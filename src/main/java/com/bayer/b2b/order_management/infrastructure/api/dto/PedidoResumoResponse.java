package com.bayer.b2b.order_management.infrastructure.api.dto;

import com.bayer.b2b.order_management.domain.model.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO de Saída (Outbound API Contract) para a listagem paginada de Pedidos.
 * <p>
 * Decisão Arquitetural: Este Record é intencionalmente raso (Flat).
 * Omitimos a coleção de Itens para evitar o acoplamento do JOIN FETCH com a
 * paginação do banco de dados (que causaria o carregamento completo da tabela
 * na memória da JVM).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record PedidoResumoResponse(
        UUID id,
        String cnpjParceiro,
        String status,
        BigDecimal valorTotal,
        Instant dataCriacao
) {

    /**
     * Factory Method que converte a Entidade em um modelo visual enxuto para grid de dados.
     */
    public static PedidoResumoResponse from(Pedido pedido) {
        return new PedidoResumoResponse(
                pedido.getId(),
                pedido.getParceiro().getCnpj(),
                pedido.getStatus().name(),
                pedido.getValorTotal(),
                pedido.getDataCriacao()
        );
    }
}