package com.bayer.b2b.order_management.domain.exception;

/**
 * Exceção de Domínio para violação da regra de limite de crédito.
 * <p>
 * Decisão Arquitetural: Pertence exclusivamente à camada de Domínio, isolada de frameworks.
 * Substitui o uso genérico de IllegalArgumentException para fornecer semântica
 * explícita (Linguagem Ubíqua) quando um Parceiro não possui saldo suficiente
 * para a aprovação de um Pedido.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public class CreditoInsuficienteException extends RuntimeException {

    public CreditoInsuficienteException(String message) {
        super(message);
    }
}