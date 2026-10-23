package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.messaging.outbox.OutboxEvento;
import com.bayer.b2b.order_management.infrastructure.messaging.outbox.OutboxEventoRepository;
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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para o Adaptador de Mensageria (Outbox Pattern).
 * <p>
 * Decisões Arquiteturais Classe Ouro (Fase 3):
 * 1. Fim do Mock de Rede: Diferente da versão anterior (onde mockávamos o KafkaTemplate
 *    e simulávamos timeouts), agora testamos exclusivamente a persistência atômica.
 * 2. Validação Transacional: O teste garante que o evento seja convertido para JSON
 *    e salvo no OutboxEventoRepository, honrando o princípio do Dual-Write Prevention.
 * 3. Propagação de Falhas: Se o banco de dados falhar ao salvar o Outbox, a exceção
 *    DEVE subir (borbulhar) para que a anotação @Transactional do UseCase efetue o Rollback
 *    do Pedido, garantindo que nunca teremos um Pedido salvo sem seu evento correspondente.
 * </p>
 *
 * @author Miguel
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Infraestrutura/Mensageria: KafkaOrderEventPublisher (Outbox Pattern)")
class KafkaOrderEventPublisherTest {

    @Mock
    private OutboxEventoRepository outboxRepository;

    // A injeção de Mocks agora resolve apenas o repositório relacional.
    // O ObjectMapper é instanciado internamente de forma isolada pela classe.
    @InjectMocks
    private KafkaOrderEventPublisher publisher;

    @Captor
    private ArgumentCaptor<OutboxEvento> outboxCaptor;

    private Pedido pedidoBase;

    @BeforeEach
    void setUp() {
        // Preparamos um Pedido rico em memória para servir de payload para os eventos
        Parceiro parceiro = new Parceiro("Bayer Agro", "11111111111111", new BigDecimal("50000.00"));
        pedidoBase = new Pedido(parceiro, "ERP-123");
        pedidoBase.aprovar();
        pedidoBase.incrementarValorTotal(new BigDecimal("1500.00"));
    }

    @Nested
    @DisplayName("1. Fail-Fast e Defesas de Fronteira")
    class FailFastTests {

        @Test
        @DisplayName("Deve lançar NullPointerException se tentar publicar um pedido nulo")
        void deveFalharQuandoPedidoForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    publisher.publicarEventoStatus(null)
            );
            assertEquals("O pedido não pode ser nulo para publicação do evento", exception.getMessage());

            // Garantia Arquitetural: O banco de dados não pode ser acionado
            verifyNoInteractions(outboxRepository);
        }
    }

    @Nested
    @DisplayName("2. Caminho Feliz (Garantia ACID)")
    class SucessoTransacionalTests {

        @Test
        @DisplayName("Deve empacotar o evento em JSON e salvar na tabela Outbox com sucesso")
        void deveSalvarEventoNoOutboxComSucesso() {
            // Act
            publisher.publicarEventoStatus(pedidoBase);

            // Assert
            // Verifica se o repositório do banco relacional foi acionado para salvar a intenção de envio
            verify(outboxRepository, times(1)).save(outboxCaptor.capture());

            OutboxEvento eventoSalvo = outboxCaptor.getValue();

            assertNotNull(eventoSalvo, "A entidade Outbox não pode ser nula");
            assertEquals(pedidoBase.getId().toString(), eventoSalvo.getAggregateId(), "O ID do agregado deve bater com o ID do Pedido");
            assertEquals("Pedido", eventoSalvo.getAggregateType());
            assertEquals("PEDIDO_APROVADO", eventoSalvo.getEventType());

            // Validações do Payload JSON (Jackson Serialization Test)
            String payloadJson = eventoSalvo.getPayload();
            assertNotNull(payloadJson);
            assertTrue(payloadJson.contains(pedidoBase.getId().toString()), "O JSON deve conter o ID do pedido");
            assertTrue(payloadJson.contains("ERP-123"), "O JSON deve conter o código de origem");
            assertTrue(payloadJson.contains("APROVADO"), "O JSON deve conter o status");
            assertTrue(payloadJson.contains("eventId"), "O JSON deve conter a chave de idempotência");
        }
    }

    @Nested
    @DisplayName("3. Resiliência e Rollback (Falha de Banco de Dados)")
    class ResilienciaBancoTests {

        @Test
        @DisplayName("Deve propagar a exceção caso o banco de dados falhe, garantindo o Rollback do UseCase")
        void devePropagarExcecaoQuandoBancoFalhar() {
            // Arrange - Simulamos uma queda de conexão com o PostgreSQL na hora de salvar o Outbox
            doThrow(new RuntimeException("Connection Refused (PostgreSQL)"))
                    .when(outboxRepository).save(any(OutboxEvento.class));

            // Act & Assert
            // Prova Nível Ouro: Diferente da Fase 2 (que engolia o erro), na arquitetura Outbox
            // nós QUEREMOS que a exceção estoure na cara do UseCase. Se o evento não for salvo,
            // o Pedido TAMBÉM NÃO PODE ser salvo (Atomicidade). O Spring fará o Rollback global.
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    publisher.publicarEventoStatus(pedidoBase)
            );

            assertEquals("Connection Refused (PostgreSQL)", exception.getMessage());
        }
    }
}