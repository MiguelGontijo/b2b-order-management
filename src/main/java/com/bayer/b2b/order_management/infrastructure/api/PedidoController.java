package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ItemCommand;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ProcessarPedidoCommand;
import com.bayer.b2b.order_management.domain.model.Pedido;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adaptador de Entrada (Inbound Adapter) responsável pela interface HTTP de Pedidos B2B.
 * <p>
 * Atua como a fronteira de proteção da aplicação. Mapeia os payloads JSON externos
 * para as estruturas imutáveis do Domínio, delegando a complexidade transacional e
 * de mensageria inteiramente para a camada de UseCases.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Pedidos de Compra B2B", description = "Endpoints para orquestração transacional de compras, débito de saldo e notificação assíncrona (Logística/Faturamento).")
public class PedidoController {

    private final ProcessarPedidoUseCase processarPedidoUseCase;

    public PedidoController(ProcessarPedidoUseCase processarPedidoUseCase) {
        this.processarPedidoUseCase = processarPedidoUseCase;
    }

    /**
     * Endpoint para submissão e processamento de um novo pedido de compra.
     * <p>
     * <b>Decisão Arquitetural:</b> Em sistemas de alto tráfego, a tradução do DTO web
     * para o Command de negócio protege o núcleo do sistema de vulnerabilidades de
     * injeção (Mass Assignment) e desacopla a versão da API da modelagem de dados.
     * </p>
     */
    @PostMapping
    @Operation(
            summary = "Submete um pedido de compra corporativa",
            description = "Recebe os itens, aciona o Pessimistic Lock para garantir exclusividade de saldo no PostgreSQL, debita o limite do cliente, salva o pedido e publica um evento assíncrono de APROVADO via Apache Kafka."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pedido processado com sucesso, saldo debitado e evento Kafka publicado."),
            @ApiResponse(responseCode = "422", description = "Regra de negócio violada (Ex: Limite de crédito insuficiente ou parceiro não encontrado).")
    })
    public ResponseEntity<PedidoResponse> processarPedido(@RequestBody ProcessarPedidoRequest payload) {

        // 1. Tradução Limpa (Web DTO -> Core Command) utilizando Streams Funcionais
        Set<ItemCommand> itensCommand = payload.itens().stream()
                .map(it -> new ItemCommand(
                        it.produtoId(),
                        it.quantidade(),
                        it.precoUnitario()
                ))
                .collect(Collectors.toSet());

        ProcessarPedidoCommand command = new ProcessarPedidoCommand(
                payload.parceiroId(),
                itensCommand
        );

        // 2. Orquestração Transacional (Valida Saldo, Cria Pedido, Salva no DB, Dispara Kafka)
        Pedido pedidoProcessado = processarPedidoUseCase.execute(command);

        // 3. Montagem do cabeçalho RESTful e tradução da resposta
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(pedidoProcessado.getId())
                .toUri();

        return ResponseEntity.created(location).body(PedidoResponse.from(pedidoProcessado));
    }

    // =========================================================================================
    // DTOs Aninhados - Isolamento de Contrato de API (Nested Records)
    // =========================================================================================

    public record ProcessarPedidoRequest(
            Long parceiroId,
            Set<ItemPedidoRequest> itens
    ) {}

    public record ItemPedidoRequest(
            String produtoId,
            Integer quantidade,
            BigDecimal precoUnitario
    ) {}

    public record PedidoResponse(
            UUID id,
            String cnpjParceiro,
            String status,
            BigDecimal valorTotal,
            Instant dataCriacao
    ) {
        /**
         * Factory Method para achatar (flatten) e traduzir a Entidade Rica em um JSON amigável,
         * expondo apenas os dados não-sensíveis para a interface do cliente.
         */
        public static PedidoResponse from(Pedido pedido) {
            return new PedidoResponse(
                    pedido.getId(),
                    pedido.getParceiro().getCnpj(),
                    pedido.getStatus().name(),
                    pedido.getValorTotal(),
                    Instant.now() // Em um sistema real, extrairíamos a data de auditoria da entidade
            );
        }
    }
}