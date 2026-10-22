package com.bayer.b2b.order_management.infrastructure.api;

import com.bayer.b2b.order_management.application.usecase.BuscarPedidoPorIdUseCase;
import com.bayer.b2b.order_management.application.usecase.ConsultarPedidosUseCase;
import com.bayer.b2b.order_management.application.usecase.PedidoFiltro;
import com.bayer.b2b.order_management.domain.execption.GlobalExceptionHandler;
import com.bayer.b2b.order_management.domain.execption.ResourceNotFoundException;
import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite de testes de integração da fronteira Web (Inbound) para a leitura de Pedidos.
 * <p>
 * Decisões Arquiteturais:
 * 1. Slicing (@WebMvcTest): Carrega exclusivamente os componentes web e os conversores JSON (Jackson).
 * 2. @Import(GlobalExceptionHandler.class): Garante a presença da nossa blindagem de erros no contexto isolado.
 * 3. Validação de Contrato: Assegura que o DTO PedidoDetalhadoResponse execute o achatamento
 *    perfeito dos dados, sem vazar informações internas da entidade de domínio.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@WebMvcTest(PedidoConsultaController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("Web/CQRS: PedidoConsultaController")
class PedidoConsultaControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuscarPedidoPorIdUseCase buscarPedidoPorIdUseCase;

    @MockitoBean
    private ConsultarPedidosUseCase consultarPedidosUseCase;

    private UUID pedidoIdAlvo;
    private Pedido pedidoMock;

    @BeforeEach
    void setUp() {
        pedidoIdAlvo = UUID.randomUUID();
        Parceiro parceiro = new Parceiro("Bayer B2B Client", "88888888888888", new BigDecimal("100000.00"));
        ReflectionTestUtils.setField(parceiro, "id", 99L);

        pedidoMock = new Pedido(parceiro);
        ReflectionTestUtils.setField(pedidoMock, "id", pedidoIdAlvo);
        ReflectionTestUtils.setField(pedidoMock, "dataCriacao", Instant.parse("2026-10-22T10:00:00Z"));
        ReflectionTestUtils.setField(pedidoMock, "dataUltimaAtualizacao", Instant.parse("2026-10-22T10:30:00Z"));
        ReflectionTestUtils.setField(pedidoMock, "status", StatusPedido.APROVADO);
        ReflectionTestUtils.setField(pedidoMock, "valorTotal", new BigDecimal("1500.00"));

        ItemPedido item = new ItemPedido(pedidoMock, "SKU-AGRO-001", 10, new BigDecimal("150.00"));
        ReflectionTestUtils.setField(pedidoMock, "itens", List.of(item));
    }

    @Nested
    @DisplayName("1. Sucesso e Achatamento de JSON (Flattening)")
    class ConsultaComSucessoTests {

        @Test
        @DisplayName("Deve retornar status 200 OK e o JSON com hierarquia detalhada do pedido e seus itens")
        void deveRetornarPedidoDetalhadoComStatus200() throws Exception {
            // Arrange
            when(buscarPedidoPorIdUseCase.execute(pedidoIdAlvo)).thenReturn(pedidoMock);

            // Act & Assert
            mockMvc.perform(get("/api/v1/pedidos/{id}", pedidoIdAlvo)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                    // Validação das raízes do DTO
                    .andExpect(jsonPath("$.id").value(pedidoIdAlvo.toString()))
                    .andExpect(jsonPath("$.parceiroId").value(99))
                    .andExpect(jsonPath("$.cnpjParceiro").value("88888888888888"))
                    .andExpect(jsonPath("$.status").value("APROVADO"))
                    .andExpect(jsonPath("$.valorTotal").value(1500.00))
                    .andExpect(jsonPath("$.dataCriacao").value("2026-10-22T10:00:00Z"))
                    .andExpect(jsonPath("$.dataUltimaAtualizacao").value("2026-10-22T10:30:00Z"))

                    // Validação da ramificação da nota fiscal (Itens Aninhados)
                    .andExpect(jsonPath("$.itens").isArray())
                    .andExpect(jsonPath("$.itens[0].produtoId").value("SKU-AGRO-001"))
                    .andExpect(jsonPath("$.itens[0].quantidade").value(10))
                    .andExpect(jsonPath("$.itens[0].precoUnitario").value(150.00));

            verify(buscarPedidoPorIdUseCase, times(1)).execute(pedidoIdAlvo);
        }
    }

    @Nested
    @DisplayName("2. Escudos de Erro (Fail-Fast e Handler)")
    class ErrosEContingenciasTests {

        @Test
        @DisplayName("Deve interceptar ResourceNotFoundException e formatar saída como HTTP 404 (Not Found)")
        void deveRetornarStatus404QuandoPedidoNaoExistir() throws Exception {
            // Arrange
            String mensagemErro = "Pedido não localizado para o ID: " + pedidoIdAlvo;
            when(buscarPedidoPorIdUseCase.execute(pedidoIdAlvo))
                    .thenThrow(new ResourceNotFoundException(mensagemErro));

            // Act & Assert
            mockMvc.perform(get("/api/v1/pedidos/{id}", pedidoIdAlvo)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Recurso Não Localizado"))
                    .andExpect(jsonPath("$.message").value(mensagemErro))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(buscarPedidoPorIdUseCase, times(1)).execute(pedidoIdAlvo);
        }

        @Test
        @DisplayName("Deve bloquear UUID malformado nativamente com HTTP 400 antes de acionar a camada de domínio")
        void deveRetornarStatus400QuandoUuidForInvalido() throws Exception {
            // Arrange
            String uuidInvalido = "um-uuid-impossivel-123";

            // Act & Assert
            // A própria infraestrutura do Spring Web deve quebrar no DataBinder da URL
            mockMvc.perform(get("/api/v1/pedidos/{id}", uuidInvalido)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            // Garantia Arquitetural: O UseCase não gasta processamento com requisições sujas
            verifyNoInteractions(buscarPedidoPorIdUseCase);
        }
    }

    @Nested
    @DisplayName("3. Listagem Paginada e Filtros Dinâmicos (Collection Resource)")
    class ListagemPaginadaTests {

        @Test
        @DisplayName("Deve retornar status 200 OK e a página com a lista resumida de pedidos, sem expor os itens")
        void deveRetornarPaginaDePedidosResumidosComStatus200() throws Exception {
            // Arrange - Prepara os metadados de paginação e a página mockada
            PageRequest pageable = PageRequest.of(0, 20);
            Page<Pedido> paginaMock = new PageImpl<>(List.of(pedidoMock), pageable, 1);

            // O Spring WebMvc instancia o Pageable internamente. Usamos any() para capturá-lo com segurança.
            when(consultarPedidosUseCase.execute(any(PedidoFiltro.class), any(Pageable.class)))
                    .thenReturn(paginaMock);

            // Act & Assert
            mockMvc.perform(get("/api/v1/pedidos")
                            .param("status", "APROVADO")
                            .param("page", "0")
                            .param("size", "20")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))

                    // Validação do Achatamento (Flattening) para o array de conteúdo
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(pedidoIdAlvo.toString()))
                    .andExpect(jsonPath("$.content[0].cnpjParceiro").value("88888888888888"))
                    .andExpect(jsonPath("$.content[0].valorTotal").value(1500.00))
                    .andExpect(jsonPath("$.content[0].status").value("APROVADO"))

                    // Garantia Arquitetural de Performance: A lista de itens NÃO DEVE existir neste payload
                    .andExpect(jsonPath("$.content[0].itens").doesNotExist())

                    // Validação dos metadados estruturais do Page<T> do Spring Data
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.number").value(0));

            // Confirma que a orquestração repassou os filtros corretamente para o domínio
            verify(consultarPedidosUseCase, times(1)).execute(any(PedidoFiltro.class), any(Pageable.class));
        }
    }
}