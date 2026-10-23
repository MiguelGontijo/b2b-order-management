package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.hibernate.internal.util.collections.ArrayHelper.forEach;

/**
 * Caso de Uso (Interactor) responsável por orquestrar a recepção e criação de um Pedido B2B.
 * <p>
 * Atua como uma fronteira transacional estrita focada apenas no registro
 * da intenção de compra. A separação de responsabilidades garante que o pedido
 * nasça com status PENDENTE e sem travas de banco (Locks), delegando a dedução
 * financeira de limite para o fluxo de aprovação específico.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class ProcessarPedidoUseCase {

    private final ParceiroRepository parceiroRepository;
    private final PedidoRepository pedidoRepository;
    private final OrderEventPublisherPort eventPublisher;

    public ProcessarPedidoUseCase(
            ParceiroRepository parceiroRepository,
            PedidoRepository pedidoRepository,
            OrderEventPublisherPort eventPublisher) {
        this.parceiroRepository = parceiroRepository;
        this.pedidoRepository = pedidoRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Executa o fluxo de recepção e criação de um novo pedido.
     * <p>
     * <b>Decisão Arquitetural:</b>
     * A anotação @Transactional garante a atomicidade da gravação do pedido e seus itens.
     * Removemos a trava pessimista (Pessimistic Lock) e o débito financeiro desta fase
     * para permitir altíssima vazão (Throughput) de recepção de requisições. O pedido
     * nasce imutável em seu estado inicial (PENDENTE).
     * </p>
     *
     * @param command Objeto de transferência (DTO interno) contendo a intenção de compra.
     * @return O Pedido criado, salvo e com status PENDENTE.
     */
    @Transactional
    public Pedido execute(ProcessarPedidoCommand command) {
        Objects.requireNonNull(command, "O comando de processamento não pode ser nulo");

        // 1. Busca o Parceiro sem travas físicas de escrita, apenas para atrelar a FK do novo registro
        Parceiro parceiro = parceiroRepository.findById(command.parceiroId())
                .orElseThrow(() -> new IllegalArgumentException("Parceiro não encontrado para o ID: " + command.parceiroId()));

        // 2. Instancia a Raiz de Agregação (O Pedido nasce como PENDENTE e com valor ZERO via construtor)
        Pedido pedido = new Pedido(parceiro, command.codigoPedidoOrigem());

        // 3. Constrói e aninha os Itens ao Pedido (O total é incrementado nativamente na memória)
        command.itens().stream().map(
                it -> new ItemPedido(
                        pedido,
                        it.produtoId(),
                        it.quantidade(),
                        it.precoUnitario()
                )
        ).forEach(pedido::adicionarItem);

        // 4. Persiste o Pedido (Os itens são salvos na mesma transação via CascadeType.ALL)
        Pedido pedidoSalvo = pedidoRepository.save(pedido);

        // 5. Arquitetura Orientada a Eventos: Dispara a notificação de criação do pedido de forma assíncrona
        eventPublisher.publicarEventoStatus(pedidoSalvo);

        return pedidoSalvo;
    }

    // =========================================================================================
    // DTOs de Entrada (Commands) usando Records para garantir Imutabilidade e Alta Coesão
    // =========================================================================================

    /**
     * DTO interno (Command) que carrega a intenção de negócio para dentro do UseCase.
     * <p>
     * Alteração Arquitetural: Inclusão do 'codigoPedidoOrigem' para garantir a idempotência
     * nativa (Business Key Constraint) contra duplicidades de rede.
     * Utiliza List em vez de Set para a coleção de itens,
     * permitindo que pedidos corporativos com linhas idênticas (mesmo produto e preço)
     * não sejam aglutinados indevidamente pela JVM.
     * </p>
     */
    public record ProcessarPedidoCommand(
            Long parceiroId,
            String codigoPedidoOrigem,
            List<ItemCommand> itens
    ) {
        public ProcessarPedidoCommand {
            java.util.Objects.requireNonNull(parceiroId, "O ID do parceiro é obrigatório");
            java.util.Objects.requireNonNull(codigoPedidoOrigem, "O código de origem do pedido é obrigatório");
            if (codigoPedidoOrigem.isBlank()) {
                throw new IllegalArgumentException("O código de origem do pedido não pode estar em branco");
            }
            if (itens == null || itens.isEmpty()) {
                throw new IllegalArgumentException("O pedido deve conter pelo menos um item");
            }
        }
    }

    public record ItemCommand(
            String produtoId,
            Integer quantidade,
            BigDecimal precoUnitario
    ) {
        public ItemCommand {
            Objects.requireNonNull(produtoId, "O ID do produto é obrigatório");
            Objects.requireNonNull(quantidade, "A quantidade é obrigatória");
            Objects.requireNonNull(precoUnitario, "O preço unitário é obrigatório");
        }
    }
}