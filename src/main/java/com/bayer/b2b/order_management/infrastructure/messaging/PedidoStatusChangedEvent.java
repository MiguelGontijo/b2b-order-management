package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO (Data Transfer Object) de Evento.
 * <p>
 * Representa o payload exato que será serializado em JSON e trafegado no tópico Kafka.
 * O uso de Records garante que o evento, uma vez consolidado na memória, não sofra
 * mutação durante o processamento assíncrono.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record PedidoStatusChangedEvent(
        UUID pedidoId,
        String cnpjParceiro,
        String novoStatus,
        BigDecimal valorTotal,
        Instant dataTransitiva
) {
    /**
     * Factory method para traduzir a Entidade de Domínio para o Evento de Infraestrutura.
     */
    public static PedidoStatusChangedEvent from(Pedido pedido) {
        return new PedidoStatusChangedEvent(
                pedido.getId(),
                pedido.getParceiro().getCnpj(),
                pedido.getStatus().name(),
                pedido.getValorTotal(),
                Instant.now() // O instante exato em que a mensagem foi forjada
        );
    }
}