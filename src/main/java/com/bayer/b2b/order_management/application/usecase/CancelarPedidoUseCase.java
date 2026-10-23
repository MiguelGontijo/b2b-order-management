package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.exception.PedidoNaoEncontradoException;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de Uso (Interactor) responsável pelo cancelamento de um Pedido e gestão de estorno.
 * <p>
 * Decisões Arquiteturais Sênior:
 * 1. Otimização de Lock: O Pessimistic Lock (trava de banco) no Parceiro só é acionado
 *    se o pedido já tiver deduzido limite de crédito anteriormente. Se for um cancelamento
 *    precoce (PENDENTE), poupamos o banco de dados dessa trava.
 * 2. Integridade Financeira: A regra de negócio avalia o estado isolado antes da mutação
 *    para determinar a necessidade de reposição (estorno) do limite.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class CancelarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final ParceiroRepository parceiroRepository;
    private final OrderEventPublisherPort eventPublisher;

    public CancelarPedidoUseCase(
            PedidoRepository pedidoRepository,
            ParceiroRepository parceiroRepository,
            OrderEventPublisherPort eventPublisher) {
        this.pedidoRepository = pedidoRepository;
        this.parceiroRepository = parceiroRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Executa o fluxo de cancelamento de um pedido, providenciando o estorno financeiro se necessário.
     *
     * @param pedidoId O identificador único do pedido a ser cancelado.
     * @return O pedido atualizado com o status CANCELADO.
     * @throws PedidoNaoEncontradoException Se o pedido não existir na base.
     */
    @Transactional
    public Pedido execute(UUID pedidoId) {
        Objects.requireNonNull(pedidoId, "O ID do pedido não pode ser nulo para cancelamento");

        // 1. Recupera o Pedido
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new PedidoNaoEncontradoException("Pedido não localizado para o ID: " + pedidoId));

        // 2. Análise de Estado Prévio: Verifica se o limite do parceiro foi consumido no passado
        StatusPedido statusAtual = pedido.getStatus();
        boolean exigeEstornoFinanceiro = (statusAtual == StatusPedido.APROVADO ||
                statusAtual == StatusPedido.EM_PROCESSAMENTO ||
                statusAtual == StatusPedido.ENVIADO);

        // 3. Regra de Negócio: Transita a máquina de estados para CANCELADO
        pedido.cancelar();

        // 4. Fluxo Financeiro Condicional
        if (exigeEstornoFinanceiro) {
            // Aplica a trava pessimista física apenas se houver necessidade de manipulação financeira
            Parceiro parceiro = parceiroRepository.findByIdWithPessimisticLock(pedido.getParceiro().getId())
                    .orElseThrow(() -> new IllegalStateException("Parceiro atrelado ao pedido não localizado para estorno"));

            // Devolve o limite para a conta do parceiro (O método deve existir na entidade Parceiro)
            parceiro.estornarCredito(pedido.getValorTotal());
        }

        // 5. Persistência (Dirty Checking fará o update, chamamos save para ser explícito)
        Pedido pedidoCancelado = pedidoRepository.save(pedido);

        // 6. Publicação de Evento para outras áreas (Logística reversa, Notificações, etc.)
        eventPublisher.publicarEventoStatus(pedidoCancelado);

        return pedidoCancelado;
    }
}