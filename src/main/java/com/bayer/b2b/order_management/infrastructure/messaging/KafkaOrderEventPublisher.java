package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.messaging.outbox.OutboxEvento;
import com.bayer.b2b.order_management.infrastructure.messaging.outbox.OutboxEventoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Adaptador de Infraestrutura (Outbound Adapter) para publicação de eventos.
 * <p>
 * Decisões Arquiteturais Classe Ouro (Evolução Fase 3):
 * 1. Fim do Dual-Write: Esta classe não envia mais o evento diretamente para a rede (Kafka).
 *    Ela agora atua estritamente persistindo a intenção de envio na tabela de Outbox.
 * 2. Transação Obrigatória (MANDATORY): A anotação garante que este método falhará
 *    imediatamente se for chamado fora do contexto transacional do banco de dados,
 *    garantindo a atomicidade ACID inegociável entre Pedido e Evento.
 * 3. Serialização Robusta: Transição para o ObjectMapper (Jackson) padrão do ecossistema
 *    Spring, preparado para lidar com serialização de datas (Instant) e UUIDs.
 * </p>
 *
 * @author Miguel
 * @version 2.0
 */
@Component
public class KafkaOrderEventPublisher implements OrderEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderEventPublisher.class);

    private final OutboxEventoRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public KafkaOrderEventPublisher(OutboxEventoRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
        // Instancia o Jackson localmente garantindo o suporte nativo a datas (Instant) e isolamento arquitetural
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }

    /**
     * Empacota e persiste o evento na mesma transação de banco do domínio.
     *
     * @param pedido O pedido que sofreu a mutação.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarEventoStatus(Pedido pedido) {
        Objects.requireNonNull(pedido, "O pedido não pode ser nulo para publicação do evento");

        // 1. Gera o Payload Enriquecido da Fase 3
        PedidoStatusChangedEvent evento = PedidoStatusChangedEvent.from(pedido);

        try {
            // 2. Serializa o Evento utilizando a engrenagem nativa do Spring (Jackson)
            String payloadJson = objectMapper.writeValueAsString(evento);

            // 3. Instancia a Entidade do Outbox Pattern
            OutboxEvento outboxEvento = new OutboxEvento(
                    pedido.getId().toString(),
                    "Pedido",
                    evento.eventType(),
                    payloadJson
            );

            // 4. Salva no banco de dados relacional.
            // Se o Kafka estiver fora do ar agora, não importa. O dado está seguro no Postgres.
            outboxRepository.save(outboxEvento);

            log.info("[OUTBOX - GARANTIA ACID] Evento ID: {} (Tipo: {}) salvo no Outbox com sucesso. Aguardando o Relay.",
                    evento.eventId(), evento.eventType());

        } catch (JsonProcessingException e) {
            // Se falhar na montagem do JSON, a exceção sobe e aborta a transação inteira do banco,
            // impedindo que o Pedido seja salvo sem o seu respectivo evento de notificação.
            log.error("[OUTBOX - FALHA FATAL] Erro ao serializar o payload do evento para o pedido ID: {}", pedido.getId(), e);
            throw new IllegalStateException("Falha arquitetural ao serializar o evento de domínio para o Outbox", e);
        }
    }
}