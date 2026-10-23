package com.bayer.b2b.order_management.infrastructure.messaging.outbox;

import com.bayer.b2b.order_management.infrastructure.messaging.PedidoStatusChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Motor assíncrono (Worker) para drenagem da tabela Outbox.
 * <p>
 * Decisões Arquiteturais Classe Ouro:
 * 1. Polling Concorrente (Cluster-Safe): Através da anotação @Transactional, o método
 *    invoca a query com 'FOR UPDATE SKIP LOCKED'. Isso bloqueia os registros lidos para
 *    esta instância, impedindo que outro pod da API leia as mesmas mensagens (Prevenção de Deadlocks).
 * 2. Envio Síncrono Condicional: A mensagem só será deletada do banco de dados relacional
 *    se o Kafka confirmar (ACK) o recebimento dentro do timeout de 3 segundos.
 * 3. Fallback Nativo e At-Least-Once: Se o Kafka cair, a thread lança exceção, o Spring
 *    faz rollback da transação de leitura, libera os locks no banco e os eventos
 *    permanecem intactos para a próxima tentativa.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Component
@EnableScheduling
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    // Tópico padrão de faturamento e logística da Bayer
    private static final String TOPIC = "b2b.order.status.events";

    private final OutboxEventoRepository outboxRepository;
    private final KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelayWorker(
            OutboxEventoRepository outboxRepository,
            KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;

        // Instancia o Jackson isoladamente (Fail-Safe) para garantir o parsing nativo de Instant e UUID
        // sem depender do Bean global do Spring que pode ser alterado por outros desenvolvedores.
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }

    /**
     * Motor de drenagem: Executa a cada 5 segundos buscando eventos pendentes.
     * O batch (lote) foi travado em 50 registros para garantir transações curtas e
     * evitar o esgotamento do pool de conexões (HikariCP).
     */
    @Scheduled(fixedDelayString = "5000")
    @Transactional
    public void processarEventosPendentes() {
        // Busca com FOR UPDATE SKIP LOCKED
        List<OutboxEvento> lote = outboxRepository.buscarLoteParaProcessamento(50);

        if (lote.isEmpty()) {
            return; // Silêncio arquitetural, nada a processar no momento.
        }

        log.info("[OUTBOX RELAY] Iniciando processamento de lote com {} eventos pendentes.", lote.size());

        for (OutboxEvento evento : lote) {
            try {
                // 1. Hidrata o JSON de volta para o objeto de domínio (Record)
                PedidoStatusChangedEvent payload = objectMapper.readValue(evento.getPayload(), PedidoStatusChangedEvent.class);

                // 2. Dispara para a rede com bloqueio máximo de 3 segundos esperando o ACK do Kafka
                kafkaTemplate.send(TOPIC, evento.getAggregateId(), payload).get(3, TimeUnit.SECONDS);

                // 3. Expurga o evento do banco (Confirmação de Sucesso)
                outboxRepository.delete(evento);

                log.debug("[OUTBOX RELAY - SUCESSO] Evento ID: {} publicado e expurgado.", evento.getId());

            } catch (Exception e) {
                /*
                 * 4. Aborta a Transação Inteira.
                 * Se houver falha de rede ou timeout, interrompemos a varredura do lote.
                 * O Spring executa o ROLLBACK, desfazendo as exclusões (deletes) e
                 * destrancando as linhas no PostgreSQL para serem coletadas no próximo ciclo.
                 */
                log.error("[OUTBOX RELAY - FALHA] Erro ao comunicar com o Kafka para o evento: {}. Abortando o lote atual. Motivo: {}",
                        evento.getId(), e.getMessage());

                throw new IllegalStateException("Falha na ponte entre o Banco Relacional e o Broker de Mensageria", e);
            }
        }

        log.info("[OUTBOX RELAY] Lote processado e entregue ao Kafka com sucesso. Eventos despachados: {}", lote.size());
    }
}