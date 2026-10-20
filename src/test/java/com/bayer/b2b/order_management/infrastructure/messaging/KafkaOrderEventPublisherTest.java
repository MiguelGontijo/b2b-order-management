package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.infrastructure.messaging.dlq.PedidoEventDlqDocument;
import com.bayer.b2b.order_management.infrastructure.messaging.dlq.PedidoEventDlqRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para o Adaptador de Mensageria Outbound (Kafka).
 * <p>
 * O foco desta classe é atestar o design de resiliência e Event-Driven Architecture (EDA).
 * Validamos não apenas o "caminho feliz" assíncrono, mas focamos maciçamente nas
 * táticas de Fallback (Design for Failure) para atestar que falhas de rede do broker
 * garantam o enfileiramento das mensagens na DLQ sem afetar a thread principal.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Infraestrutura/Mensageria: KafkaOrderEventPublisher")
class KafkaOrderEventPublisherTest {

    @Mock
    private KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate;

    @Mock
    private PedidoEventDlqRepository dlqRepository;

    @InjectMocks
    private KafkaOrderEventPublisher publisher;

    @Captor
    private ArgumentCaptor<PedidoEventDlqDocument> dlqCaptor;

    private Pedido pedidoBase;

    @BeforeEach
    void setUp() {
        // Preparamos um Pedido rico em memória para servir de payload para os eventos
        Parceiro parceiro = new Parceiro("Bayer Agro", "11111111111111", new BigDecimal("50000.00"));
        pedidoBase = new Pedido(parceiro);
        pedidoBase.transitarStatus(StatusPedido.APROVADO);
        pedidoBase.incrementarValorTotal(new BigDecimal("1500.00"));
    }

    @Nested
    @DisplayName("1. Fail-Fast Inicial")
    class FailFastTests {

        @Test
        @DisplayName("Deve lançar NullPointerException se tentar publicar um pedido nulo antes de acionar o broker")
        void deveFalharQuandoPedidoForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    publisher.publicarEventoStatus(null)
            );
            assertEquals("O pedido não pode ser nulo para publicação do evento", exception.getMessage());

            // Garantia Arquitetural: Se quebrou na entrada, as bibliotecas de infra não podem ser acionadas
            verifyNoInteractions(kafkaTemplate, dlqRepository);
        }
    }

    @Nested
    @DisplayName("2. Caminho Feliz (Happy Path Assíncrono)")
    class SucessoAssincronoTests {

        @Test
        @DisplayName("Deve enviar o evento para o Kafka e NÃO acionar a DLQ do MongoDB em caso de sucesso da rede")
        void devePublicarNoKafkaComSucesso() {
            // Arrange - Precisamos simular a resposta de sucesso da biblioteca interna do Kafka
            SendResult<String, PedidoStatusChangedEvent> sendResultMock = mock(SendResult.class);
            RecordMetadata metadataMock = mock(RecordMetadata.class);

            when(sendResultMock.getRecordMetadata()).thenReturn(metadataMock);
            when(metadataMock.partition()).thenReturn(0);
            when(metadataMock.offset()).thenReturn(101L);

            // Simulamos o CompletableFuture devolvendo sucesso quase instantâneo
            CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> futureSucesso =
                    CompletableFuture.completedFuture(sendResultMock);

            when(kafkaTemplate.send(eq("b2b.order.status.events"), eq(pedidoBase.getId().toString()), any(PedidoStatusChangedEvent.class)))
                    .thenReturn(futureSucesso);

            // Act
            publisher.publicarEventoStatus(pedidoBase);

            // Assert
            // Verifica se o kafkaTemplate foi chamado com o tópico, key (UUID) e payload corretos
            verify(kafkaTemplate, times(1)).send(
                    eq("b2b.order.status.events"),
                    eq(pedidoBase.getId().toString()),
                    any(PedidoStatusChangedEvent.class)
            );

            // GARANTIA SRE: Em cenário de sucesso, o banco da contingência jamais deve ser tocado
            verifyNoInteractions(dlqRepository);
        }
    }

    @Nested
    @DisplayName("3. Resiliência e Fallback (Dead Letter Queue)")
    class ResilienciaDLQTests {

        @Test
        @DisplayName("Se o Kafka falhar, deve interceptar assincronamente e salvar o payload intacto na DLQ do MongoDB")
        void deveSalvarNaDLQQuandoKafkaCair() {
            // Arrange - Simulamos um Timeout da rede com o broker do Kafka
            RuntimeException kafkaException = new RuntimeException("Timeout broker not reachable");
            CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> futureFalha =
                    CompletableFuture.failedFuture(kafkaException);

            when(kafkaTemplate.send(eq("b2b.order.status.events"), eq(pedidoBase.getId().toString()), any(PedidoStatusChangedEvent.class)))
                    .thenReturn(futureFalha);

            // Act
            // O publisher fará o envio. O CompletableFuture retornará a falha simulada e ativará
            // a lambda de tratamento reativo que chamará salvarNaDeadLetterQueue().
            publisher.publicarEventoStatus(pedidoBase);

            // Assert - Verificação do Fallback
            verify(dlqRepository, times(1)).save(dlqCaptor.capture());
            PedidoEventDlqDocument documentoSalvo = dlqCaptor.getValue();

            // Asseguramos que não perdemos nenhum dado durante o desvio de fluxo
            assertNotNull(documentoSalvo, "O documento de contingência deve ser criado");
            assertEquals(pedidoBase.getId(), documentoSalvo.getPedidoId(), "O UUID do pedido original deve ser mantido para rastreio");
            assertEquals("Timeout broker not reachable", documentoSalvo.getErroDetalhado(), "A mensagem exata do Kafka deve ser salva para a equipe SRE");

            // O Payload JSON não pode ser vazio e deve conter o ID e o status (GSON serialize test)
            assertNotNull(documentoSalvo.getEventoPayloadJson());
            assertTrue(documentoSalvo.getEventoPayloadJson().contains(pedidoBase.getId().toString()));
            assertTrue(documentoSalvo.getEventoPayloadJson().contains("APROVADO"));

            // O evento na DLQ deve nascer como 'não reprocessado'
            assertFalse(documentoSalvo.isReprocessado());
        }

        @Test
        @DisplayName("Se houver Dupla Falha (Kafka Caiu + Mongo Caiu), o publisher deve engolir o erro e proteger a Transação Relacional")
        void deveEngolirFalhaFatalAoSalvarNaDLQ() {
            // Arrange - Falha 1: O Kafka cai
            CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> futureFalhaKafka =
                    CompletableFuture.failedFuture(new RuntimeException("Kafka Down"));

            when(kafkaTemplate.send(anyString(), anyString(), any(PedidoStatusChangedEvent.class)))
                    .thenReturn(futureFalhaKafka);

            // Arrange - Falha 2: O Banco de Dados de contingência (MongoDB) também cai
            doThrow(new RuntimeException("MongoDB Connection Refused"))
                    .when(dlqRepository).save(any(PedidoEventDlqDocument.class));

            // Act & Assert
            // Prova Nível Ouro de Resiliência: O método não pode lançar exceção para quem o chamou (o UseCase).
            // Se lançasse, a anotação @Transactional do UseCase daria Rollback no banco de dados principal de Pedidos (PostgreSQL),
            // causando perda de vendas só porque a mensageria/auditoria falhou. A falha da infra NÃO deve parar a venda B2B.
            assertDoesNotThrow(() -> publisher.publicarEventoStatus(pedidoBase));

            // Validamos que ele realmente tentou usar a DLQ antes de engolir a exceção fatal
            verify(dlqRepository, times(1)).save(any(PedidoEventDlqDocument.class));
        }
    }
}