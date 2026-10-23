package com.bayer.b2b.order_management.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Configuração de Arquitetura e Topologia do Apache Kafka.
 * <p>
 * Decisões Arquiteturais Classe Ouro:
 * 1. Provisionamento Automático (IaC): Garante que a aplicação inicie de forma autossuficiente,
 *    criando seus próprios tópicos no broker sem depender de scripts externos manuais.
 * 2. Escalabilidade Horizontal e Throughput: A definição de 3 partições permite que o
 *    Consumer Group divida a carga perfeitamente entre até 3 instâncias da API (Pods),
 *    evitando o gargalo de thread única na recepção dos eventos.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    public static final String TOPIC_B2B_ORDER_EVENTS = "b2b.order.status.events";

    /**
     * Define o contrato de infraestrutura para o tópico principal de logística e faturamento.
     * O Spring Boot detecta este Bean e garante a criação física no Broker Kafka.
     */
    @Bean
    public NewTopic orderStatusEventsTopic() {
        return TopicBuilder.name(TOPIC_B2B_ORDER_EVENTS)
                .partitions(3)
                .replicas(1) // Garantia de Ouro: Em ambiente de produção real (ex: AWS MSK), este valor deve ser 3.
                .build();
    }
}