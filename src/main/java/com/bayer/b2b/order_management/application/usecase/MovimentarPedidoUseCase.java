package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.execption.ResourceNotFoundException;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de Uso (Interactor) responsável por orquestrar a progressão logística de um Pedido.
 * <p>
 * Decisões Arquiteturais Sênior:
 * 1. Alta Coesão (DRY): Agrupa transições puramente logísticas (sem impacto financeiro)
 *    em um único serviço, reaproveitando a mecânica de persistência e disparo de eventos.
 * 2. Performance (Sem Locks): Como não há manipulação de limites de crédito concorrentes
 *    nesta fase, dispensamos o Pessimistic Lock. A integridade estrutural é garantida
 *    exclusivamente pela Máquina de Estados blindada dentro da entidade.
 * 3. Intenção Explícita (DDD): Expõe métodos que refletem a linguagem ubíqua do negócio,
 *    eliminando passagens genéricas de "status".
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class MovimentarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final OrderEventPublisherPort eventPublisher;

    public MovimentarPedidoUseCase(
            PedidoRepository pedidoRepository,
            OrderEventPublisherPort eventPublisher) {
        this.pedidoRepository = pedidoRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Transita o pedido para a fase de separação no centro de distribuição.
     */
    @Transactional
    public Pedido processar(UUID pedidoId) {
        Pedido pedido = extrairPedido(pedidoId);
        pedido.processar();
        return concluirMovimentacao(pedido);
    }

    /**
     * Registra o despacho do pedido para a transportadora.
     */
    @Transactional
    public Pedido enviar(UUID pedidoId) {
        Pedido pedido = extrairPedido(pedidoId);
        pedido.enviar();
        return concluirMovimentacao(pedido);
    }

    /**
     * Confirma a entrega física ao cliente B2B.
     */
    @Transactional
    public Pedido entregar(UUID pedidoId) {
        Pedido pedido = extrairPedido(pedidoId);
        pedido.entregar();
        return concluirMovimentacao(pedido);
    }

    // =========================================================================================
    // Métodos utilitários privados para reuso estrutural
    // =========================================================================================

    private Pedido extrairPedido(UUID pedidoId) {
        Objects.requireNonNull(pedidoId, "O ID do pedido não pode ser nulo");
        return pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não localizado para o ID: " + pedidoId));
    }

    private Pedido concluirMovimentacao(Pedido pedido) {
        // Salva a mutação de estado gerada pela entidade
        Pedido pedidoSalvo = pedidoRepository.save(pedido);

        // Dispara assincronamente a notificação (Kafka/RabbitMQ) para os sistemas interessados
        eventPublisher.publicarEventoStatus(pedidoSalvo);

        return pedidoSalvo;
    }
}