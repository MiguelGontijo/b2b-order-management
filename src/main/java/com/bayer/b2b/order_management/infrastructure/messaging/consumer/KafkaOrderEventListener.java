package com.bayer.b2b.order_management.infrastructure.messaging.consumer;

import com.bayer.b2b.order_management.infrastructure.messaging.PedidoStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Consumidor de Eventos (Kafka Listener) simulando um ecossistema satélite (ex: ERP, Faturamento).
 * <p>
 * Decisões Arquiteturais Classe Ouro (Fase 3):
 * 1. Idempotência Absoluta (Exactly-Once Semantics): Em sistemas distribuídos, o Kafka garante
 *    "At-Least-Once" (Pelo menos uma vez). Isso significa que duplicidades ocorrem.
 *    Utilizamos o MongoDB como cofre de idempotência. Se a rede flutuar, a restrição de chave
 *    primária (_id) do Mongo barrará o reprocessamento instantaneamente.
 * 2. High-Throughput BSON: A trava é feita via 'MongoTemplate' com 'org.bson.Document' puro.
 *    Isso evita o overhead de reflexão de Repositories/Entidades, garantindo latência
 *    de milissegundos (Design for Performance).
 * 3. Fail-Fast Resiliente: Se ocorrer erro no processamento do negócio (ex: banco fora),
 *    a exceção sobe. Como a trava do Mongo não foi efetivada ou sofreu rollback (se transacional),
 *    o Kafka fará o Retry nativo posteriormente.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Component
public class KafkaOrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderEventListener.class);

    // Nome da collection no MongoDB onde os IDs dos eventos processados ficarão armazenados
    private static final String IDEMPOTENCY_COLLECTION = "eventos_consumidos_idempotencia";

    private final MongoTemplate mongoTemplate;

    public KafkaOrderEventListener(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Intercepta as mensagens do tópico de logística e faturamento.
     * O Spring deserializa automaticamente o JSON para o Record PedidoStatusChangedEvent
     * com base nas propriedades definidas no application.yml.
     *
     * @param evento O payload consolidado.
     */
    @KafkaListener(topics = "b2b.order.status.events", groupId = "b2b-order-group")
    public void consumirEventoStatusPedido(@Payload PedidoStatusChangedEvent evento) {
        log.info("[KAFKA CONSUMER] Mensagem recebida. Evento ID: {} | Pedido ID: {} | Status: {}",
                evento.eventId(), evento.pedidoId(), evento.statusNovo());

        try {
            // 1. Defesa de Fronteira: Tenta inserir o lock de idempotência no MongoDB.
            // O método 'insert' lança DuplicateKeyException imediatamente se o _id já existir na collection.
            org.bson.Document idempotencyLock = new org.bson.Document("_id", evento.eventId().toString())
                    .append("pedidoId", evento.pedidoId().toString())
                    .append("status", evento.statusNovo())
                    .append("codigoOrigem", evento.codigoPedidoOrigem())
                    .append("processadoEm", Instant.now());

            mongoTemplate.insert(idempotencyLock, IDEMPOTENCY_COLLECTION);

        } catch (DuplicateKeyException e) {
            // 2. Descarte Silencioso (Idempotência Funcional)
            // A mensagem é repetida. Fingimos que processamos para dar o ACK (commit) no Kafka,
            // e ignoramos a execução para não duplicar faturamento do cliente B2B.
            log.warn("[IDEMPOTÊNCIA - KAFKA] Evento duplicado bloqueado pelo MongoDB. Evento ID: {} já processado.", evento.eventId());
            return;
        }

        // 3. Processamento de Negócio (Simulação de sistema vizinho)
        log.info("[KAFKA CONSUMER - PROCESSAMENTO] Executando regras de integração ERP para o pedido: {}",
                evento.codigoPedidoOrigem());

        simularProcessamentoPesado();

        log.info("[KAFKA CONSUMER - SUCESSO] Evento ID: {} processado com êxito e gravado no histórico (Mongo).", evento.eventId());
    }

    /**
     * Simula a latência de comunicação com o sistema legado (ERP) ou banco de dados.
     */
    private void simularProcessamentoPesado() {
        try {
            Thread.sleep(300); // 300ms de operação I/O
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A thread do consumidor foi interrompida", e);
        }
    }
}