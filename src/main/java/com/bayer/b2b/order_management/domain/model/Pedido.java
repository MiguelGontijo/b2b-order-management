package com.bayer.b2b.order_management.domain.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Representa o Pedido B2B, atuando como a Raiz de Agregação (Aggregate Root) do domínio.
 * <p>
 * O ciclo de vida desta entidade é estritamente controlado. As mutações de status
 * passam obrigatoriamente pela máquina de estados ({@link StatusPedido}), e o ID
 * é gerado na memória (UUID) para garantir integridade de identidade antes mesmo
 * da persistência no banco de dados relacional.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Entity
@Table(name = "pedidos")
public class Pedido {

    /*
     * Utilizamos UUID (Universally Unique Identifier) em vez de Long/Sequence.
     * Em arquiteturas distribuídas (Microsserviços/Mensageria), o UUID evita colisões
     * de IDs entre diferentes bancos e facilita a rastreabilidade (Correlation ID).
     */
    @Id
    @Column(updatable = false, nullable = false, length = 36)
    private UUID id;

    /*
     * Mapeamento LAZY (Preguiçoso) é obrigatório em nível Sênior para evitar o problema N+1.
     * O parceiro só será carregado do banco se o método getParceiro() for explicitamente chamado.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parceiro_id", nullable = false, updatable = false)
    private Parceiro parceiro;

    @Column(name = "valor_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal valorTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatusPedido status;

    @Column(name = "data_criacao", nullable = false, updatable = false)
    private Instant dataCriacao;

    @Column(name = "data_ultima_atualizacao", nullable = false)
    private Instant dataUltimaAtualizacao;

    /*
     * Mapeamento bidirecional para a Entidade Local (ItemPedido).
     * CascadeType.ALL: Ao salvar o Pedido, o Hibernate salva os itens automaticamente.
     * orphanRemoval = true: Se um item for removido da coleção em memória, será deletado no banco.
     */
    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ItemPedido> itens = new HashSet<>();

    /**
     * Construtor protegido exigido pelo framework JPA/Hibernate.
     */
    protected Pedido() {
    }

    /**
     * Construtor principal (Fail-Fast).
     * Garante que um pedido nunca nasça inválido ou sem um cliente atrelado.
     *
     * @param parceiro O cliente B2B detentor do pedido.
     */
    public Pedido(Parceiro parceiro) {
        this.id = UUID.randomUUID(); // Identidade garantida em memória
        this.parceiro = Objects.requireNonNull(parceiro, "O parceiro do pedido não pode ser nulo");
        this.status = StatusPedido.PENDENTE;
        this.valorTotal = BigDecimal.ZERO;
    }

    // =========================================================================================
    // Callbacks de Auditoria (JPA)
    // O próprio Hibernate chamará estes métodos automaticamente antes de executar os comandos SQL,
    // garantindo que as datas estejam sempre precisas, sem precisarmos poluir os Services.
    // =========================================================================================

    @PrePersist
    protected void onCreate() {
        this.dataCriacao = Instant.now();
        this.dataUltimaAtualizacao = this.dataCriacao;
    }

    @PreUpdate
    protected void onUpdate() {
        this.dataUltimaAtualizacao = Instant.now();
    }

    // =========================================================================================
    // Comportamentos de Negócio (Rich Domain)
    // =========================================================================================

    /**
     * Valida internamente se o pedido pode assumir um novo status utilizando a máquina de estados.
     * <p>
     * Decisão Arquitetural: A visibilidade privada protege a Raiz de Agregação contra mutações anêmicas.
     * O sistema deve utilizar exclusivamente os métodos explícitos de intenção (ex: aprovar(), cancelar())
     * para interagir com o ciclo de vida logístico e financeiro do pedido.
     * </p>
     *
     * @param novoStatus O status desejado.
     * @throws IllegalStateException se a regra de transição for violada.
     */
    private void transitarStatus(StatusPedido novoStatus) {
        Objects.requireNonNull(novoStatus, "O novo status não pode ser nulo");

        if (!this.status.podeTransitarPara(novoStatus)) {
            throw new IllegalStateException(
                    String.format("Violação da Máquina de Estados: O pedido não pode transitar de %s para %s",
                            this.status.name(), novoStatus.name())
            );
        }

        this.status = novoStatus;
    }

    /**
     * Registra a aprovação financeira do pedido, liberando-o para a esteira logística.
     */
    public void aprovar() {
        this.transitarStatus(StatusPedido.APROVADO);
    }

    /**
     * Indica que o pedido entrou na fase de separação e embalagem no centro de distribuição.
     */
    public void processar() {
        this.transitarStatus(StatusPedido.EM_PROCESSAMENTO);
    }

    /**
     * Registra o despacho do pedido para a transportadora.
     */
    public void enviar() {
        this.transitarStatus(StatusPedido.ENVIADO);
    }

    /**
     * Confirma a entrega física ao parceiro B2B, finalizando o ciclo de sucesso.
     */
    public void entregar() {
        this.transitarStatus(StatusPedido.ENTREGUE);
    }

    /**
     * Marca o pedido como cancelado, bloqueando mutações operacionais futuras.
     */
    public void cancelar() {
        this.transitarStatus(StatusPedido.CANCELADO);
    }

    /**
     * Adiciona valor ao montante total do pedido.
     * (Será utilizado internamente quando adicionarmos os Itens do Pedido).
     */
    public void incrementarValorTotal(BigDecimal valor) {
        Objects.requireNonNull(valor);
        if (valor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O valor a ser incrementado deve ser maior que zero");
        }
        this.valorTotal = this.valorTotal.add(valor);
    }

    /**
     * Adiciona um item ao pedido garantindo a sincronia da agregação e
     * atualizando o valor financeiro total do pedido de forma imediata na memória.
     *
     * @param item O item previamente instanciado e validado.
     */
    public void adicionarItem(ItemPedido item) {
        Objects.requireNonNull(item, "O item a ser adicionado não pode ser nulo");
        this.itens.add(item);
        this.incrementarValorTotal(item.getPrecoTotal());
    }

    /**
     * Retorna uma visualização imutável (Read-Only) da coleção de itens.
     * <p>
     * Esta blindagem impede que desenvolvedores nas camadas superiores manipulem
     * a coleção diretamente (ex: pedido.getItens().clear()), forçando-os a usar
     * os comportamentos de negócio da entidade para qualquer mutação.
     * </p>
     *
     * @return Set imutável contendo os itens do pedido.
     */
    public Set<ItemPedido> getItens() {
        return Collections.unmodifiableSet(itens);
    }

    // =========================================================================================
    // Getters puros
    // =========================================================================================

    public UUID getId() {
        return id;
    }

    public Parceiro getParceiro() {
        return parceiro;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public StatusPedido getStatus() {
        return status;
    }

    public Instant getDataCriacao() {
        return dataCriacao;
    }

    public Instant getDataUltimaAtualizacao() {
        return dataUltimaAtualizacao;
    }

    // =========================================================================================
    // Equals e HashCode baseados estritamente na chave de negócio única (UUID).
    // =========================================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pedido pedido = (Pedido) o;
        return id.equals(pedido.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

}