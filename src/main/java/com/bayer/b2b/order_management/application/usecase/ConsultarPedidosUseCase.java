package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoSpecification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Caso de Uso (Application Service) para consulta paginada e dinâmica de Pedidos.
 * <p>
 * Decisões Arquiteturais:
 * 1. Performance (Read-Only): A anotação transacional em modo leitura desativa o
 *    rastreamento de sujeira (Dirty Checking) da sessão do Hibernate.
 * 2. Segurança de Memória (Fail-Fast): Bloqueia requisições caso a configuração de
 *    paginação seja nula, mitigando o risco de sobrecarga da JVM (OOM).
 * 3. Delegação de Responsabilidade: Centraliza a comunicação entre o Filtro (Aplicação)
 *    e a Specification (Infraestrutura), preservando a Clean Architecture.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class ConsultarPedidosUseCase {

    private final PedidoRepository pedidoRepository;

    public ConsultarPedidosUseCase(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    /**
     * Executa a extração em lote dos pedidos seguindo critérios dinâmicos.
     *
     * @param filtro   O conjunto de parâmetros opcionais preenchidos pelo consumidor web.
     * @param pageable A configuração rigorosa de tamanho de página e ordenação (LIMIT/OFFSET).
     * @return Uma estrutura Page do Spring Data contendo as entidades e os metadados de paginação.
     */
    @Transactional(readOnly = true)
    public Page<Pedido> execute(PedidoFiltro filtro, Pageable pageable) {
        // Blindagem contra varreduras completas sem paginação
        Objects.requireNonNull(pageable, "As diretrizes de paginação (Pageable) são obrigatórias para proteger a memória da aplicação.");

        // O UseCase aciona a fábrica de Specifications injetando as regras de negócio filtradas
        var specification = PedidoSpecification.comFiltros(filtro);

        return pedidoRepository.findAll(specification, pageable);
    }
}