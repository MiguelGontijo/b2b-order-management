package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.BuscarPedidoPorIdUseCase;
import com.bayer.b2b.order_management.application.usecase.ConsultarPedidosUseCase;
import com.bayer.b2b.order_management.application.usecase.PedidoFiltro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.api.dto.PedidoDetalhadoResponse;
import com.bayer.b2b.order_management.infrastructure.api.dto.PedidoResumoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
    private final ConsultarPedidosUseCase consultarPedidosUseCase;

    public PedidoConsultaController(BuscarPedidoPorIdUseCase buscarPedidoPorIdUseCase,
                                    ConsultarPedidosUseCase consultarPedidosUseCase) {
        this.buscarPedidoPorIdUseCase = buscarPedidoPorIdUseCase;
        this.consultarPedidosUseCase = consultarPedidosUseCase;
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

    /**
     * Endpoint para listagem paginada e filtragem dinâmica de pedidos corporativos.
     * <p>
     * Recebe parâmetros opcionais de filtro via Query Parameters da URL e mapeia automaticamente
     * para o record PedidoFiltro. Aplica a paginação nativa e converte a saída para um formato
     * de resumo, garantindo alta performance e prevenção contra carregamento excessivo em memória.
     * </p>
     */
    @GetMapping
    @Operation(
            summary = "Lista pedidos corporativos com paginação e filtros opcionais",
            description = "Devolve uma lista resumida (sem a coleção de itens) otimizada para performance. Permite filtragem por status e período de data (UTC)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listagem paginada processada e retornada com sucesso.")
    })
    public ResponseEntity<Page<PedidoResumoResponse>> listar(
            PedidoFiltro filtro,
            @PageableDefault(size = 20, sort = "dataCriacao", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<Pedido> pedidosPage = consultarPedidosUseCase.execute(filtro, pageable);

        // A função map do Page do Spring aplica o Factory Method para cada elemento do array
        Page<PedidoResumoResponse> responsePage = pedidosPage.map(PedidoResumoResponse::from);

        return ResponseEntity.ok(responsePage);
    }
}