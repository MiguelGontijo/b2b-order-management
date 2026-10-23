package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ItemCommand;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ProcessarPedidoCommand;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.api.dto.PedidoResumoResponse;
import com.bayer.b2b.order_management.infrastructure.api.dto.ProcessarPedidoRequest;
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

import java.net.URI;
import java.util.List;
import java.util.Set;
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
@Tag(name = "Pedidos de Compra B2B", description = "Endpoints para orquestração transacional de compras e recepção de pedidos.")
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
     * A anotação @Valid aplica o Fail-Fast, bloqueando requisições com dados malformados.
     * </p>
     */
    @PostMapping
    @Operation(
            summary = "Submete um pedido de compra corporativa",
            description = "Recebe a intenção de compra, processa a lista de itens preservando duplicidades (List) e salva o pedido em altíssima vazão como PENDENTE. A dedução financeira ocorre posteriormente em endpoint de aprovação."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pedido recepcionado e criado com sucesso (Status PENDENTE)."),
            @ApiResponse(responseCode = "400", description = "Falha de validação no payload (ex: valores negativos, IDs ausentes)."),
            @ApiResponse(responseCode = "409", description = "Conflito de Idempotência: Um pedido com este código de origem já foi processado para este parceiro."),
            @ApiResponse(responseCode = "422", description = "Regra de negócio violada na validação estrutural do pedido.")
    })
    public ResponseEntity<PedidoResumoResponse> processarPedido(@Valid @RequestBody ProcessarPedidoRequest payload) {

        // 1. Tradução Limpa (Web DTO -> Core Command) utilizando Streams Funcionais
        // Alteração Arquitetural: Uso de List em vez de Set para preservar cardinalidade dos itens idênticos
        List<ItemCommand> itensCommand = payload.itens().stream()
                .map(it -> new ItemCommand(
                        it.produtoId(),
                        it.quantidade(),
                        it.precoUnitario()
                ))
                .collect(Collectors.toList());

        ProcessarPedidoCommand command = new ProcessarPedidoCommand(
                payload.parceiroId(),
                payload.codigoPedidoOrigem(),
                itensCommand
        );

        // 2. Orquestração Transacional (Cria Pedido PENDENTE, Salva no DB, Dispara Evento de Criação)
        Pedido pedidoProcessado = processarPedidoUseCase.execute(command);

        // 3. Montagem do cabeçalho RESTful e tradução da resposta
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(pedidoProcessado.getId())
                .toUri();

        return ResponseEntity.created(location).body(PedidoResumoResponse.from(pedidoProcessado));
    }

}