package com.bayer.b2b.order_management.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Representa o Parceiro (Cliente B2B) no núcleo de negócio.
 * <p>
 * O foco desta entidade é o gerenciamento rigoroso do limite de crédito.
 * Construída com o padrão de Modelo de Domínio Rico (Rich Domain Model),
 * ela não expõe "Setters" públicos para atributos sensíveis, garantindo que
 * mutações de saldo ocorram exclusivamente através de regras de negócio internas.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Entity
@Table(name = "parceiros")
public class Parceiro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, unique = true, length = 14)
    private String cnpj;

    /*
     * Mapeamento de alta precisão para valores financeiros.
     * precision = 19 (dígitos totais), scale = 2 (casas decimais).
     */
    @Column(name = "limite_credito", nullable = false, precision = 19, scale = 2)
    private BigDecimal limiteCredito;

    /**
     * Construtor protegido exigido pelo framework JPA/Hibernate.
     * Não deve ser utilizado diretamente pela aplicação.
     */
    protected Parceiro() {
    }

    /**
     * Construtor principal para criação de novos parceiros.
     * Aplica o padrão Fail-Fast, impedindo a criação de instâncias inválidas na memória.
     */
    public Parceiro(String nome, String cnpj, BigDecimal limiteCredito) {
        this.nome = Objects.requireNonNull(nome, "O nome do parceiro não pode ser nulo");
        this.cnpj = Objects.requireNonNull(cnpj, "O CNPJ não pode ser nulo");
        this.limiteCredito = Objects.requireNonNull(limiteCredito, "O limite de crédito inicial não pode ser nulo");

        if (limiteCredito.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("O limite de crédito não pode ser negativo");
        }
    }

    /**
     * Executa o débito no limite de crédito do parceiro.
     * <p>
     * Este método encapsula a regra de negócio. Em um ambiente de alta concorrência,
     * este método será chamado após o banco de dados aplicar um Pessimistic Lock na linha,
     * garantindo a integridade absoluta da operação ACID.
     * </p>
     *
     * @param valor O valor do pedido a ser debitado.
     * @throws IllegalArgumentException Se o parceiro não possuir saldo suficiente.
     */
    public void debitarCredito(BigDecimal valor) {
        Objects.requireNonNull(valor, "O valor de débito não pode ser nulo");

        if (valor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O valor de débito deve ser maior que zero");
        }

        // compareTo retorna -1 se o limite atual for menor que o valor cobrado
        if (this.limiteCredito.compareTo(valor) < 0) {
            throw new IllegalArgumentException("Crédito insuficiente para o parceiro: " + this.cnpj);
        }

        this.limiteCredito = this.limiteCredito.subtract(valor);
    }

    /**
     * Executa o estorno de um valor ao limite de crédito do parceiro.
     * <p>
     * Utilizado exclusivamente em fluxos de cancelamento (logística reversa ou desistência)
     * de pedidos que já haviam deduzido saldo financeiro no passado. Assim como o débito,
     * este método exige execução sob bloqueio pessimista (Pessimistic Lock) gerenciado
     * pelo orquestrador para garantir a integridade ACID da conta do cliente.
     * </p>
     *
     * @param valor O montante financeiro a ser devolvido ao limite do parceiro.
     * @throws IllegalArgumentException Se o valor de estorno for inválido (zero ou negativo).
     */
    public void estornarCredito(BigDecimal valor) {
        Objects.requireNonNull(valor, "O valor de estorno não pode ser nulo");

        if (valor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O valor de estorno deve ser maior que zero");
        }

        this.limiteCredito = this.limiteCredito.add(valor);
    }

    // =========================================================================================
    // Getters puros (Apenas leitura)
    // NENHUM Setter foi criado. O estado do banco só muda através do construtor ou de métodos de negócio.
    // =========================================================================================

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getCnpj() {
        return cnpj;
    }

    public BigDecimal getLimiteCredito() {
        return limiteCredito;
    }

    // =========================================================================================
    // Equals e HashCode focados na chave de negócio (CNPJ) em vez do ID dinâmico do banco.
    // =========================================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Parceiro parceiro = (Parceiro) o;
        return cnpj.equals(parceiro.cnpj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cnpj);
    }

    @Override
    public String toString() {
        return "Parceiro{" +
                "id=" + id +
                ", nome='" + nome + '\'' +
                ", cnpj='" + cnpj + '\'' +
                ", limiteCredito=" + limiteCredito +
                '}';
    }
}