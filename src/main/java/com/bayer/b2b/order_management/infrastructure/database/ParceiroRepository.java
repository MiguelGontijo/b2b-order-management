package com.bayer.b2b.order_management.infrastructure.database;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Adaptador de Infraestrutura responsável pela persistência do Parceiro B2B.
 * <p>
 * O foco desta interface é garantir a consistência ACID em operações financeiras concorrentes.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Repository
public interface ParceiroRepository extends JpaRepository<Parceiro, Long> {

    /**
     * Busca um parceiro pelo seu ID aplicando um Bloqueio Pessimista de Escrita (PESSIMISTIC_WRITE).
     * <p>
     * <b>Decisão Arquitetural (Engenharia de Alta Escala):</b>
     * Em fluxos monetários bloqueantes, esta anotação instrui o Hibernate a gerar um
     * bloqueio físico na linha do PostgreSQL (SELECT ... FOR UPDATE). Isso garante que,
     * se dois pedidos entrarem simultaneamente no mesmo milissegundo, a segunda requisição
     * aguardará o fim da primeira transação, impedindo Leituras Fantasmas (Phantom Reads)
     * e o esgotamento indevido do limite de crédito.
     * </p>
     *
     * @param id O identificador do Parceiro.
     * @return Um Optional contendo o parceiro, com a linha do banco bloqueada para escrita.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Parceiro p WHERE p.id = :id")
    Optional<Parceiro> findByIdWithPessimisticLock(@Param("id") Long id);
}