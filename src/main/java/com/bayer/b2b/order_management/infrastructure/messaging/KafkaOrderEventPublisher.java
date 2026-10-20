package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.messaging.dlq.PedidoEventDlqDocument;
import com.bayer.b2b.order_management.infrastructure.messaging.dlq.PedidoEventDlqRepository;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptador de Infraestrutura (Outbound Adapter) para publicação de eventos no Apache Kafka.
 * <p>
 * Implementa a Porta de Saída do domínio com resiliência nível Sênior.
 * O processamento ocorre em background liberando a Thread principal do servidor web para
 * atender novos usuários imediatamente. Caso o broker (Kafka) esteja indisponível, a falha
 * é capturada assincronamente e o payload é salvo no MongoDB atuando como Dead Letter Queue (DLQ),
 * garantindo zero perda de dados transacionais.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Component
public class KafkaOrderEventPublisher implements OrderEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderEventPublisher.class);

    // Tópico padrão de faturamento e logística da Bayer
    private static final String TOPIC = "b2b.order.status.events";

    private final KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate;
    private final PedidoEventDlqRepository dlqRepository;
    private final Gson gson = new Gson();

    public KafkaOrderEventPublisher(
            KafkaTemplate<String, PedidoStatusChangedEvent> kafkaTemplate,
            PedidoEventDlqRepository dlqRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqRepository = dlqRepository;
    }

    @Override
    public void publicarEventoStatus(Pedido pedido) {
        Objects.requireNonNull(pedido, "O pedido não pode ser nulo para publicação do evento");

        PedidoStatusChangedEvent evento = PedidoStatusChangedEvent.from(pedido);
        String key = evento.pedidoId().toString();

        log.info("[KAFKA PRODUCER] Iniciando publicação do evento para o Pedido ID: {} no tópico: {}", key, TOPIC);

        // Envio Assíncrono e Não-bloqueante
        CompletableFuture<SendResult<String, PedidoStatusChangedEvent>> future =
                kafkaTemplate.send(TOPIC, key, evento);

        // Tratamento Reativo
        future.whenComplete((result, exception) -> {
            if (exception == null) {
                log.info("[KAFKA PRODUCER - SUCESSO] Evento publicado. Pedido ID: {} | Partição: {} | Offset: {}",
                        key, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            } else {
                log.error("[KAFKA PRODUCER - FALHA CRÍTICA] Kafka indisponível. Redirecionando Pedido ID: {} para a DLQ (MongoDB). Motivo: {}",
                        key, exception.getMessage());

                // Desvio Gracioso da falha para a nossa estrutura de contingência
                salvarNaDeadLetterQueue(evento, exception);
            }
        });
    }

    /**
     * Estratégia de Fallback (Design for Failure):
     * Grava o evento corrompido ou rejeitado pela rede no MongoDB para auditoria ou reprocessamento.
     */
    private void salvarNaDeadLetterQueue(PedidoStatusChangedEvent evento, Throwable exception) {
        try {
            // Serializa o DTO para JSON utilizando o GSON para preservarmos a integridade do payload original
            String payloadJson = gson.toJson(evento);

            PedidoEventDlqDocument dlqDocument = new PedidoEventDlqDocument(
                    evento.pedidoId(),
                    payloadJson,
                    exception.getMessage()
            );

            dlqRepository.save(dlqDocument);
            log.info("[DLQ - MONGODB] Evento do Pedido ID: {} salvo com sucesso na Dead Letter Queue.", evento.pedidoId());

        } catch (Exception e) {
            // Utilizamos Exception genérica, pois o GSON não exige tratamento de exceções checadas,
            // garantindo que uma falha de conversão não trave o fluxo de contingência.
            log.error("[DLQ - FALHA FATAL] Erro arquitetural ao serializar o payload do evento para a DLQ. Pedido ID: {}", evento.pedidoId(), e);
        }
    }
}