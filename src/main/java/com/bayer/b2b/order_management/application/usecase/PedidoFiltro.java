package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.StatusPedido;

import java.time.Instant;

/**
 * Objeto de Comando/Filtro para a listagem paginada de Pedidos.
 * <p>
 * Decisão Arquitetural: Encapsular os "Query Parameters" (parâmetros de URL opcionais)
 * em um Record garante imutabilidade e tráfego limpo entre o Inbound Adapter (Controller)
 * e o Application Service (UseCase). Isso impede que a assinatura do UseCase cresça
 * descontroladamente caso novos filtros sejam adicionados no futuro (ex: parceiroId, valorMinimo).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record PedidoFiltro(
        StatusPedido status,
        Instant dataInicio,
        Instant dataFim
) {
}