package com.bayer.b2b.order_management.domain.exception;

/**
 * Exceção de domínio (Business Rule) lançada quando a raiz de agregação Parceiro
 * não é localizada na base de dados.
 * <p>
 * Decisão Arquitetural: Segrega as falhas de identidade do parceiro B2B das falhas
 * operacionais de pedidos, mantendo o ecossistema fiel à Linguagem Ubíqua (DDD).
 * </p>
 *
 * @author Miguel
 */
public class ParceiroNaoEncontradoException extends RuntimeException {

    public ParceiroNaoEncontradoException(String message) {
        super(message);
    }
}