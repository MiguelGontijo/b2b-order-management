package com.bayer.b2b.order_management.infrastructure.messaging.dlq;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade Documental do MongoDB para atuar como Dead Letter Queue (DLQ).
 * <p>
 * Em uma Arquitetura Orientada a Eventos (EDA) resiliente, se o Kafka ficar indisponível,
 * o payload do evento não pode ser perdido em memória. Esta classe garante o armazenamento
 * seguro do evento falho, do motivo exato do erro e gerencia o estado para um futuro
 * reprocessamento (Retry/CronJob), isolando a falha sem impactar o banco relacional (PostgreSQL).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Document(collection = "order_events_dlq")
public class PedidoEventDlqDocument {

    @Id
    private String id;

    @Field("pedido_id")
    private UUID pedidoId;

    /*
     * Armazenamos o payload como String (JSON) para garantir que, mesmo se a classe
     * Java do evento (PedidoStatusChangedEvent) mudar no futuro, os eventos antigos
     * falhos ainda possam ser lidos e analisados pelo time de SRE.
     */
    @Field("evento_payload_json")
    private String eventoPayloadJson;

    @Field("erro_detalhado")
    private String erroDetalhado;

    @Field("data_falha")
    private Instant dataFalha;

    @Field("reprocessado")
    private boolean reprocessado;

    @Field("data_reprocessamento")
    private Instant dataReprocessamento;

    /**
     * Construtor exigido pelo Spring Data MongoDB.
     */
    protected PedidoEventDlqDocument() {
    }

    /**
     * Construtor principal (Fail-Fast) para registrar uma nova falha crítica de mensageria.
     *
     * @param pedidoId O identificador único do pedido.
     * @param eventoPayloadJson O JSON exato que tentou ser enviado ao Kafka.
     * @param erroDetalhado A stacktrace ou mensagem de exceção capturada.
     */
    public PedidoEventDlqDocument(UUID pedidoId, String eventoPayloadJson, String erroDetalhado) {
        this.pedidoId = Objects.requireNonNull(pedidoId, "O ID do pedido é obrigatório para rastreabilidade");
        this.eventoPayloadJson = Objects.requireNonNull(eventoPayloadJson, "O payload do evento não pode ser nulo");
        this.erroDetalhado = Objects.requireNonNull(erroDetalhado, "O erro detalhado deve ser informado");

        this.dataFalha = Instant.now();
        this.reprocessado = false;
        this.dataReprocessamento = null;
    }

    // =========================================================================================
    // Comportamento de Negócio da Infraestrutura
    // =========================================================================================

    /**
     * Altera o estado do documento quando um Worker/CronJob conseguir reenviar
     * esta mensagem com sucesso para o Kafka posteriormente.
     */
    public void marcarComoReprocessado() {
        if (this.reprocessado) {
            throw new IllegalStateException("Este evento já foi reprocessado e enviado com sucesso anteriormente.");
        }
        this.reprocessado = true;
        this.dataReprocessamento = Instant.now();
    }

    // =========================================================================================
    // Getters Puros (Sem Setters públicos para proteger a auditoria do erro)
    // =========================================================================================

    public String getId() {
        return id;
    }

    public UUID getPedidoId() {
        return pedidoId;
    }

    public String getEventoPayloadJson() {
        return eventoPayloadJson;
    }

    public String getErroDetalhado() {
        return erroDetalhado;
    }

    public Instant getDataFalha() {
        return dataFalha;
    }

    public boolean isReprocessado() {
        return reprocessado;
    }

    public Instant getDataReprocessamento() {
        return dataReprocessamento;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PedidoEventDlqDocument that = (PedidoEventDlqDocument) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}