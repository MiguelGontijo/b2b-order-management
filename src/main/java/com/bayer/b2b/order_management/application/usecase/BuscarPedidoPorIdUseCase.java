package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.exception.PedidoNaoEncontradoException;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de Uso (Application Service) para consulta detalhada de Pedido.
 * <p>
 * Decisões Arquiteturais:
 * 1. Performance (Read-Only): Utilizamos a transação no modo leitura para desativar o
 *    rastreamento de mutação de estado (Dirty Checking) do Hibernate, poupando CPU/RAM.
 * 2. Prevenção de N+1: Invocamos explicitamente a query customizada com JOIN FETCH
 *    (findByIdWithItens) para hidratar o Agregado e sua coleção de Itens em uma única
 *    viagem de rede ao PostgreSQL.
 * 3. Fail-Fast: Se o recurso não existir, abortamos imediatamente lançando uma
 *    exceção de domínio específica.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class BuscarPedidoPorIdUseCase {

    private final PedidoRepository pedidoRepository;

    public BuscarPedidoPorIdUseCase(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    /**
     * Orquestra a busca do pedido completo pelo seu identificador único.
     *
     * @param id O UUID do pedido solicitado pelo cliente B2B.
     * @return A Entidade de Domínio rica (Aggregate Root) totalmente hidratada.
     * @throws PedidoNaoEncontradoException Se o ID fornecido não existir na base.
     */
    @Transactional(readOnly = true)
    public Pedido execute(UUID id) {
        Objects.requireNonNull(id, "O identificador do pedido não pode ser nulo para a consulta");

        return pedidoRepository.findByIdWithItens(id)
                .orElseThrow(() -> new PedidoNaoEncontradoException("Pedido não localizado para o ID: " + id));
    }
}