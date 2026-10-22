package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.execption.ResourceNotFoundException;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Caso de Uso (Interactor) responsável pela aprovação financeira e de negócio de um Pedido.
 * <p>
 * Decisões Arquiteturais Sênior:
 * 1. Separação de Preocupações (SRP): Isolamos a validação de crédito da recepção do pedido.
 * 2. ACID e Concorrência: Utilizamos Pessimistic Lock na entidade Parceiro para garantir que
 *    múltiplas aprovações simultâneas do mesmo cliente sejam enfileiradas pelo banco de dados,
 *    impedindo matematicamente que o saldo financeiro fique inconsistente ou negativo.
 * 3. Domain-Driven Design (DDD): A mutação de status ocorre através do método explícito
 *    de intenção aprovar() da Raiz de Agregação, respeitando a máquina de estados.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class AprovarPedidoUseCase {

    private final PedidoRepository pedidoRepository;
    private final ParceiroRepository parceiroRepository;
    private final OrderEventPublisherPort eventPublisher;

    public AprovarPedidoUseCase(
            PedidoRepository pedidoRepository,
            ParceiroRepository parceiroRepository,
            OrderEventPublisherPort eventPublisher) {
        this.pedidoRepository = pedidoRepository;
        this.parceiroRepository = parceiroRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Executa o fluxo de aprovação financeira de um pedido PENDENTE.
     *
     * @param pedidoId O identificador único do pedido a ser aprovado.
     * @return O pedido atualizado com o status APROVADO.
     * @throws ResourceNotFoundException Se o pedido não existir na base.
     * @throws IllegalStateException Se o pedido não estiver no estado PENDENTE.
     * @throws IllegalArgumentException Se o parceiro não possuir limite de crédito suficiente.
     */
    @Transactional
    public Pedido execute(UUID pedidoId) {
        Objects.requireNonNull(pedidoId, "O ID do pedido não pode ser nulo para aprovação");

        // 1. Recupera a Raiz de Agregação da base de dados
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não localizado para o ID: " + pedidoId));

        // 2. Trava física de escrita (Pessimistic Lock) na conta do Parceiro.
        // Extraímos o ID do parceiro do pedido (Proxy LAZY) e buscamos a entidade travada.
        Parceiro parceiro = parceiroRepository.findByIdWithPessimisticLock(pedido.getParceiro().getId())
                .orElseThrow(() -> new IllegalStateException("Parceiro atrelado ao pedido não localizado na base de dados"));

        // 3. Regra Financeira: Tenta debitar o valor do pedido do limite de crédito do parceiro.
        // Se não houver saldo, o método do domínio lançará exceção e o Spring fará o ROLLBACK da transação.
        parceiro.debitarCredito(pedido.getValorTotal());

        // 4. Regra de Negócio: Transita a máquina de estados (Aciona o método intencional)
        pedido.aprovar();

        // 5. O Hibernate aplica o Dirty Checking automaticamente (atualizando o saldo do parceiro
        // e o status do pedido na transação), mas acionamos o save() para retorno explícito.
        Pedido pedidoAprovado = pedidoRepository.save(pedido);

        // 6. Arquitetura Orientada a Eventos: Publica a mudança de estado para a mensageria
        eventPublisher.publicarEventoStatus(pedidoAprovado);

        return pedidoAprovado;
    }
}