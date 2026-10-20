package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

import static org.hibernate.internal.util.collections.ArrayHelper.forEach;

/**
 * Caso de Uso (Interactor) responsável por orquestrar a criação e aprovação financeira de um Pedido B2B.
 * <p>
 * Atua como uma fronteira transacional estrita. O uso da Injeção de Dependência foca
 * exclusivamente na orquestração: as regras matemáticas de crédito e status pertencem
 * às Entidades de Domínio.
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
     * Executa o fluxo de processamento de um novo pedido.
     * <p>
     * <b>Decisão Arquitetural Sênior (Concorrência e ACID):</b>
     * A anotação @Transactional garante que toda essa operação ocorra de forma atômica no PostgreSQL.
     * Ao buscar o Parceiro, o repositório aciona o Pessimistic Lock (SELECT ... FOR UPDATE).
     * Isso significa que se 5 requisições de pedidos chegarem no mesmo milissegundo para o
     * mesmo parceiro, o banco enfileirará as transações, executando uma por vez, garantindo
     * matematicamente que o limite de crédito nunca ficará negativo.
     * </p>
     *
     * @param command Objeto de transferência (DTO interno) contendo a intenção de compra.
     * @return O Pedido processado, salvo e com status atualizado.
     */
    @Transactional
    public Pedido execute(ProcessarPedidoCommand command) {
        Objects.requireNonNull(command, "O comando de processamento não pode ser nulo");

        // 1. Busca o Parceiro com trava física de escrita (Pessimistic Lock)
        Parceiro parceiro = parceiroRepository.findByIdWithPessimisticLock(command.parceiroId())
                .orElseThrow(() -> new IllegalArgumentException("Parceiro não encontrado para o ID: " + command.parceiroId()));

        // 2. Instancia a Raiz de Agregação (O Pedido)
        Pedido pedido = new Pedido(parceiro);

        // 3. Constrói e aninha os Itens ao Pedido (O total é incrementado nativamente na memória)
        command.itens().stream().map(
                it -> new ItemPedido(
                    pedido,
                    it.produtoId(),
                    it.quantidade(),
                    it.precoUnitario()
                )
        ).forEach(pedido::adicionarItem);

        // 4. Executa a Regra Financeira: Tenta debitar o valor total calculado do limite do Parceiro.
        // Se falhar (falta de limite), lança IllegalArgumentException e a transação do banco sofre ROLLBACK automático.
        parceiro.debitarCredito(pedido.getValorTotal());

        // 5. Atualiza o status via Máquina de Estados do Domínio
        pedido.transitarStatus(StatusPedido.APROVADO);

            // 6. Persiste o Pedido (Os itens e o novo saldo do parceiro são salvos na transação)
            Pedido pedidoSalvo = pedidoRepository.save(pedido);

            // 7. Arquitetura Orientada a Eventos: Dispara a notificação de forma assíncrona
            // O caso de uso não sabe se é Kafka ou RabbitMQ, ele apenas confia no contrato.
            eventPublisher.publicarEventoStatus(pedidoSalvo);

            return pedidoSalvo;
    }

    // =========================================================================================
    // DTOs de Entrada (Commands) usando Records para garantir Imutabilidade e Alta Coesão
    // =========================================================================================

    public record ProcessarPedidoCommand(
            Long parceiroId,
            Set<ItemCommand> itens
    ) {
        public ProcessarPedidoCommand {
            Objects.requireNonNull(parceiroId, "O ID do parceiro é obrigatório");
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