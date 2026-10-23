package com.bayer.b2b.order_management.infrastructure.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador de Infraestrutura (Repository) para acesso à tabela de Outbox.
 * <p>
 * Decisões Arquiteturais Classe Ouro:
 * 1. Concorrência Segura em Cluster: O método de busca utiliza a diretiva nativa
 *    do PostgreSQL 'FOR UPDATE SKIP LOCKED'. Isso permite que múltiplas instâncias
 *    da API leiam a tabela simultaneamente sem travar o banco (Deadlocks) e
 *    sem chance de ler e enviar o mesmo evento duplicado.
 * 2. Desempenho (High Throughput): Em vez de bloquear o acesso à tabela, o banco de dados
 *    ignora de forma transparente as linhas que já estão sob processamento de outra Thread.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Repository
public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    /**
     * Busca, ordena e bloqueia fisicamente um lote de eventos pendentes para envio.
     * <p>
     * Decisão Técnica: Utilizamos Query Nativa (SQL puro) pois a diretiva SKIP LOCKED
     * é uma funcionalidade específica de dialetos avançados (como PostgreSQL) e garante
     * o comportamento de fila distribuída na tabela. Prioriza sempre o First-In, First-Out (FIFO).
     * </p>
     *
     * @param limit O tamanho máximo do lote (batch) a ser capturado por ciclo do Worker.
     * @return Uma lista de eventos reservados e bloqueados estritamente para a transação atual.
     */
    @Query(value = """
            SELECT * FROM outbox_evento 
            ORDER BY data_criacao ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvento> buscarLoteParaProcessamento(@Param("limit") int limit);
}