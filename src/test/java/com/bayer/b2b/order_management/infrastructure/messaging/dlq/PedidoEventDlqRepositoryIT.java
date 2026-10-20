package com.bayer.b2b.order_management.infrastructure.messaging.dlq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de Testes de Integração para a Dead Letter Queue (DLQ) no MongoDB.
 * <p>
 * O foco desta classe é validar as queries customizadas derivadas pelo Spring Data.
 * Utiliza a anotação @DataMongoTest para carregar estritamente a fatia de persistência
 * NoSQL, garantindo execução rápida e isolamento do resto do contexto Spring.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DataMongoTest
@DisplayName("Integração MongoDB: PedidoEventDlqRepository")
class PedidoEventDlqRepositoryIT {

    @Autowired
    private PedidoEventDlqRepository repository;

    /**
     * Utilitário nativo do Spring Data Mongo.
     * Usado aqui para limpar as coleções e garantir que um teste não polua o banco de dados do outro.
     */
    @Autowired
    private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        // Dropa a coleção após cada teste para garantir isolamento absoluto (Clean Slate)
        mongoTemplate.dropCollection(PedidoEventDlqDocument.class);
    }

    @Nested
    @DisplayName("1. Mecanismo de Retry (Worker Queue)")
    class ConsultasDeReprocessamentoTests {

        @Test
        @DisplayName("Deve retornar apenas pendentes e ordenar estritamente pela falha mais antiga (FIFO)")
        void deveRetornarPendentesOrdenadosPelaDataMaisAntiga() {
            // Arrange - Criação das entidades de contingência
            UUID pedidoId = UUID.randomUUID();

            PedidoEventDlqDocument docRecente = new PedidoEventDlqDocument(pedidoId, "{}", "Timeout Hoje");
            PedidoEventDlqDocument docAntigo = new PedidoEventDlqDocument(pedidoId, "{}", "Timeout Ontem");
            PedidoEventDlqDocument docReprocessado = new PedidoEventDlqDocument(pedidoId, "{}", "Timeout Semana Passada");

            // Truque Arquitetural: A Entidade é perfeitamente encapsulada e não possui setDataFalha().
            // Usamos Reflection para viajar no tempo e forjar o cenário do banco de dados.
            Instant agora = Instant.now();
            ReflectionTestUtils.setField(docRecente, "dataFalha", agora);
            ReflectionTestUtils.setField(docAntigo, "dataFalha", agora.minus(1, ChronoUnit.DAYS));
            ReflectionTestUtils.setField(docReprocessado, "dataFalha", agora.minus(7, ChronoUnit.DAYS));

            // Marcamos o documento mais antigo de todos como já resolvido
            docReprocessado.marcarComoReprocessado();

            // Salvamos fora de ordem de propósito para atestar que o banco de dados fará a ordenação (ORDER BY) corretamente
            repository.saveAll(List.of(docRecente, docReprocessado, docAntigo));

            // Act
            List<PedidoEventDlqDocument> pendentes = repository.findByReprocessadoFalseOrderByDataFalhaAsc();

            // Assert
            assertNotNull(pendentes);
            assertEquals(2, pendentes.size(), "O documento que já foi reprocessado deve ser rigorosamente ignorado pela query");

            // Validação de FIFO (First In, First Out)
            PedidoEventDlqDocument primeiroDaFila = pendentes.get(0);
            PedidoEventDlqDocument segundoDaFila = pendentes.get(1);

            assertEquals(docAntigo.getErroDetalhado(), primeiroDaFila.getErroDetalhado(),
                    "O documento de 'Ontem' deve vir primeiro (mais antigo)");
            assertEquals(docRecente.getErroDetalhado(), segundoDaFila.getErroDetalhado(),
                    "O documento de 'Hoje' deve vir na sequência");
        }

        @Test
        @DisplayName("Deve retornar lista vazia se todos os documentos já estiverem reprocessados")
        void deveRetornarVazioQuandoTudoEstiverResolvido() {
            // Arrange
            PedidoEventDlqDocument docResolvido = new PedidoEventDlqDocument(UUID.randomUUID(), "{}", "Falha Isolada");
            docResolvido.marcarComoReprocessado();
            repository.save(docResolvido);

            // Act
            List<PedidoEventDlqDocument> pendentes = repository.findByReprocessadoFalseOrderByDataFalhaAsc();

            // Assert
            assertTrue(pendentes.isEmpty(), "Queries Spring Data devem retornar lista vazia (nunca nula) ao não encontrar registros");
        }
    }

    @Nested
    @DisplayName("2. Observabilidade e Tracing do SRE")
    class ConsultasDeRastreioTests {

        @Test
        @DisplayName("Deve buscar todas as falhas ocorridas em um Pedido específico")
        void deveLocalizarFalhasPorPedidoId() {
            // Arrange
            UUID pedidoAlvo = UUID.randomUUID();
            UUID outroPedido = UUID.randomUUID();

            repository.save(new PedidoEventDlqDocument(pedidoAlvo, "{\"tentativa\":1}", "Timeout"));
            repository.save(new PedidoEventDlqDocument(pedidoAlvo, "{\"tentativa\":2}", "Connection Refused"));
            repository.save(new PedidoEventDlqDocument(outroPedido, "{}", "Timeout"));

            // Act
            List<PedidoEventDlqDocument> historico = repository.findByPedidoId(pedidoAlvo);

            // Assert
            assertNotNull(historico);
            assertEquals(2, historico.size(), "Deve localizar exatamente as duas falhas vinculadas ao Pedido Alvo");

            // Garante que o documento do outro pedido não vazou para a consulta atual
            boolean contemVazamento = historico.stream().anyMatch(doc -> !doc.getPedidoId().equals(pedidoAlvo));
            assertFalse(contemVazamento, "A consulta não pode vazar dados de outros UUIDs");
        }

        @Test
        @DisplayName("Deve retornar lista vazia quando consultar o histórico de um Pedido sem falhas")
        void deveRetornarVazioParaPedidoComSucesso() {
            // Act
            List<PedidoEventDlqDocument> historico = repository.findByPedidoId(UUID.randomUUID());

            // Assert
            assertNotNull(historico);
            assertTrue(historico.isEmpty());
        }
    }
}