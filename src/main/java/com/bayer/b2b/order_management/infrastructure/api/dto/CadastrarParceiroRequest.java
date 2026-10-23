package com.bayer.b2b.order_management.infrastructure.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * DTO de Entrada (Inbound API Contract) para a requisição de cadastro de um novo Parceiro B2B.
 * <p>
 * Decisões Arquiteturais Sênior:
 * 1. Isolamento: Extraído do interior do Controller para o pacote de DTOs, garantindo
 *    reaproveitamento, alta coesão e limpeza visual da fronteira REST.
 * 2. Blindagem de Fronteira (Fail-Fast): Aplicação do Bean Validation para barrar
 *    cargas sujas (Payloads inválidos) antes de acionar a camada de Domínio.
 * 3. Normalização: A anotação @Pattern obriga que o CNPJ chegue limpo da Web,
 *    contendo estritamente 14 dígitos numéricos, alinhado com o armazenamento padronizado.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public record CadastrarParceiroRequest(

        @NotBlank(message = "O nome do parceiro é obrigatório e não pode estar em branco")
        String nome,

        @NotBlank(message = "O CNPJ é obrigatório")
        @Pattern(regexp = "^\\d{14}$", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem máscara")
        String cnpj,

        @NotNull(message = "O limite de crédito é obrigatório")
        @PositiveOrZero(message = "O limite de crédito não pode ser negativo")
        @Digits(integer = 17, fraction = 2, message = "O limite financeiro deve ter no máximo 17 dígitos inteiros e 2 decimais")
        BigDecimal limiteCredito
) {}