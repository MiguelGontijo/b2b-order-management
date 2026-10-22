package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.domain.execption.ResourceNotFoundException;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para a orquestração de consulta de Pedidos.
 * <p>
 * Decisões Arquiteturais de Teste:
 * 1. Isolamento Absoluto: Não subimos o contexto do Spring (@SpringBootTest).
 *    Toda a infraestrutura do banco de dados é simulada via Mockito.
 * 2. Proteção de Borda: Garantimos que falhas de validação (Fail-Fast)
 *    ocorram ANTES de acionar a infraestrutura, poupando I/O.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Application/UseCase: BuscarPedidoPorId")
class BuscarPedidoPorIdUseCaseTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @InjectMocks
    private BuscarPedidoPorIdUseCase useCase;

    private UUID pedidoIdAlvo;
    private Pedido pedidoMock;

    @BeforeEach
    void setUp() {
        // Preparação do cenário de dados simulados em memória
        pedidoIdAlvo = UUID.randomUUID();
        Parceiro parceiro = new Parceiro("Bayer Agro Testes", "11111111111111", new BigDecimal("50000.00"));
        pedidoMock = new Pedido(parceiro);
    }

    @Nested
    @DisplayName("1. Fail-Fast e Validação de Entrada")
    class FailFastTests {

        @Test
        @DisplayName("Deve rejeitar a consulta e estourar NullPointerException se o ID fornecido for nulo")
        void deveFalharQuandoIdForNulo() {
            // Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(null)
            );

            // Validando a mensagem exata para facilitar o trace de logs no Splunk/Datadog
            assertEquals("O identificador do pedido não pode ser nulo para a consulta", exception.getMessage());

            // Garantia Sênior: A infraestrutura jamais pode ser tocada se os dados de entrada forem inválidos
            verifyNoInteractions(pedidoRepository);
        }
    }

    @Nested
    @DisplayName("2. Regras de Negócio e Respostas de Infraestrutura")
    class RegrasDeNegocioTests {

        @Test
        @DisplayName("Deve extrair a entidade hidratada com sucesso quando o repositório localizar o ID")
        void deveRetornarPedidoQuandoLocalizadoComSucesso() {
            // Arrange (Mock da infraestrutura devolvendo o pedido)
            when(pedidoRepository.findByIdWithItens(pedidoIdAlvo)).thenReturn(Optional.of(pedidoMock));

            // Act
            Pedido pedidoRecuperado = useCase.execute(pedidoIdAlvo);

            // Assert
            assertNotNull(pedidoRecuperado);
            assertEquals(pedidoMock.getParceiro().getCnpj(), pedidoRecuperado.getParceiro().getCnpj());

            // Garantir que a query otimizada (com JOIN FETCH) foi a utilizada
            verify(pedidoRepository, times(1)).findByIdWithItens(pedidoIdAlvo);
        }

        @Test
        @DisplayName("Deve estourar ResourceNotFoundException quando o banco de dados retornar vazio (Garantia de 404)")
        void deveLancarExcecaoQuandoPedidoNaoExistir() {
            // Arrange (Mock simulando que o banco fez a busca e não achou nada)
            when(pedidoRepository.findByIdWithItens(pedidoIdAlvo)).thenReturn(Optional.empty());

            // Act & Assert
            ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () ->
                    useCase.execute(pedidoIdAlvo)
            );

            assertTrue(exception.getMessage().contains(pedidoIdAlvo.toString()));
            verify(pedidoRepository, times(1)).findByIdWithItens(pedidoIdAlvo);
        }
    }
}