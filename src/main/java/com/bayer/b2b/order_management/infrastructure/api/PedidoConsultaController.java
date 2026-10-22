package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.BuscarPedidoPorIdUseCase;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.api.dto.PedidoDetalhadoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador de Entrada (Inbound Adapter) exclusivo para operações de leitura de Pedidos B2B.
 * <p>
 * Padrão Arquitetural: CQRS (Command Query Responsibility Segregation).
 * Ao segregar a leitura da escrita, garantimos que endpoints pesados de consulta não
 * interfiram na manutenção e injeção de dependências dos endpoints de mutação transacional.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Consultas de Pedidos B2B", description = "Endpoints otimizados para leitura (Read-Only) com extração de agregados ricos via JOIN FETCH.")
public class PedidoConsultaController {

    private final BuscarPedidoPorIdUseCase buscarPedidoPorIdUseCase;

    public PedidoConsultaController(BuscarPedidoPorIdUseCase buscarPedidoPorIdUseCase) {
        this.buscarPedidoPorIdUseCase = buscarPedidoPorIdUseCase;
    }

    /**
     * Endpoint para consulta detalhada da nota estrutural de um pedido.
     * <p>
     * Delega a extração otimizada ao UseCase e realiza o "flatten" (achatamento) da
     * entidade rica para o DTO de saída, ocultando regras matemáticas do cliente web.
     * </p>
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Consulta um pedido detalhado por ID",
            description = "Recupera os dados do pedido, seus itens e o status logístico atual. Retorna 404 caso o UUID não conste no banco de dados."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pedido localizado e serializado com sucesso."),
            @ApiResponse(responseCode = "404", description = "O UUID fornecido não corresponde a nenhum pedido.")
    })
    public ResponseEntity<PedidoDetalhadoResponse> buscarPorId(@PathVariable UUID id) {

        Pedido pedido = buscarPedidoPorIdUseCase.execute(id);

        return ResponseEntity.ok(PedidoDetalhadoResponse.from(pedido));
    }
}