package com.bayer.b2b.order_management.infrastructure.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO de Entrada (Inbound API Contract) para a requisição de processamento de um Pedido.
 * <p>
 * Decisão Arquitetural Sênior:
 * 1. Isolamento: Extraído do interior do Controller para garantir reaproveitamento e
 *    limpeza visual da fronteira REST.
 * 2. Correção de Bug (Set vs List): A coleção foi alterada estritamente de Set para List.
 *    O uso de Set causava uma falha silenciosa: se o cliente enviasse duas linhas
 *    com o mesmo produto e preço (ex: promoções BOGO - Buy One Get One), a interface Set
 *    faria o merge pelo hashCode, apagando o segundo item e gerando um rombo financeiro.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record ProcessarPedidoRequest(
        Long parceiroId,
        List<ItemPedidoRequest> itens
) {

    /**
     * DTO aninhado para o contrato de entrada de cada item do pedido.
     * <p>
     * Mantido aninhado por altíssima coesão estrutural, visto que a entrada de um
     * item isolado não possui contexto fora da requisição de criação do pedido.
     * </p>
     */
    public record ItemPedidoRequest(
            String produtoId,
            Integer quantidade,
            BigDecimal precoUnitario
    ) {}
}