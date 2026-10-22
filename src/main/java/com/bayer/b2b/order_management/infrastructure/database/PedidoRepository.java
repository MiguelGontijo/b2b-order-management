package com.bayer.b2b.order_management.infrastructure.database;

import com.bayer.b2b.order_management.domain.model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para o Agregado de Pedido.
 * <p>
 * Estende JpaSpecificationExecutor para habilitar a execução de consultas dinâmicas
 * (Criteria API) focadas em paginação e filtros opcionais, garantindo performance
 * ao evitar a explosão de métodos de consulta combinados.
 * </p>
 */
public interface PedidoRepository extends JpaRepository<Pedido, UUID>, JpaSpecificationExecutor<Pedido> {

    /**
     * Busca o pedido e força o banco de dados a carregar os itens subordinados
     * em uma única operação de SELECT.
     * <p>
     * <b>Decisão Arquitetural (Otimização Relacional):</b>
     * O mapeamento padrão da coleção é LAZY. Para consultas onde precisamos do
     * detalhamento completo, a cláusula JOIN FETCH mitiga o problema N+1,
     * evitando sobrecarga na rede e múltiplos acessos desnecessários ao banco.
     * </p>
     *
     * @param id O UUID do pedido.
     * @return Um Optional contendo a Raiz de Agregação hidratada com seus Itens.
     */
    @Query("SELECT p FROM Pedido p JOIN FETCH p.itens WHERE p.id = :id")
    Optional<Pedido> findByIdWithItens(@Param("id") UUID id);
}