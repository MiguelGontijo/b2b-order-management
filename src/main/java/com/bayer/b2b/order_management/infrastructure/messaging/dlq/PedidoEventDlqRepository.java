package com.bayer.b2b.order_management.infrastructure.messaging.dlq;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador de Infraestrutura (Repository) para a persistência da DLQ no MongoDB.
 * <p>
 * Seguindo a mentalidade SRE (Site Reliability Engineering), este repositório
 * não serve apenas para salvar falhas, mas para fornecer os meios de observabilidade
 * e recuperação automatizada em sistemas distribuídos de missão crítica.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Repository
public interface PedidoEventDlqRepository extends MongoRepository<PedidoEventDlqDocument, String> {

    /**
     * Busca todos os eventos que falharam ao serem enviados para o Kafka e que
     * ainda não foram reprocessados com sucesso, ordenados da falha mais antiga para a mais nova.
     * <p>
     * <b>Decisão Arquitetural (Mecanismo de Retry):</b>
     * Este método é o motor de um padrão "Retry". Em um cenário real de produção,
     * uma tarefa agendada (@Scheduled / CronJob) chamará este método a cada X minutos,
     * pegará as mensagens presas e tentará publicá-las novamente no Kafka,
     * garantindo a Eventual Consistência sem intervenção humana.
     * </p>
     *
     * @return Lista de documentos pendentes de reprocessamento (FIFO).
     */
    List<PedidoEventDlqDocument> findByReprocessadoFalseOrderByDataFalhaAsc();

    /**
     * Busca o histórico de falhas de mensageria para um pedido específico.
     * <p>
     * <b>Decisão Arquitetural (Observabilidade e Tracing):</b>
     * Quando o ecossistema de Logística ou Faturamento reclamar que não recebeu o evento
     * de um pedido, a equipe de sustentação utilizará esta consulta para diagnosticar
     * o payload exato e o erro capturado no momento da falha.
     * </p>
     *
     * @param pedidoId O identificador único da Raiz de Agregação (Pedido).
     * @return Lista de falhas associadas àquele pedido específico.
     */
    List<PedidoEventDlqDocument> findByPedidoId(UUID pedidoId);

}