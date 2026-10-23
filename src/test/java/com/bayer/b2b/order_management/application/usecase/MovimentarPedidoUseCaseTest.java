package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.exception.PedidoNaoEncontradoException;
import com.bayer.b2b.order_management.domain.exception.TransicaoStatusInvalidaException;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para a Orquestração Logística do Pedido.
 * <p>
 * O foco desta classe é atestar o comportamento DRY (Don't Repeat Yourself) do UseCase:
 * 1. Fluxos de Negócio: Garante que cada método público da fachada logística
 *    invoque a intenção correta na Raiz de Agregação.
 * 2. Performance e Coesão: Diferente da aprovação, atestamos que a logística
 *    NÃO aciona bloqueios físicos (Pessimistic Lock) desnecessários no banco.
 * 3. Prevenção de Side-Effects: Garante que violações de estado anulem a gravação e a mensageria.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Caso de Uso: Movimentar Pedido (Logística)")
class MovimentarPedidoUseCaseTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private OrderEventPublisherPort eventPublisher;

    @InjectMocks
    private MovimentarPedidoUseCase useCase;

    private UUID pedidoId;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        pedidoId = UUID.randomUUID();
        // Setup base: Um pedido nasce invariavelmente como PENDENTE
        Parceiro parceiro = new Parceiro("Bayer Transporte", "99999999999999", new BigDecimal("10000.00"));
        pedido = new Pedido(parceiro, "ERP-123");
    }

    @Nested
    @DisplayName("1. Orquestração e Execução (Happy Paths)")
    class FluxoLogisticoTests {

        @Test
        @DisplayName("Deve orquestrar o processamento (separação) do pedido com sucesso")
        void deveProcessarPedido() {
            // Arrange - Avançamos o estado para APROVADO para permitir o processamento legal
            pedido.aprovar();
            prepararMocksDeSucesso();

            // Act
            Pedido resultado = useCase.processar(pedidoId);

            // Assert
            assertEquals(StatusPedido.EM_PROCESSAMENTO, resultado.getStatus(), "O orquestrador deve invocar pedido.processar()");
            verificarAuditoriaArquitetural();
        }

        @Test
        @DisplayName("Deve orquestrar o envio do pedido para a transportadora com sucesso")
        void deveEnviarPedido() {
            // Arrange - Avançamos o estado até EM_PROCESSAMENTO
            pedido.aprovar();
            pedido.processar();
            prepararMocksDeSucesso();

            // Act
            Pedido resultado = useCase.enviar(pedidoId);

            // Assert
            assertEquals(StatusPedido.ENVIADO, resultado.getStatus(), "O orquestrador deve invocar pedido.enviar()");
            verificarAuditoriaArquitetural();
        }

        @Test
        @DisplayName("Deve orquestrar a entrega final do pedido com sucesso")
        void deveEntregarPedido() {
            // Arrange - Avançamos o estado até ENVIADO
            pedido.aprovar();
            pedido.processar();
            pedido.enviar();
            prepararMocksDeSucesso();

            // Act
            Pedido resultado = useCase.entregar(pedidoId);

            // Assert
            assertEquals(StatusPedido.ENTREGUE, resultado.getStatus(), "O orquestrador deve invocar pedido.entregar()");
            verificarAuditoriaArquitetural();
        }

        private void prepararMocksDeSucesso() {
            // Utilizamos leitura otimizada (sem Lock) e interceptamos o Save para devolver a mesma instância
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));
            when(pedidoRepository.save(any(Pedido.class))).thenAnswer(i -> i.getArgument(0));
        }

        private void verificarAuditoriaArquitetural() {
            verify(pedidoRepository, times(1)).findById(pedidoId);
            verify(pedidoRepository, times(1)).save(pedido);
            verify(eventPublisher, times(1)).publicarEventoStatus(pedido);
            verifyNoMoreInteractions(pedidoRepository, eventPublisher);
        }
    }

    @Nested
    @DisplayName("2. Escudos de Domínio (Fail-Fast da Máquina de Estados)")
    class FalhasDominioTests {

        @Test
        @DisplayName("Deve barrar operações fora de ordem e anular side-effects (Rollback logístico)")
        void deveFalharEProtegerBancoAoPularEtapaLogistica() {
            // Arrange - O pedido está PENDENTE. Vamos tentar forçar a "entrega" prematura.
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.of(pedido));

            // Act & Assert
            TransicaoStatusInvalidaException exception = assertThrows(TransicaoStatusInvalidaException.class, () ->
                    useCase.entregar(pedidoId)
            );
            assertEquals("Violação da Máquina de Estados: O pedido não pode transitar de PENDENTE para ENTREGUE", exception.getMessage());

            // GARANTIA DE OURO: A infraestrutura nunca será notificada de uma mutação ilegal
            verify(pedidoRepository, never()).save(any());
            verify(eventPublisher, never()).publicarEventoStatus(any());
        }
    }

    @Nested
    @DisplayName("3. Escudos de Infraestrutura (Contratos Privados)")
    class FalhasInfraestruturaTests {

        @Test
        @DisplayName("Deve lançar NullPointerException se o ID fornecido for nulo em qualquer método logístico")
        void deveFalharQuandoIdForNulo() {
            // Testamos a blindagem na camada do helper privado extrairPedido(UUID)
            assertThrows(NullPointerException.class, () -> useCase.processar(null));
            assertThrows(NullPointerException.class, () -> useCase.enviar(null));
            assertThrows(NullPointerException.class, () -> useCase.entregar(null));

            verifyNoInteractions(pedidoRepository, eventPublisher);
        }

        @Test
        @DisplayName("Deve lançar PedidoNaoEncontradoException se o UUID não existir no banco de dados")
        void deveFalharQuandoPedidoNaoExistir() {
            when(pedidoRepository.findById(pedidoId)).thenReturn(Optional.empty());

            PedidoNaoEncontradoException exception = assertThrows(PedidoNaoEncontradoException.class, () ->
                    useCase.processar(pedidoId)
            );
            assertEquals("Pedido não localizado para o ID: " + pedidoId, exception.getMessage());

            verify(pedidoRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }
}