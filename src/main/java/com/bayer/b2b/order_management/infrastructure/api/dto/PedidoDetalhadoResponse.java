package com.bayer.b2b.order_management.infrastructure.api.dto;

import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DTO de Saída (Outbound API Contract) para a consulta detalhada de um Pedido.
 * <p>
 * Decisão Arquitetural: Separar este DTO do Controller original evita acoplamento visual e
 * protege o roteamento HTTP. Utilizamos Records para garantir imutabilidade absoluta e o
 * padrão Factory Method (from) para centralizar a tradução da Entidade Rica para o payload Web.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record PedidoDetalhadoResponse(
        UUID id,
        String cnpjParceiro,
        String status,
        BigDecimal valorTotal,
        Instant dataCriacao,
        Set<ItemPedidoResponse> itens
) {

    /**
     * Factory Method para traduzir o Aggregate Root em um payload JSON detalhado,
     * impedindo vazamento de métodos ou lógicas de domínio para a interface do cliente.
     */
    public static PedidoDetalhadoResponse from(Pedido pedido) {
        Set<ItemPedidoResponse> itensTraduzidos = pedido.getItens().stream()
                .map(ItemPedidoResponse::from)
                .collect(Collectors.toSet());

        return new PedidoDetalhadoResponse(
                pedido.getId(),
                pedido.getParceiro().getCnpj(),
                pedido.getStatus().name(),
                pedido.getValorTotal(),
                pedido.getDataCriacao(), // Resolve o gap da data dinâmica apontado na análise de arquitetura
                itensTraduzidos
        );
    }

    /**
     * Nested Record representando a projeção de cada item na nota fiscal do pedido.
     * <p>
     * Mantê-lo aninhado aqui eleva a coesão, pois este DTO de item só faz sentido
     * existindo dentro do contexto de uma resposta detalhada de pedido.
     * </p>
     */
    public record ItemPedidoResponse(
            String produtoId,
            Integer quantidade,
            BigDecimal precoUnitario
    ) {
        public static ItemPedidoResponse from(ItemPedido item) {
            return new ItemPedidoResponse(
                    item.getProdutoId(),
                    item.getQuantidade(),
                    item.getPrecoUnitario()
            );
        }
    }
}