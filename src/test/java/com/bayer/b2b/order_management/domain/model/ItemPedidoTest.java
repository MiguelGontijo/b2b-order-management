package com.bayer.b2b.order_management.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Suite de testes unitários para a Entidade Local ItemPedido.
 * <p>
 * O foco desta classe é validar a integridade matemática, o padrão Fail-Fast
 * e a blindagem contra fraudes (quantidades/valores zerados ou negativos).
 * O framework Mockito é utilizado para simular a Raiz de Agregação (Pedido),
 * garantindo o isolamento estrito deste teste de unidade.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Domínio: ItemPedido")
class ItemPedidoTest {

    // Mock da Raiz de Agregação para isolar o teste
    private final Pedido pedidoMock = mock(Pedido.class);

    @Nested
    @DisplayName("1. Instanciação e Cálculos Financeiros (Happy Path)")
    class SucessoTests {

        @Test
        @DisplayName("Deve instanciar o item, gerar UUID e calcular o preço total corretamente")
        void deveCriarItemECalcularTotal() {
            // Arrange
            String produtoId = "PROD-999";
            Integer quantidade = 3;
            BigDecimal precoUnitario = new BigDecimal("15.50");

            // O valor total matemático esperado é 3 * 15.50 = 46.50
            BigDecimal precoTotalEsperado = new BigDecimal("46.50");

            // Act
            ItemPedido item = new ItemPedido(pedidoMock, produtoId, quantidade, precoUnitario);

            // Assert
            assertNotNull(item.getId(), "A entidade deve gerar sua identidade (UUID) no nascimento");
            assertEquals(pedidoMock, item.getPedido(), "O vínculo com a Raiz de Agregação deve ser mantido");
            assertEquals(produtoId, item.getProdutoId());
            assertEquals(quantidade, item.getQuantidade());
            assertEquals(precoUnitario, item.getPrecoUnitario());

            // Verificação Financeira de Alta Precisão (Ignora divergências de escala como 46.5 vs 46.50)
            assertEquals(0, precoTotalEsperado.compareTo(item.getPrecoTotal()),
                    "O cálculo do preço total (quantidade * valor unitário) deve ser exato");
        }
    }

    @Nested
    @DisplayName("2. Validações de Integridade (Fail-Fast Null Checks)")
    class FailFastNullTests {

        @Test
        @DisplayName("Deve lançar NullPointerException se o Pedido pai for nulo")
        void deveFalharQuandoPedidoForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new ItemPedido(null, "PROD-123", 1, new BigDecimal("10.00"))
            );
            assertEquals("O pedido pai não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se o ID do Produto for nulo")
        void deveFalharQuandoProdutoIdForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new ItemPedido(pedidoMock, null, 1, new BigDecimal("10.00"))
            );
            assertEquals("O ID do produto não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se a Quantidade for nula")
        void deveFalharQuandoQuantidadeForNula() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", null, new BigDecimal("10.00"))
            );
            assertEquals("A quantidade não pode ser nula", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException se o Preço Unitário for nulo")
        void deveFalharQuandoPrecoUnitarioForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", 1, null)
            );
            assertEquals("O preço unitário não pode ser nulo", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("3. Regras de Negócio e Prevenção de Fraudes")
    class RegrasNegocioTests {

        @Test
        @DisplayName("Deve lançar IllegalArgumentException se a quantidade for ZERO")
        void deveFalharQuandoQuantidadeForZero() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", 0, new BigDecimal("10.00"))
            );
            assertEquals("A quantidade do item deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException se a quantidade for NEGATIVA")
        void deveFalharQuandoQuantidadeForNegativa() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", -5, new BigDecimal("10.00"))
            );
            assertEquals("A quantidade do item deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException se o preço unitário for ZERO")
        void deveFalharQuandoPrecoUnitarioForZero() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", 1, BigDecimal.ZERO)
            );
            assertEquals("O preço unitário deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException se o preço unitário for NEGATIVO")
        void deveFalharQuandoPrecoUnitarioForNegativo() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new ItemPedido(pedidoMock, "PROD-123", 1, new BigDecimal("-1.50"))
            );
            assertEquals("O preço unitário deve ser maior que zero", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("4. Contratos Básicos: Equals, HashCode e Construtor JPA")
    class ContratosBasicosTests {

        @Test
        @DisplayName("Equals e HashCode devem ser baseados unicamente no UUID do item")
        void equalsEHashCodeDevemRespeitarIdentidade() {
            // Arrange
            ItemPedido item1 = new ItemPedido(pedidoMock, "PROD-1", 1, new BigDecimal("10.00"));
            ItemPedido item2 = new ItemPedido(pedidoMock, "PROD-1", 1, new BigDecimal("10.00"));

            // Act & Assert
            // Como cada chamada ao construtor gera um UUID.randomUUID() novo, instâncias diferentes NUNCA serão iguais
            assertNotEquals(item1, item2, "Itens distintos devem gerar UUIDs distintos");
            assertNotEquals(item1.hashCode(), item2.hashCode());

            // Garantia de contrato básico do Java
            assertEquals(item1, item1, "O item deve ser igual a si mesmo");
            assertNotEquals(null, item1, "O item não pode ser igual a nulo");
            assertNotEquals(item1, new Object(), "O item não pode ser igual a outra classe");
        }

        @Test
        @DisplayName("Deve cobrir a instanciação do construtor protegido exigido pelo JPA")
        void deveCobrirConstrutorProtegido() {
            // Act
            ItemPedido itemVazio = new ItemPedido();

            // Assert
            assertNotNull(itemVazio, "A instância vazia deve ser criada para atender ao contrato do framework");
            assertNull(itemVazio.getId());
            assertNull(itemVazio.getPedido());
        }
    }
}