package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.execption.ResourceNotFoundException;
import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para a Orquestração de Aprovação de Pedidos.
 * <p>
 * O foco desta classe é atestar as garantias transacionais e financeiras:
 * 1. Pessimistic Lock: O parceiro deve ser extraído estritamente via Lock para evitar Race Conditions.
 * 2. Prevenção de Rollback: Se o domínio estourar erro (falta de saldo ou status ilegal),
 *    o repositório de persistência e a mensageria JAMAIS podem ser acionados.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Caso de Uso: Aprovar Pedido")
class AprovarPedidoUseCaseTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private ParceiroRepository parceiroRepository;

    @Mock
    private OrderEventPublisherPort eventPublisher;

    @InjectMocks
    private AprovarPedidoUseCase useCase;

    private UUID pedidoId;
    private Parceiro parceiro;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        pedidoId = UUID.randomUUID();
        // Parceiro com 5000.00 de limite inicial
        parceiro = new Parceiro("Bayer B2B", "11111111111111", new BigDecimal("5000.00"));
        ReflectionTestUtils.setField(parceiro, "id", 1L);

        pedido = new Pedido(parceiro);
        // Adicionamos um item de 2000.00 para que o pedido tenha valor financeiro a ser cobrado
        pedido.adicionarItem(new ItemPedido(pedido, "PROD-A", 2, new BigDecimal("1000.00")));
    }

    @Nested
    @DisplayName("1. Orquestração e Execução (Happy Path)")
    class OrquestracaoSucessoTests {

        @Test
        @DisplayName("Deve debitar o limite com Pessimistic Lock, aprovar e publicar evento")
        void deveAprovarPedidoComSucesso() {
            // Arrange
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(parceiro));
            when(pedidoRepository.save(any(Pedido.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Pedido pedidoAprovado = useCase.execute(pedidoId);

            // Assert - Validações de Domínio
            assertEquals(StatusPedido.APROVADO, pedidoAprovado.getStatus(), "O pedido deve transitar para APROVADO");

            // Verifica o efeito colateral no limite de crédito (5000.00 - 2000.00 = 3000.00)
            assertEquals(0, new BigDecimal("3000.00").compareTo(parceiro.getLimiteCredito()),
                    "O orquestrador deve ter invocado o débito no domínio antes de salvar");

            // Assert - Validações de Infraestrutura (Interações)
            verify(pedidoRepository, times(1)).findById(pedidoId);
            verify(parceiroRepository, times(1)).findByIdWithPessimisticLock(1L);
            verify(pedidoRepository, times(1)).save(pedido);
            verify(eventPublisher, times(1)).publicarEventoStatus(pedido);
        }
    }

    @Nested
    @DisplayName("2. Escudos de Domínio (Fail-Fast Financeiro e Logístico)")
    class FalhasDominioTests {

        @Test
        @DisplayName("Deve abortar operação e não salvar nada se o saldo for insuficiente")
        void deveFalharEProtegerBancoQuandoSaldoInsuficiente() {
            // Arrange - Pedido de 2000.00, mas vamos deixar o parceiro com limite de 500.00
            ReflectionTestUtils.setField(parceiro, "limiteCredito", new BigDecimal("500.00"));

            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(parceiro));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Crédito insuficiente para o parceiro: 11111111111111", exception.getMessage());

            // GARANTIA DE OURO: Bloqueio de Side-Effects
            verify(pedidoRepository, never()).save(any());
            verify(eventPublisher, never()).publicarEventoStatus(any());
        }

        @Test
        @DisplayName("Deve abortar operação se o pedido já estiver APROVADO (Violação da Máquina de Estados)")
        void deveFalharQuandoTransicaoForInvalida() {
            // Arrange - Simulamos um pedido que já foi aprovado no passado
            pedido.aprovar();

            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(parceiro));

            // Act & Assert
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Violação da Máquina de Estados: O pedido não pode transitar de APROVADO para APROVADO", exception.getMessage());

            // GARANTIA DE OURO: Bloqueio de Side-Effects
            verify(pedidoRepository, never()).save(any());
            verify(eventPublisher, never()).publicarEventoStatus(any());
        }
    }

    @Nested
    @DisplayName("3. Escudos de Infraestrutura (Fail-Fast Básico)")
    class FalhasInfraestruturaTests {

        @Test
        @DisplayName("Deve lançar NullPointerException quando o ID do pedido for nulo")
        void deveFalharQuandoPedidoIdNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(null)
            );
            assertEquals("O ID do pedido não pode ser nulo para aprovação", exception.getMessage());
            verifyNoInteractions(pedidoRepository, parceiroRepository, eventPublisher);
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando o pedido não existir no banco")
        void deveFalharQuandoPedidoNaoExistir() {
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Pedido não localizado para o ID: " + pedidoId, exception.getMessage());

            verify(parceiroRepository, never()).findByIdWithPessimisticLock(any());
        }

        @Test
        @DisplayName("Deve lançar IllegalStateException quando o parceiro atrelado não existir (Inconsistência Relacional)")
        void deveFalharQuandoParceiroNaoExistirParaLock() {
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.empty());

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Parceiro atrelado ao pedido não localizado na base de dados", exception.getMessage());

            verify(pedidoRepository, never()).save(any());
        }
    }
}