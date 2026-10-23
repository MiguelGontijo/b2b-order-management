package com.bayer.b2b.order_management.infrastructure.messaging.outbox;

import com.bayer.b2b.order_management.infrastructure.messaging.PedidoStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Suite de testes unitários para o Motor de Drenagem do Transactional Outbox.
 * <p>
 * Decisões Arquiteturais Classe Ouro:
 * 1. Isolamento de I/O: Simulamos as respostas do PostgreSQL e do Kafka para focar
 *    estritamente na máquina de estados do Worker.
 * 2. Validação do Rollback: Atestamos que falhas na rede disparam exceções não checadas,
 *    o que garante que o Spring (em tempo de execução) executará o Rollback no banco,
 *    impedindo a perda do evento.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Infraestrutura/Mensageria: OutboxRelayWorker")
class OutboxRelayWorkerTest {

    @Mock
    private OutboxEventoRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate;

    @InjectMocks
    private OutboxRelayWorker relayWorker;

    private OutboxEvento eventoMock;
    private String payloadJsonValido;

    @BeforeEach
    void setUp() {
        // Montamos um JSON válido que o ObjectMapper interno do Worker consiga hidratar
        payloadJsonValido = """
                {
                  "eventId": "123e4567-e89b-12d3-a456-426614174000",
                  "eventType": "PEDIDO_APROVADO",
                  "schemaVersion": "v1.0",
                  "pedidoId": "123e4567-e89b-12d3-a456-426614174001",
                  "codigoPedidoOrigem": "ERP-123",
                  "cnpjParceiro": "12345678901234",
                  "statusAnterior": "PENDENTE",
                  "statusNovo": "APROVADO",
                  "valorTotal": 1500.00,
                  "versaoPedido": 1,
                  "ocorridoEm": "2026-10-23T10:00:00Z"
                }
                """;

        eventoMock = new OutboxEvento(
                "123e4567-e89b-12d3-a456-426614174001",
                "Pedido",
                "PEDIDO_APROVADO",
                payloadJsonValido
        );
    }

    @Nested
    @DisplayName("1. Comportamento de Standby (Fila Vazia)")
    class FilaVaziaTests {

        @Test
        @DisplayName("Deve retornar silenciosamente sem acionar o Kafka caso não existam eventos no banco")
        void deveIgnorarQuandoNaoHouverEventos() {
            // Arrange
            when(outboxRepository.buscarLoteParaProcessamento(anyInt())).thenReturn(List.of());

            // Act
            relayWorker.processarEventosPendentes();

            // Assert
            verifyNoInteractions(kafkaTemplate);
            verify(outboxRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("2. Caminho Feliz (Envio com Sucesso)")
    class EnvioSucessoTests {

        @Test
        @DisplayName("Deve ler o evento, enviar para o Kafka síncrono e DELETAR do banco relacional")
        void deveProcessarEExpurgarEventoComSucesso() {
            // Arrange
            when(outboxRepository.buscarLoteParaProcessamento(anyInt())).thenReturn(List.of(eventoMock));

            // Simulamos o ACK (Confirmação de recebimento) instantâneo do Kafka
            CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> futureSucesso = new CompletableFuture<>();
            futureSucesso.complete(mock(SendResult.class));

            when(kafkaTemplate.send(eq("b2b.order.status.events"), eq(eventoMock.getAggregateId()), any(PedidoStatusChangedEvent.class)))
                    .thenReturn(futureSucesso);

            // Act
            relayWorker.processarEventosPendentes();

            // Assert
            // 1. Garante que disparou para a rede
            verify(kafkaTemplate, times(1)).send(eq("b2b.order.status.events"), eq(eventoMock.getAggregateId()), any(PedidoStatusChangedEvent.class));

            // 2. Garante que limpou a tabela para não processar de novo
            verify(outboxRepository, times(1)).delete(eventoMock);
        }
    }

    @Nested
    @DisplayName("3. Falhas e Rollback (Resiliência de Rede)")
    class FalhaRedeTests {

        @Test
        @DisplayName("Deve estourar exceção para acionar Rollback se o Kafka não responder (Timeout/Falha)")
        void deveLancarExcecaoEAbortarDelecaoQuandoKafkaFalhar() {
            // Arrange
            when(outboxRepository.buscarLoteParaProcessamento(anyInt())).thenReturn(List.of(eventoMock));

            // Simulamos uma falha física de conexão com o Broker
            CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> futureFalha = new CompletableFuture<>();
            futureFalha.completeExceptionally(new RuntimeException("Kafka Broker Down"));

            when(kafkaTemplate.send(anyString(), anyString(), any(PedidoStatusChangedEvent.class)))
                    .thenReturn(futureFalha);

            // Act & Assert
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    relayWorker.processarEventosPendentes()
            );

            assertEquals("Falha na ponte entre o Banco Relacional e o Broker de Mensageria", exception.getMessage());

            // Garantia de Segurança: Como a rede falhou, a deleção NO BANCO NÃO PODE acontecer.
            verify(outboxRepository, never()).delete(any());
        }
    }
}