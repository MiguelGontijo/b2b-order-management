package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.CadastrarParceiroUseCase;
import com.bayer.b2b.order_management.application.usecase.CadastrarParceiroUseCase.CadastrarParceiroCommand;
import com.bayer.b2b.order_management.domain.exception.CreditoInsuficienteException;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.infrastructure.api.dto.CadastrarParceiroRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite de Testes de Contrato e Interface Web (API REST) para ParceiroController.
 * <p>
 * Utiliza @WebMvcTest para carregar estritamente a fatia Web do Spring (Controllers,
 * Filtros, ExceptionHandlers), isolando todo o resto do sistema. O UseCase é "mockado"
 * para que possamos focar exclusivamente no comportamento HTTP (Serialização JSON,
 * Status Codes e Cabeçalhos).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@WebMvcTest(ParceiroController.class)
@Import(ParceiroControllerIT.JacksonTestConfig.class)
@DisplayName("API HTTP: ParceiroController")
class ParceiroControllerIT {

    @TestConfiguration
    static class JacksonTestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
    /**
     * Motor principal para simular requisições HTTP sem a necessidade de
     * subir um servidor web real (Tomcat/Undertow), garantindo execução em milissegundos.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Utilitário do Jackson (já configurado pelo Spring) para converter
     * nossos DTOs em JSON (Serialização) de forma realista.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * O Bean que injetamos na Controller. Como estamos em um Slice Test (@WebMvcTest),
     * a infraestrutura e o repositório não existem aqui. Precisamos mockar o UseCase.
     */
    @MockitoBean
    private CadastrarParceiroUseCase cadastrarParceiroUseCase;

    @Nested
    @DisplayName("1. Fluxo de Sucesso (Happy Path)")
    class FluxosSucessoTests {

        @Test
        @DisplayName("Deve retornar 201 Created, Header Location e o JSON de resposta ao cadastrar com sucesso")
        void deveRetornar201CreatedComLocationEPayload() throws Exception {
            // Arrange - Preparação do Request DTO
            CadastrarParceiroRequest payloadRequest =
                    new CadastrarParceiroRequest("Bayer Corp", "12345678901234", new BigDecimal("50000.00"));

            // Arrange - Simulação da execução do Domínio (já atestamos essa lógica nos testes anteriores)
            // Usamos a engine de Reflection ou instanciamos a entidade e forçamos o ID (que não tem setter)
            // Aqui, por não termos setter, passamos pelo construtor e mockamos o retorno.
            // Para efeitos de teste de HTTP, simularemos o comportamento do Mockito retornando o Parceiro.
            Parceiro parceiroSimulado = new Parceiro("Bayer Corp", "12345678901234", new BigDecimal("50000.00"));

            // Um truque avançado para testes sem Setter é criar uma subclasse anônima apenas para embutir o ID
            Parceiro parceiroComId = new Parceiro("Bayer Corp", "12345678901234", new BigDecimal("50000.00")) {
                @Override
                public Long getId() {
                    return 1L; // Simulando a Primary Key gerada pelo banco
                }
            };

            when(cadastrarParceiroUseCase.execute(any(CadastrarParceiroCommand.class))).thenReturn(parceiroComId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/parceiros")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadRequest)))
                    .andExpect(status().isCreated()) // Exige HTTP 201
                    .andExpect(header().exists("Location")) // Atesta padrão REST corporativo
                    .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/parceiros/1")))
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.nome").value("Bayer Corp"))
                    .andExpect(jsonPath("$.cnpj").value("12345678901234"))
                    .andExpect(jsonPath("$.limiteCreditoDisponivel").value(50000.00));
        }
    }

    @Nested
    @DisplayName("2. Fluxos de Exceção (Error Handling)")
    class FluxosExcecaoTests {

        @Test
        @DisplayName("Deve barrar payload com limite negativo na fronteira (Bean Validation) retornando HTTP 400")
        void deveRetornar400QuandoLimiteCreditoNegativo() throws Exception {
            // Arrange
            CadastrarParceiroRequest payloadRequest =
                    new CadastrarParceiroRequest("Bayer Europa", "99999999999999", new BigDecimal("-100.00"));

            // O mock do UseCase foi removido, pois o @Valid bloqueia o fluxo ANTES de chegar nele.

            // Act & Assert
            mockMvc.perform(post("/api/v1/parceiros")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadRequest)))
                    .andExpect(status().isBadRequest()) // Exige HTTP 400
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Falha na Validação dos Dados"))
                    .andExpect(jsonPath("$.violacoes.limiteCredito").value("O limite de crédito não pode ser negativo"));
        }

        @Test
        @DisplayName("Deve barrar payload sem CNPJ na fronteira (Bean Validation) retornando HTTP 400")
        void deveRetornar400QuandoCnpjAusente() throws Exception {
            // Arrange
            CadastrarParceiroRequest payloadRequest =
                    new CadastrarParceiroRequest("Bayer Ásia", null, new BigDecimal("100.00"));

            // Act & Assert
            mockMvc.perform(post("/api/v1/parceiros")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payloadRequest)))
                    .andExpect(status().isBadRequest()) // Exige HTTP 400
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Falha na Validação dos Dados"))
                    .andExpect(jsonPath("$.violacoes.cnpj").value("O CNPJ é obrigatório"));
        }
    }
}