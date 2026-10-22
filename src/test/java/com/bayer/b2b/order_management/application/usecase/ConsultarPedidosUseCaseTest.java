package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para a orquestração de listagem paginada de Pedidos.
 * <p>
 * Decisões Arquiteturais de Teste:
 * 1. Isolamento Absoluto: Foco em validar a lógica de orquestração do UseCase,
 *    zombando (mocking) a infraestrutura de dados. A montagem real do SQL pelo motor de
 *    Specification será testada cirurgicamente no nível de integração (RepositoryIT).
 * 2. Segurança de Memória: Garantimos que o bloqueio contra requisições sem limites
 *    (Pageable nulo) estoure erro imediatamente, protegendo a JVM de OutOfMemoryErrors (OOM).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Application/UseCase: ConsultarPedidos")
class ConsultarPedidosUseCaseTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @InjectMocks
    private ConsultarPedidosUseCase useCase;

    private PedidoFiltro filtroPadrao;
    private Pageable pageablePadrao;
    private Page<Pedido> paginaSimulada;

    @BeforeEach
    void setUp() {
        // Mock dos parâmetros de entrada
        filtroPadrao = new PedidoFiltro(StatusPedido.APROVADO, Instant.now().minusSeconds(3600), Instant.now());
        pageablePadrao = PageRequest.of(0, 20);

        // Mock da Entidade de saída
        Parceiro parceiro = new Parceiro("Bayer B2B Mock", "99999999999999", new BigDecimal("10000.00"));
        Pedido pedidoMock = new Pedido(parceiro);

        // Estrutura de Paginação nativa do Spring
        paginaSimulada = new PageImpl<>(List.of(pedidoMock), pageablePadrao, 1);
    }

    @Nested
    @DisplayName("1. Fail-Fast e Proteção de Memória")
    class FailFastTests {

        @Test
        @DisplayName("Deve abortar a execução e lançar NullPointerException se o Pageable for nulo")
        void deveFalharQuandoPageableForNulo() {
            // Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(filtroPadrao, null)
            );

            assertEquals("As diretrizes de paginação (Pageable) são obrigatórias para proteger a memória da aplicação.", exception.getMessage());

            // Garantia Sênior: Se os parâmetros ferem a segurança, a infraestrutura (I/O) jamais será tocada.
            verifyNoInteractions(pedidoRepository);
        }
    }

    @Nested
    @DisplayName("2. Orquestração e Delegação para Infraestrutura")
    class OrquestracaoTests {

        @Test
        @DisplayName("Deve converter o Filtro em Specification, acionar o banco com Pageable e retornar a Página de Pedidos")
        void deveRetornarPaginaDePedidosComSucesso() {
            // Arrange
            // Aceitamos qualquer Specification gerada dinamicamente, mas exigimos o Pageable exato
            when(pedidoRepository.findAll(any(Specification.class), eq(pageablePadrao)))
                    .thenReturn(paginaSimulada);

            // Act
            Page<Pedido> resultado = useCase.execute(filtroPadrao, pageablePadrao);

            // Assert
            assertNotNull(resultado, "A página de retorno não pode ser nula");
            assertEquals(1, resultado.getTotalElements(), "A contagem total deve corresponder ao mock de dados");
            assertEquals(paginaSimulada.getContent().get(0).getParceiro().getCnpj(),
                    resultado.getContent().get(0).getParceiro().getCnpj());

            verify(pedidoRepository, times(1)).findAll(any(Specification.class), eq(pageablePadrao));
        }

        @Test
        @DisplayName("Deve orquestrar a busca com sucesso ignorando os filtros caso o objeto PedidoFiltro seja nulo")
        void deveExecutarComSucessoQuandoFiltroForNulo() {
            // Arrange
            when(pedidoRepository.findAll(any(Specification.class), eq(pageablePadrao)))
                    .thenReturn(paginaSimulada);

            // Act (Simula a ausência de Query Parameters na URL HTTP)
            Page<Pedido> resultado = useCase.execute(null, pageablePadrao);

            // Assert
            assertNotNull(resultado);
            verify(pedidoRepository, times(1)).findAll(any(Specification.class), eq(pageablePadrao));
        }
    }
}