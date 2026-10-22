package com.bayer.b2b.order_management.infrastructure.database;

import com.bayer.b2b.order_management.application.usecase.PedidoFiltro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Fábrica de Specifications (Criteria API) para construção dinâmica de consultas de Pedidos.
 * <p>
 * Decisão Arquitetural:
 * Evita a explosão combinatória de métodos no Repositório (ex: findByStatus, findByStatusAndData, etc.).
 * O motor Criteria do Jakarta EE monta a cláusula "WHERE" dinamicamente apenas com os parâmetros
 * que o cliente enviou na requisição, garantindo performance e blindagem total contra SQL Injection.
 * Omitimos intencionalmente qualquer JOIN FETCH nesta specification para permitir que a paginação
 * ocorra nativamente no banco de dados, poupando a RAM do servidor (Prevenção de OOM).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public class PedidoSpecification {

    /**
     * Construtor privado para ocultar o construtor público implícito.
     * Classes utilitárias/fábricas estáticas não devem ser instanciadas.
     */
    private PedidoSpecification() {
        throw new UnsupportedOperationException("Esta é uma classe utilitária e não pode ser instanciada");
    }

    /**
     * Monta a especificação de busca baseada nos filtros opcionais fornecidos.
     *
     * @param filtro O objeto contendo os parâmetros de query vindos do adaptador web.
     * @return Specification configurada para ser executada pelo JpaSpecificationExecutor.
     */
    public static Specification<Pedido> comFiltros(PedidoFiltro filtro) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filtro == null) {
                return cb.conjunction(); // Retorna 1=1 (nenhum filtro aplicado)
            }

            // Cláusula: WHERE status = ?
            if (filtro.status() != null) {
                predicates.add(cb.equal(root.get("status"), filtro.status()));
            }

            // Cláusula: WHERE data_criacao >= ?
            if (filtro.dataInicio() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dataCriacao"), filtro.dataInicio()));
            }

            // Cláusula: WHERE data_criacao <= ?
            if (filtro.dataFim() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dataCriacao"), filtro.dataFim()));
            }

            // Concatena todos os predicados com operador lógico AND
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}