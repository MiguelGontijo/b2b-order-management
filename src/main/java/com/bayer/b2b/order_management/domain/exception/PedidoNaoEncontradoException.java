package com.bayer.b2b.order_management.domain.exception;

/**
 * Exceção de Domínio para representar a ausência de um Pedido na base de dados.
 * <p>
 * Decisão Arquitetural: Pertence exclusivamente à camada de Domínio, isolada de frameworks web.
 * Substitui o uso genérico de "ResourceNotFoundException" para fornecer uma semântica
 * explícita (Linguagem Ubíqua) focada na Raiz de Agregação. Garante que o Domínio
 * expresse exatamente qual entidade de negócio falhou na busca.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public class PedidoNaoEncontradoException extends RuntimeException {

    public PedidoNaoEncontradoException(String message) {
        super(message);
    }
}