package com.bayer.b2b.order_management.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa um Item contido dentro de um Pedido B2B.
 * <p>
 * Atua como uma Entidade Local subordinada à Raiz de Agregação ({@link Pedido}).
 * O seu ciclo de vida é estritamente amarrado ao pedido pai. Os cálculos financeiros
 * (quantidade * preço unitário) são encapsulados no momento da criação, garantindo
 * a imutabilidade dos valores transacionais e prevenindo fraudes de manipulação na memória.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Entity
@Table(name = "itens_pedido")
public class ItemPedido {

    @Id
    @Column(updatable = false, nullable = false, length = 36)
    private UUID id;

    /*
     * Relacionamento forte (ManyToOne) com a Raiz de Agregação.
     * Mapeamento LAZY para evitar o carregamento do Pedido inteiro quando
     * consultamos apenas o item diretamente na base.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id", nullable = false, updatable = false)
    private Pedido pedido;

    @Column(name = "produto_id", nullable = false, length = 100)
    private String produtoId;

    @Column(nullable = false)
    private Integer quantidade;

    @Column(name = "preco_unitario", nullable = false, precision = 19, scale = 2)
    private BigDecimal precoUnitario;

    // Campo calculado internamente e persistido para otimizar relatórios e evitar recalculos.
    @Column(name = "preco_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal precoTotal;

    /**
     * Construtor protegido exigido pelo framework JPA/Hibernate.
     */
    protected ItemPedido() {
    }

    /**
     * Construtor principal (Fail-Fast).
     * Garante que o item já nasça com seu valor total calculado matematicamente.
     *
     * @param pedido O pedido pai ao qual este item pertence.
     * @param produtoId O identificador do produto no catálogo externo.
     * @param quantidade A quantidade comercializada (deve ser maior que zero).
     * @param precoUnitario O valor unitário no momento da venda.
     */
    public ItemPedido(Pedido pedido, String produtoId, Integer quantidade, BigDecimal precoUnitario) {
        this.id = UUID.randomUUID();
        this.pedido = Objects.requireNonNull(pedido, "O pedido pai não pode ser nulo");
        this.produtoId = Objects.requireNonNull(produtoId, "O ID do produto não pode ser nulo");
        this.quantidade = Objects.requireNonNull(quantidade, "A quantidade não pode ser nula");
        this.precoUnitario = Objects.requireNonNull(precoUnitario, "O preço unitário não pode ser nulo");

        if (this.quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade do item deve ser maior que zero");
        }
        if (this.precoUnitario.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O preço unitário deve ser maior que zero");
        }

        // Encapsulamento matemático: o preço total é calculado no nascimento do objeto.
        this.precoTotal = this.precoUnitario.multiply(new BigDecimal(this.quantidade));
    }

    // =========================================================================================
    // Getters puros (Sem Setters para proteger a integridade financeira)
    // =========================================================================================

    public UUID getId() {
        return id;
    }

    public Pedido getPedido() {
        return pedido;
    }

    public String getProdutoId() {
        return produtoId;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public BigDecimal getPrecoUnitario() {
        return precoUnitario;
    }

    public BigDecimal getPrecoTotal() {
        return precoTotal;
    }

    // =========================================================================================
    // Equals e HashCode baseados no UUID gerado em memória.
    // =========================================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemPedido that = (ItemPedido) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}