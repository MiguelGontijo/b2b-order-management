package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.CadastrarParceiroUseCase;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.infrastructure.api.dto.CadastrarParceiroRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;

/**
 * Adaptador de Entrada (Inbound Adapter) responsável pela interface HTTP do Parceiro.
 * <p>
 * Este Controller orquestra puramente o tráfego da Web. Ele recebe os payloads JSON,
 * traduz para as estruturas do Caso de Uso (Commands) e converte a resposta nativa
 * para o padrão RESTful corporativo.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/parceiros")
@Tag(name = "Parceiros B2B", description = "Endpoints de Onboarding e Gestão Financeira de Clientes Corporativos")
public class ParceiroController {

    private final CadastrarParceiroUseCase cadastrarParceiroUseCase;

    public ParceiroController(CadastrarParceiroUseCase cadastrarParceiroUseCase) {
        this.cadastrarParceiroUseCase = cadastrarParceiroUseCase;
    }

    /**
     * Endpoint para cadastro de um novo Parceiro B2B.
     * <p>
     * <b>Decisão Arquitetural RESTful:</b> Ao criar um novo recurso no sistema,
     * o padrão exige que retornemos o HTTP Status 201 (Created) acompanhado de
     * um header 'Location' apontando para a URI do novo recurso.
     * Além disso, a anotação @Valid garante a blindagem do payload na fronteira.
     * </p>
     */
    @PostMapping
    @Operation(
            summary = "Cadastra um novo Parceiro",
            description = "Injeta um cliente no banco de dados estabelecendo o seu limite de crédito inicial. O CNPJ deve ser único no ecossistema da Bayer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Parceiro cadastrado com sucesso e limite liberado para uso."),
            @ApiResponse(responseCode = "400", description = "Falha de validação no payload (ex: CNPJ fora do padrão ou limite negativo)."),
            @ApiResponse(responseCode = "422", description = "Regra de negócio violada (Ex: CNPJ já existe).")
    })
    public ResponseEntity<ParceiroResponse> cadastrarParceiro(@Valid @RequestBody CadastrarParceiroRequest payload) {
        // 1. Tradução da Web (DTO) para a estrutura do Motor (Command)
        CadastrarParceiroUseCase.CadastrarParceiroCommand command =
                new CadastrarParceiroUseCase.CadastrarParceiroCommand(
                        payload.nome(),
                        payload.cnpj(),
                        payload.limiteCredito()
                );

        // 2. Execução da Regra de Negócio (Transação ACID)
        Parceiro parceiroSalvo = cadastrarParceiroUseCase.execute(command);

        // 3. Montagem do header "Location" e conversão para o DTO de Saída
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(parceiroSalvo.getId())
                .toUri();

        return ResponseEntity.created(location).body(ParceiroResponse.from(parceiroSalvo));
    }

    // =========================================================================================
    // DTOs Aninhados - Padrão de Alta Coesão (Nested Records)
    // =========================================================================================

       public record ParceiroResponse(
            Long id,
            String nome,
            String cnpj,
            BigDecimal limiteCreditoDisponivel
    ) {
        /**
         * Factory Method (Método de Fábrica) para traduzir a Entidade de Domínio
         * para a representação JSON segura que será exposta para fora.
         */
        public static ParceiroResponse from(Parceiro parceiro) {
            return new ParceiroResponse(
                    parceiro.getId(),
                    parceiro.getNome(),
                    parceiro.getCnpj(),
                    parceiro.getLimiteCredito()
            );
        }
    }
}