package com.bayer.b2b.order_management.infrastructure.messaging.dlq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de testes unitários para a Entidade Documental DLQ (Dead Letter Queue).
 * <p>
 * O foco desta classe é garantir o isolamento da informação em caso de falhas
 * na mensageria. Asseguramos que o registro do erro seja infalível (Fail-Fast)
 * e que a regra de reprocessamento impeça envios duplicados em produção,
 * protegendo o sistema de anomalias logísticas.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Infraestrutura/Mensageria: PedidoEventDlqDocument")
class PedidoEventDlqDocumentTest {

    @Nested
    @DisplayName("1. Instanciação e Auditoria Inicial (Fail-Fast)")
    class ConstrutorTests {

        @Test
        @DisplayName("Deve registrar a falha com sucesso gerando os carimbos de tempo iniciais")
        void deveCriarDocumentoDeFalhaComSucesso() {
            // Arrange
            UUID pedidoId = UUID.randomUUID();
            String payloadJson = "{\"pedidoId\":\"" + pedidoId + "\", \"status\":\"APROVADO\"}";
            String erroDetalhado = "java.net.SocketTimeoutException: Kafka broker not reachable";

            // Act
            PedidoEventDlqDocument dlqDoc = new PedidoEventDlqDocument(pedidoId, payloadJson, erroDetalhado);

            // Assert
            assertNull(dlqDoc.getId(), "O ID do documento deve ser nulo até ser gerado pelo MongoDB");
            assertEquals(pedidoId, dlqDoc.getPedidoId());
            assertEquals(payloadJson, dlqDoc.getEventoPayloadJson());
            assertEquals(erroDetalhado, dlqDoc.getErroDetalhado());

            assertNotNull(dlqDoc.getDataFalha(), "O momento exato da falha deve ser carimbado na instanciação");
            assertFalse(dlqDoc.isReprocessado(), "O documento deve nascer como pendente de reprocessamento");
            assertNull(dlqDoc.getDataReprocessamento(), "A data de reprocessamento deve ser nula inicialmente");
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se o ID do Pedido for nulo")
        void deveFalharQuandoPedidoIdNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new PedidoEventDlqDocument(null, "{}", "Timeout")
            );
            assertEquals("O ID do pedido é obrigatório para rastreabilidade", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se o Payload JSON for nulo")
        void deveFalharQuandoPayloadNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new PedidoEventDlqDocument(UUID.randomUUID(), null, "Timeout")
            );
            assertEquals("O payload do evento não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se o Erro Detalhado for nulo")
        void deveFalharQuandoErroNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new PedidoEventDlqDocument(UUID.randomUUID(), "{}", null)
            );
            assertEquals("O erro detalhado deve ser informado", exception.getMessage());
        }

        @Test
        @DisplayName("Deve cobrir a instanciação do construtor protegido exigido pelo Spring Data MongoDB")
        void deveCobrirConstrutorProtegido() {
            PedidoEventDlqDocument docVazio = new PedidoEventDlqDocument();
            assertNotNull(docVazio, "O Spring Data deve ser capaz de instanciar a entidade vazia via Reflection");
            assertNull(docVazio.getPedidoId());
            assertNull(docVazio.getDataFalha());
        }
    }

    @Nested
    @DisplayName("2. Comportamento de Negócio (Worker/CronJob de Reprocessamento)")
    class ReprocessamentoTests {

        @Test
        @DisplayName("Deve marcar o documento como reprocessado e registrar o timestamp")
        void deveMarcarComoReprocessadoComSucesso() throws InterruptedException {
            // Arrange
            PedidoEventDlqDocument dlqDoc = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Timeout");
            Instant falhaOriginal = dlqDoc.getDataFalha();

            // Pausa minúscula para garantir que o relógio do sistema passe ao menos 1 milissegundo.
            // Isso evita Flaky Tests onde a falha e o reprocessamento ocorrem no mesmo nanosegundo de CPU.
            Thread.sleep(1);

            // Act
            dlqDoc.marcarComoReprocessado();

            // Assert
            assertTrue(dlqDoc.isReprocessado(), "O status deve constar como resolvido");
            assertNotNull(dlqDoc.getDataReprocessamento(), "O carimbo de reprocessamento não pode ser nulo");
            assertTrue(dlqDoc.getDataReprocessamento().isAfter(falhaOriginal),
                    "A data de reprocessamento deve ser estritamente posterior à data de falha original");
        }

        @Test
        @DisplayName("Deve bloquear e lançar IllegalStateException se houver tentativa de duplo reprocessamento")
        void deveFalharSeJaFoiReprocessado() {
            // Arrange
            PedidoEventDlqDocument dlqDoc = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Timeout");
            dlqDoc.marcarComoReprocessado(); // Primeira vez (sucesso)

            // Act & Assert (Tentativa do Worker processar o mesmo documento duas vezes)
            IllegalStateException exception = assertThrows(IllegalStateException.class, dlqDoc::marcarComoReprocessado);

            assertEquals("Este evento já foi reprocessado e enviado com sucesso anteriormente.", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("3. Contratos Básicos: Equals e HashCode (MongoDB ID Mapping)")
    class ContratosBasicosTests {

        @Test
        @DisplayName("Equals e HashCode devem operar exclusivamente via ID string gerado pelo MongoDB")
        void equalsEHashCodeDevemRespeitarIdDoMongo() {
            // Arrange
            PedidoEventDlqDocument doc1 = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Erro A");
            PedidoEventDlqDocument doc2 = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Erro B");

            // Truque Arquitetural: Usamos ReflectionTestUtils para simular o comportamento do MongoDB
            // injetando a chave primária sem a necessidade de expor um "setId" público na Entidade Documental.
            String objectIdSimulado = "64f1c9c8e4b0a1a2b3c4d5e6";
            ReflectionTestUtils.setField(doc1, "id", objectIdSimulado);
            ReflectionTestUtils.setField(doc2, "id", objectIdSimulado);

            PedidoEventDlqDocument docDiferente = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Erro C");
            ReflectionTestUtils.setField(docDiferente, "id", "64f1c9c8e4b0a1a2b3c4d999");

            // Act & Assert
            assertEquals(doc1, doc2, "Documentos com o mesmo ObjectID do Mongo são a mesma entidade");
            assertEquals(doc1.hashCode(), doc2.hashCode(), "Colisão de Hash garantida para IDs iguais");

            assertNotEquals(doc1, docDiferente, "IDs distintos representam falhas distintas");
            assertNotEquals(doc1.hashCode(), docDiferente.hashCode());

            // Garantias do contrato do Java Object
            assertEquals(doc1, doc1, "Reflexividade");
            assertNotEquals(doc1, null, "Não-Nulidade");
            assertNotEquals(doc1, new Object(), "Tipos Diferentes");
        }
    }
}