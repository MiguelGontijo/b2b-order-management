package com.bayer.b2b.order_management.domain.execption;

/**
 * Exceção de Domínio Genérica para ausência de recursos.
 * <p>
 * Decisão Arquitetural: Evita a criação de dezenas de exceções específicas
 * (PedidoNotFound, ParceiroNotFound). Centraliza o tratamento de 404.
 * </p>
 *
 * @author Miguel
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}