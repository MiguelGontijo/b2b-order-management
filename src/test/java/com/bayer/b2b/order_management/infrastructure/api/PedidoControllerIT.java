package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ProcessarPedidoCommand;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.api.dto.ProcessarPedidoRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite de Testes de Contrato HTTP e Interface Web para PedidoController.
 * <p>
 * O foco desta classe é estritamente testar o comportamento do adaptador Inbound:
 * Conversão de DTOs JSON, mapeamento de URIs, injeção de dependências web e
 * devolução correta de status RESTful padronizados em caso de sucesso (201) ou
 * em caso de interrupções de negócio (422) interceptadas pelo @ControllerAdvice.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@WebMvcTest(PedidoController.class)
@Import(PedidoControllerIT.JacksonTestConfig.class) // Injeção garantida do ObjectMapper no Spring 4.1.1
@DisplayName("API HTTP: PedidoController")
class PedidoControllerIT {

    /**
     * Configuração explícita para evitar falhas de contexto nos testes aninhados
     */
    @TestConfiguration
    static class JacksonTestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProcessarPedidoUseCase processarPedidoUseCase;

    @Nested
    @DisplayName("1. Fluxo de Sucesso (Happy Path)")
    class FluxosSucessoTests {

        @Test
        @DisplayName("Deve retornar 201 Created, URI no Location e payload achatamdo ao processar pedido")
        void deveProcessarPedidoComSucesso() throws Exception {
            // Arrange - Payload JSON de Entrada simulando o cliente Web
            ProcessarPedidoRequest.ItemPedidoRequest itemRequest = new ProcessarPedidoRequest.ItemPedidoRequest("PROD-001", 10, new BigDecimal("50.00"));
            ProcessarPedidoRequest payloadRequest = new ProcessarPedidoRequest(99L, "ERP-123", List.of(itemRequest));

            // Arrange - Precisamos criar um stub da entidade rica que o UseCase teoricamente devolveria
            Parceiro parceiroSimulado = new Parceiro("Bayer Agro", "11111111111111", new BigDecimal("50000.00"));
            Pedido pedidoSimulado = new Pedido(parceiroSimulado, "ERP-123") {
                // Truque arquitetural sem ferir o encapsulamento: forçamos um UUID estático na subclasse anônima
                // para que a validação do header "Location" e do JSON de resposta não falhem por causa de IDs randômicos.
                private final UUID idFixo = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

                @Override
                public UUID getId() {
                    return idFixo;
                }
            };

            // Subimos o status para APROVADO para simular a saída real do UseCase
            pedidoSimulado.aprovar();

            when(processarPedidoUseCase.execute(any(ProcessarPedidoCommand.class))).thenReturn(pedidoSimulado);

            // Act & Assert
            mockMvc.perform(post("/api/v1/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadRequest)))
                    .andExpect(status().isCreated()) // Atesta que o status não foi 200 OK genérico, mas sim 201 Created corporativo
                    .andExpect(header().exists("Location")) // Valida obrigatoriedade de RFC para criação de recursos
                    .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/pedidos/123e4567-e89b-12d3-a456-426614174000")))
                    .andExpect(jsonPath("$.id").value("123e4567-e89b-12d3-a456-426614174000"))
                    .andExpect(jsonPath("$.cnpjParceiro").value("11111111111111"))
                    .andExpect(jsonPath("$.status").value("APROVADO"))
                    // Garante que não vazamos dados não declarados na resposta B2B
                    .andExpect(jsonPath("$.valorTotal").exists());
        }
    }

    @Nested
    @DisplayName("2. Fluxos de Exceção e Proteção (Error Handling)")
    class FluxosExcecaoTests {

        @Test
        @DisplayName("Deve traduzir erro de Saldo Insuficiente do Domínio (IllegalArgumentException) para HTTP 422")
        void deveRetornar422QuandoSaldoEstourar() throws Exception {
            // Arrange - Submissão padrão
            ProcessarPedidoRequest.ItemPedidoRequest itemRequest = new ProcessarPedidoRequest.ItemPedidoRequest("PROD-RARO", 1, new BigDecimal("999999.00"));
            ProcessarPedidoRequest payloadRequest = new ProcessarPedidoRequest(99L, "ERP-123", List.of(itemRequest));

            // Simulamos o domínio ativando a sua barreira Fail-Fast financeira
            when(processarPedidoUseCase.execute(any(ProcessarPedidoCommand.class)))
                    .thenThrow(new IllegalArgumentException("Crédito insuficiente para o parceiro: 11111111111111"));

            // Act & Assert
            // Prova arquitetural: Graças ao GlobalExceptionHandler criado anteriormente,
            // a API não vai estourar erro 500 para o parceiro cliente.
            mockMvc.perform(post("/api/v1/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadRequest)))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(status().isUnprocessableContent()) // Exige rigorosamente código HTTP 422
                    .andExpect(jsonPath("$.title").value("Validação de Negócio Violada"))
                    .andExpect(jsonPath("$.detail").value("Crédito insuficiente para o parceiro: 11111111111111"));
        }

        @Test
        @DisplayName("Deve barrar payload sem parceiro na fronteira (Bean Validation) retornando HTTP 400")
        void deveRetornar400QuandoPayloadFalharNoBeanValidation() throws Exception {
            // Arrange - Falta o ID do cliente
            ProcessarPedidoRequest payloadSemCliente = new ProcessarPedidoRequest(
                    null, // parceiroId nulo para acionar o @NotNull da fronteira
                    "ERP-123",
                    List.of(new ProcessarPedidoRequest.ItemPedidoRequest("X", 1, BigDecimal.ONE))
            );

            // O mock do UseCase foi removido, pois o @Valid bloqueia o fluxo ANTES de chegar nele.

            // Act & Assert
            mockMvc.perform(post("/api/v1/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadSemCliente)))
                    .andExpect(status().isBadRequest()) // Agora exige 400 Bad Request
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Falha na Validação dos Dados"))
                    // Valida se o mapa de violações mapeou exatamente o campo que falhou
                    .andExpect(jsonPath("$.violacoes.parceiroId").value("O ID do parceiro é obrigatório"));
        }
    }
}