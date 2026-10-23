package com.bayer.b2b.order_management.infrastructure.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Infraestrutura que implementa o Transactional Outbox Pattern.
 * <p>
 * Decisões Arquiteturais Classe Ouro:
 * 1. Prevenção de Dual-Write: Esta entidade será salva na mesma transação (ACID) do Pedido.
 *    Se o banco "comitar" o pedido, o evento está garantido. Se der rollback, o evento não é gerado.
 * 2. Desacoplamento Temporal: O Caso de Uso não precisa esperar o Kafka responder.
 *    A API responde rápido para o cliente B2B, e um Worker assíncrono lerá esta tabela depois.
 * 3. Imutabilidade Absoluta: Um evento do passado não pode ser alterado. Não existem setters,
 *    apenas a criação e a exclusão (após o envio bem-sucedido).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Entity
@Table(name = "outbox_evento")
public class OutboxEvento {

    @Id
    @Column(updatable = false, nullable = false, length = 36)
    private UUID id;

    /**
     * ID da Raiz de Agregação (Pedido).
     * Armazenado como String para tornar o Outbox genérico caso novos agregados surjam no futuro.
     */
    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 36)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 100)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    /**
     * O Payload em JSON consolidado.
     * columnDefinition = "TEXT" evita o estouro de limite do VARCHAR(255) nativo do Hibernate.
     */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "data_criacao", nullable = false, updatable = false)
    private Instant dataCriacao;

    /**
     * Construtor protegido exigido pelo framework JPA/Hibernate.
     */
    protected OutboxEvento() {
    }

    /**
     * Construtor principal (Fail-Fast) para injetar um evento na tabela de saída.
     *
     * @param aggregateId ID do Pedido.
     * @param aggregateType O nome da entidade (Ex: "Pedido").
     * @param eventType O tipo do evento (Ex: "PEDIDO_APROVADO").
     * @param payload O JSON serializado contendo os dados do evento.
     */
    public OutboxEvento(String aggregateId, String aggregateType, String eventType, String payload) {
        this.id = UUID.randomUUID(); // Identidade nativa do evento gerada na memória
        this.aggregateId = Objects.requireNonNull(aggregateId, "O ID do agregado não pode ser nulo");
        this.aggregateType = Objects.requireNonNull(aggregateType, "O tipo do agregado não pode ser nulo");
        this.eventType = Objects.requireNonNull(eventType, "O tipo de evento não pode ser nulo");
        this.payload = Objects.requireNonNull(payload, "O payload do evento não pode ser nulo");
    }

    @PrePersist
    protected void onCreate() {
        this.dataCriacao = Instant.now();
    }

    // =========================================================================================
    // Getters Puros
    // =========================================================================================

    public UUID getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getDataCriacao() {
        return dataCriacao;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutboxEvento that = (OutboxEvento) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}