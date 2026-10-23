package com.bayer.b2b.order_management.infrastructure.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

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
        @NotNull(message = "O ID do parceiro é obrigatório")
        Long parceiroId,

        /*
         * Chave Natural de Idempotência recebida do ERP.
         * Garante proteção transacional contra reenvios e instabilidades de rede.
         */
        @NotBlank(message = "O código de origem do pedido (ERP) é obrigatório e não pode estar em branco")
        String codigoPedidoOrigem,

        @NotEmpty(message = "O pedido deve conter pelo menos um item")
        @Valid
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
            @NotBlank(message = "O ID do produto é obrigatório e não pode estar em branco")
            String produtoId,

            @NotNull(message = "A quantidade é obrigatória")
            @Positive(message = "A quantidade deve ser maior que zero")
            Integer quantidade,

            @NotNull(message = "O preço unitário é obrigatório")
            @Positive(message = "O preço unitário deve ser maior que zero")
            @Digits(integer = 17, fraction = 2, message = "O preço deve ter no máximo 17 dígitos inteiros e 2 decimais")
            BigDecimal precoUnitario
    ) {}
}