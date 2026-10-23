package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.AprovarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.CancelarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.MovimentarPedidoUseCase;
import com.bayer.b2b.order_management.domain.exception.PedidoNaoEncontradoException;
import com.bayer.b2b.order_management.infrastructure.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite de testes de integração da fronteira Web (Inbound) para Ações Intencionais do Pedido.
 * <p>
 * Decisões Arquiteturais Sênior:
 * 1. Slicing (@WebMvcTest): Subimos apenas a camada HTTP, isolando o banco de dados.
 * 2. Validação de Contrato (CQRS): Atestamos que comandos de mutação não retornam payload
 *    (HTTP 204 No Content), preservando banda de rede e CPU (sem serialização Jackson).
 * 3. Roteamento Semântico: Verificamos se cada URL intencional aciona estritamente o
 *    UseCase atômico correspondente, blindando a responsabilidade única (SRP).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@WebMvcTest(PedidoAcaoController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("Web/CQRS: PedidoAcaoController")
class PedidoAcaoControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AprovarPedidoUseCase aprovarPedidoUseCase;

    @MockitoBean
    private MovimentarPedidoUseCase movimentarPedidoUseCase;

    @MockitoBean
    private CancelarPedidoUseCase cancelarPedidoUseCase;

    private UUID pedidoIdAlvo;

    @BeforeEach
    void setUp() {
        pedidoIdAlvo = UUID.randomUUID();
    }

    @Nested
    @DisplayName("1. Roteamento Semântico e Sucesso (HTTP 204 No Content)")
    class AcoesComSucessoTests {

        @Test
        @DisplayName("POST /aprovacao -> Deve acionar AprovarPedidoUseCase e retornar 204")
        void deveAprovarERetornar204() throws Exception {
            mockMvc.perform(post("/api/v1/pedidos/{id}/aprovacao", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string("")); // Garante que o body está vazio

            verify(aprovarPedidoUseCase, times(1)).execute(pedidoIdAlvo);
            verifyNoInteractions(movimentarPedidoUseCase, cancelarPedidoUseCase);
        }

        @Test
        @DisplayName("POST /processamento -> Deve acionar MovimentarPedidoUseCase.processar e retornar 204")
        void deveProcessarERetornar204() throws Exception {
            mockMvc.perform(post("/api/v1/pedidos/{id}/processamento", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(movimentarPedidoUseCase, times(1)).processar(pedidoIdAlvo);
            verifyNoInteractions(aprovarPedidoUseCase, cancelarPedidoUseCase);
        }

        @Test
        @DisplayName("POST /envio -> Deve acionar MovimentarPedidoUseCase.enviar e retornar 204")
        void deveEnviarERetornar204() throws Exception {
            mockMvc.perform(post("/api/v1/pedidos/{id}/envio", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(movimentarPedidoUseCase, times(1)).enviar(pedidoIdAlvo);
            verifyNoInteractions(aprovarPedidoUseCase, cancelarPedidoUseCase);
        }

        @Test
        @DisplayName("POST /entrega -> Deve acionar MovimentarPedidoUseCase.entregar e retornar 204")
        void deveEntregarERetornar204() throws Exception {
            mockMvc.perform(post("/api/v1/pedidos/{id}/entrega", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(movimentarPedidoUseCase, times(1)).entregar(pedidoIdAlvo);
            verifyNoInteractions(aprovarPedidoUseCase, cancelarPedidoUseCase);
        }

        @Test
        @DisplayName("POST /cancelamento -> Deve acionar CancelarPedidoUseCase e retornar 204")
        void deveCancelarERetornar204() throws Exception {
            mockMvc.perform(post("/api/v1/pedidos/{id}/cancelamento", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());

            verify(cancelarPedidoUseCase, times(1)).execute(pedidoIdAlvo);
            verifyNoInteractions(aprovarPedidoUseCase, movimentarPedidoUseCase);
        }
    }

    @Nested
    @DisplayName("2. Escudos de Erro (Fail-Fast e Interceptação)")
    class ErrosEContingenciasTests {

        @Test
        @DisplayName("Deve interceptar PedidoNaoEncontradoException e traduzir para HTTP 404 (Not Found)")
        void deveRetornarStatus404QuandoPedidoNaoExistir() throws Exception {
            // Arrange
            String mensagemErro = "Pedido não localizado para o ID: " + pedidoIdAlvo;

            // Simulamos que o UseCase não achou o pedido e estourou o erro do domínio
            doThrow(new PedidoNaoEncontradoException(mensagemErro))
                    .when(aprovarPedidoUseCase).execute(pedidoIdAlvo);

            // Act & Assert
            mockMvc.perform(post("/api/v1/pedidos/{id}/aprovacao", pedidoIdAlvo)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Recurso Não Localizado"))
                    .andExpect(jsonPath("$.detail").value(mensagemErro));

            verify(aprovarPedidoUseCase, times(1)).execute(pedidoIdAlvo);
        }

        @Test
        @DisplayName("Deve bloquear UUID malformado na própria URL com HTTP 400 antes de acionar a camada de domínio")
        void deveRetornarStatus400QuandoUuidForInvalido() throws Exception {
            // Arrange
            String uuidInvalido = "texto-qualquer-nao-uuid";

            // Act & Assert
            mockMvc.perform(post("/api/v1/pedidos/{id}/processamento", uuidInvalido)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            // Garantia Arquitetural: Se a tipagem da URL falha, o Controller/UseCase nem gasta CPU.
            verifyNoInteractions(movimentarPedidoUseCase);
        }
    }
}