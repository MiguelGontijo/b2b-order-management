package com.bayer.b2b.order_management.domain.exception;

/**
 * Exceção de Domínio para violação da Máquina de Estados do Pedido.
 * <p>
 * Decisão Arquitetural: Pertence exclusivamente à camada de Domínio, isolada de frameworks.
 * Substitui o uso genérico de IllegalStateException para fornecer uma semântica
 * explícita (Linguagem Ubíqua) sempre que o ciclo de vida logístico ou financeiro
 * de um pedido tentar ser rompido (ex: transitar de ENTREGUE para CANCELADO).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public class TransicaoStatusInvalidaException extends RuntimeException {

    public TransicaoStatusInvalidaException(String message) {
        super(message);
    }
}