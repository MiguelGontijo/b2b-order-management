package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.AprovarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.CancelarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.MovimentarPedidoUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controlador REST focado exclusivamente nas Mutações de Estado (Commands) do Pedido.
 * <p>
 * Decisões Arquiteturais (Padrão Ouro / DDD):
 * 1. Segregação de Interfaces (CQRS): Separamos as consultas das ações intencionais.
 *    Isso permite escalar, monitorar e proteger as rotas de escrita de forma independente,
 *    facilitando a aplicação de filtros de segurança (ex: @PreAuthorize) específicos por ação.
 * 2. Ações Intencionais vs CRUD: Em vez de um PATCH genérico expondo o banco de dados,
 *    utilizamos verbos de negócio mapeados como sub-recursos via método POST (ex: /aprovacao).
 *    Isso blinda a máquina de estados e expressa a Linguagem Ubíqua diretamente na API.
 * 3. Performance de Rede: Retornamos HTTP 204 (No Content) após o sucesso da transição,
 *    economizando banda de rede e processamento de serialização JSON, uma prática
 *    recomendada em ecossistemas de microsserviços de alta vazão.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Pedidos - Intenções e Mutações", description = "Endpoints de ações de negócio sobre a esteira de pedidos (DDD)")
public class PedidoAcaoController {

    private final AprovarPedidoUseCase aprovarPedidoUseCase;
    private final MovimentarPedidoUseCase movimentarPedidoUseCase;
    private final CancelarPedidoUseCase cancelarPedidoUseCase;

    public PedidoAcaoController(
            AprovarPedidoUseCase aprovarPedidoUseCase,
            MovimentarPedidoUseCase movimentarPedidoUseCase,
            CancelarPedidoUseCase cancelarPedidoUseCase) {
        this.aprovarPedidoUseCase = aprovarPedidoUseCase;
        this.movimentarPedidoUseCase = movimentarPedidoUseCase;
        this.cancelarPedidoUseCase = cancelarPedidoUseCase;
    }

    @PostMapping("/{id}/aprovacao")
    @Operation(
            summary = "Aprova financeiramente um pedido pendente",
            description = "Aciona o Pessimistic Lock e debita o valor do limite de crédito do parceiro B2B."
    )
    @ApiResponse(responseCode = "204", description = "Pedido aprovado com sucesso.")
    public ResponseEntity<Void> aprovar(@PathVariable UUID id) {
        aprovarPedidoUseCase.execute(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/processamento")
    @Operation(
            summary = "Inicia o processamento logístico",
            description = "Move o pedido aprovado para a fase de separação e embalagem no centro de distribuição."
    )
    @ApiResponse(responseCode = "204", description = "Pedido em processamento.")
    public ResponseEntity<Void> processar(@PathVariable UUID id) {
        movimentarPedidoUseCase.processar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/envio")
    @Operation(
            summary = "Despacha o pedido",
            description = "Registra a entrega física do pedido para a transportadora."
    )
    @ApiResponse(responseCode = "204", description = "Pedido enviado com sucesso.")
    public ResponseEntity<Void> enviar(@PathVariable UUID id) {
        movimentarPedidoUseCase.enviar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/entrega")
    @Operation(
            summary = "Confirma a entrega do pedido",
            description = "Registra o recebimento físico do pedido pelo parceiro, concluindo o fluxo."
    )
    @ApiResponse(responseCode = "204", description = "Pedido entregue com sucesso.")
    public ResponseEntity<Void> entregar(@PathVariable UUID id) {
        movimentarPedidoUseCase.entregar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancelamento")
    @Operation(
            summary = "Cancela o pedido",
            description = "Interrompe a esteira logística do pedido. Realiza o estorno seguro do limite de crédito caso o pedido já tivesse sido aprovado no passado."
    )
    @ApiResponse(responseCode = "204", description = "Pedido cancelado com sucesso.")
    public ResponseEntity<Void> cancelar(@PathVariable UUID id) {
        cancelarPedidoUseCase.execute(id);
        return ResponseEntity.noContent().build();
    }
}