package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Caso de Uso (Interactor) responsável por registrar um novo Parceiro B2B (Cliente).
 * <p>
 * Atua como o ponto de entrada para o ecossistema financeiro do sistema.
 * É através deste serviço que o limite de crédito inicial do parceiro é
 * estabelecido e persistido com integridade transacional no banco de dados.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@Service
public class CadastrarParceiroUseCase {

    private final ParceiroRepository parceiroRepository;

    public CadastrarParceiroUseCase(ParceiroRepository parceiroRepository) {
        this.parceiroRepository = parceiroRepository;
    }

    /**
     * Executa o registro de um novo parceiro no sistema.
     * <p>
     * <b>Decisão Arquitetural:</b> A anotação @Transactional garante que o
     * registro seja atômico. Como o CNPJ é uma chave única (unique = true) na
     * entidade de domínio, se houver uma tentativa de cadastro duplicado,
     * o banco de dados ejetará uma restrição (ConstraintViolationException),
     * impedindo fraudes ou inconsistências de clientes em duplicidade.
     * </p>
     *
     * @param command O objeto de transferência contendo os dados do novo parceiro.
     * @return A entidade Parceiro persistida, com o ID já gerado pelo PostgreSQL.
     */
    @Transactional
    public Parceiro execute(CadastrarParceiroCommand command) {
        Objects.requireNonNull(command, "O comando de cadastro não pode ser nulo");

        // A entidade aplica suas próprias validações de negócio no construtor (Fail-Fast)
        Parceiro novoParceiro = new Parceiro(
                command.nome(),
                command.cnpj(),
                command.limiteCredito()
        );

        return parceiroRepository.save(novoParceiro);
    }

    // =========================================================================================
    // DTO de Entrada (Command) usando Records para garantir Imutabilidade
    // =========================================================================================

    public record CadastrarParceiroCommand(
            String nome,
            String cnpj,
            BigDecimal limiteCredito
    ) {
        public CadastrarParceiroCommand {
            Objects.requireNonNull(nome, "O nome do parceiro é obrigatório");
            Objects.requireNonNull(cnpj, "O CNPJ é obrigatório");
            Objects.requireNonNull(limiteCredito, "O limite de crédito é obrigatório");

            if (nome.trim().isEmpty()) {
                throw new IllegalArgumentException("O nome não pode estar vazio");
            }
        }
    }
}