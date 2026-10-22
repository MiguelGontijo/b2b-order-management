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
 * Suite de testes unitários para a Orquestração de Cancelamento de Pedidos.
 * <p>
 * O foco desta classe é atestar as ramificações críticas de negócio e performance:
 * 1. Otimização de Infraestrutura: Cancelamentos de pedidos PENDENTES não devem
 *    acionar bloqueios pessimistas no banco de dados, poupando recursos.
 * 2. Integridade de Estorno: Cancelamentos de pedidos que já consumiram limite
 *    devem obrigatoriamente travar o parceiro e devolver o saldo com precisão matemática.
 * 3. Blindagem de Estados Terminais: Pedidos ENTREGUES ou já CANCELADOS não podem
 *    sofrer mutação, abortando a transação imediatamente (Fail-Fast).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Caso de Uso: Cancelar Pedido")
class CancelarPedidoUseCaseTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private ParceiroRepository parceiroRepository;

    @Mock
    private OrderEventPublisherPort eventPublisher;

    @InjectMocks
    private CancelarPedidoUseCase useCase;

    private UUID pedidoId;
    private Parceiro parceiro;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        pedidoId = UUID.randomUUID();
        // Simulamos o parceiro com 5000.00 de limite e ID 1L
        parceiro = new Parceiro("Bayer B2B", "99999999999999", new BigDecimal("5000.00"));
        ReflectionTestUtils.setField(parceiro, "id", 1L);

        pedido = new Pedido(parceiro);
        // Pedido possui um valor financeiro de 1000.00
        pedido.adicionarItem(new ItemPedido(pedido, "PROD-A", 1, new BigDecimal("1000.00")));
    }

    @Nested
    @DisplayName("1. Fluxos de Sucesso (Com e Sem Estorno)")
    class CancelamentoSucessoTests {

        @Test
        @DisplayName("Deve cancelar pedido PENDENTE sem acionar Pessimistic Lock (Economia de Banco)")
        void deveCancelarPendenteSemEstorno() {
            // Arrange - O pedido já nasce PENDENTE
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(pedidoRepository.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

            // Act
            Pedido pedidoCancelado = useCase.execute(pedidoId);

            // Assert - Validações de Domínio
            assertEquals(StatusPedido.CANCELADO, pedidoCancelado.getStatus());
            assertEquals(0, new BigDecimal("5000.00").compareTo(parceiro.getLimiteCredito()),
                    "O limite original deve permanecer intocado, pois nunca foi deduzido");

            // Assert - Validações Arquiteturais (Garantia de Performance)
            verify(parceiroRepository, never()).findByIdWithPessimisticLock(any());

            verify(pedidoRepository, times(1)).save(pedido);
            verify(eventPublisher, times(1)).publicarEventoStatus(pedido);
        }

        @Test
        @DisplayName("Deve cancelar pedido APROVADO, acionar Pessimistic Lock e estornar o limite do cliente")
        void deveCancelarAprovadoComEstornoFinanceiro() {
            // Arrange - Forçamos o pedido para APROVADO
            pedido.aprovar();

            // Simulamos que, no passado, esse parceiro teve os 1000.00 descontados (ficou com 4000.00)
            ReflectionTestUtils.setField(parceiro, "limiteCredito", new BigDecimal("4000.00"));

            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(parceiro));
            when(pedidoRepository.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));

            // Act
            Pedido pedidoCancelado = useCase.execute(pedidoId);

            // Assert - Validações de Domínio
            assertEquals(StatusPedido.CANCELADO, pedidoCancelado.getStatus());

            // Garantia Ouro: Os 1000.00 do pedido voltaram para os 4000.00 do limite?
            assertEquals(0, new BigDecimal("5000.00").compareTo(parceiro.getLimiteCredito()),
                    "O orquestrador deve ter devolvido com precisão matemática o valor ao limite do cliente");

            // Assert - Validações Arquiteturais (Garantia ACID)
            verify(parceiroRepository, times(1)).findByIdWithPessimisticLock(1L);
            verify(pedidoRepository, times(1)).save(pedido);
            verify(eventPublisher, times(1)).publicarEventoStatus(pedido);
        }
    }

    @Nested
    @DisplayName("2. Escudos de Domínio (Máquina de Estados)")
    class FalhasDominioTests {

        @Test
        @DisplayName("Deve abortar o cancelamento e proteger o banco se o pedido já estiver ENTREGUE")
        void deveFalharAoTentarCancelarPedidoEntregue() {
            // Arrange - Forçamos o pedido até o estado terminal de sucesso
            pedido.aprovar();
            pedido.processar();
            pedido.enviar();
            pedido.entregar();

            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));

            // Act & Assert
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Violação da Máquina de Estados: O pedido não pode transitar de ENTREGUE para CANCELADO", exception.getMessage());

            // Garantia de Side-Effects limpos
            verify(parceiroRepository, never()).findByIdWithPessimisticLock(any());
            verify(pedidoRepository, never()).save(any());
            verify(eventPublisher, never()).publicarEventoStatus(any());
        }
    }

    @Nested
    @DisplayName("3. Escudos de Infraestrutura")
    class FalhasInfraestruturaTests {

        @Test
        @DisplayName("Deve lançar NullPointerException quando o ID do pedido for nulo")
        void deveFalharQuandoPedidoIdNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(null)
            );
            assertEquals("O ID do pedido não pode ser nulo para cancelamento", exception.getMessage());
            verifyNoInteractions(pedidoRepository, parceiroRepository, eventPublisher);
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando o pedido não existir na base")
        void deveFalharQuandoPedidoNaoExistir() {
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.empty());

            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Pedido não localizado para o ID: " + pedidoId, exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalStateException se o estorno for exigido, mas o parceiro não for encontrado no Lock")
        void deveFalharQuandoParceiroNaoExistirParaEstorno() {
            // Arrange - Pedido exige estorno
            pedido.aprovar();

            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(parceiroRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.empty());

            // Act & Assert
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    useCase.execute(pedidoId)
            );
            assertEquals("Parceiro atrelado ao pedido não localizado para estorno", exception.getMessage());

            // O pedido foi mutado na memória (pedido.cancelar() roda antes), mas a persistência é abortada
            verify(pedidoRepository, never()).save(any());
            verify(eventPublisher, never()).publicarEventoStatus(any());
        }
    }
}