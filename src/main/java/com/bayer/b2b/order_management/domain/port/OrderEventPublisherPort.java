package com.bayer.b2b.order_management.domain.port;

import com.bayer.b2b.order_management.domain.model.Pedido;

/**
 * Porta de Saída (Outbound Port) para o ecossistema de eventos.
 * <p>
 * Seguindo a Arquitetura Hexagonal e o Princípio de Inversão de Dependência (D do SOLID),
 * a camada de Domínio/UseCase conhece apenas esta interface. A implementação técnica
 * (seja via Kafka, RabbitMQ ou AWS SQS) deve obrigatoriamente satisfazer este contrato,
 * isolando o núcleo do sistema de bibliotecas externas de infraestrutura.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public interface OrderEventPublisherPort {

    /**
     * Publica de forma assíncrona o evento de transição de status do pedido.
     *
     * @param pedido O pedido que sofreu mutação.
     */
    void publicarEventoStatus(Pedido pedido);
}