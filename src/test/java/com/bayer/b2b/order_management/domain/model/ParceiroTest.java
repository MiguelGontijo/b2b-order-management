package com.bayer.b2b.order_management.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de testes unitários para a entidade de domínio Parceiro.
 * <p>
 * O foco desta classe é garantir a integridade absoluta do Modelo de Domínio Rico (Rich Domain Model).
 * Foram aplicadas técnicas de Boundary Value Analysis (Análise de Valor Limite) e
 * Equivalence Partitioning para garantir 100% de cobertura de linhas e ramificações (Branch Coverage).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Domínio: Parceiro")
class ParceiroTest {

    @Nested
    @DisplayName("1. Instanciação e Fail-Fast (Construtores)")
    class ConstrutorTests {

        @Test
        @DisplayName("Deve criar o parceiro com sucesso quando todos os dados e limites forem válidos")
        void deveCriarParceiroComSucesso() {
            // Arrange
            String nome = "Bayer Crop Science";
            String cnpj = "12345678901234";
            BigDecimal limite = new BigDecimal("10000.00");

            // Act
            Parceiro parceiro = new Parceiro(nome, cnpj, limite);

            // Assert
            assertNull(parceiro.getId(), "O ID deve ser nulo antes da persistência do JPA");
            assertEquals(nome, parceiro.getNome());
            assertEquals(cnpj, parceiro.getCnpj());
            assertEquals(limite, parceiro.getLimiteCredito());
        }

        @Test
        @DisplayName("Deve permitir a criação de um parceiro com limite de crédito exatamente igual a zero")
        void deveCriarParceiroComLimiteZero() {
            // Arrange
            BigDecimal limiteZero = BigDecimal.ZERO;

            // Act
            Parceiro parceiro = new Parceiro("Parceiro Sem Limite", "00000000000000", limiteZero);

            // Assert
            assertEquals(limiteZero, parceiro.getLimiteCredito());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o nome for nulo")
        void deveFalharQuandoNomeForNulo() {
            // Arrange & Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new Parceiro(null, "12345678901234", new BigDecimal("1000.00"))
            );
            assertEquals("O nome do parceiro não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o CNPJ for nulo")
        void deveFalharQuandoCnpjForNulo() {
            // Arrange & Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new Parceiro("Bayer Crop Science", null, new BigDecimal("1000.00"))
            );
            assertEquals("O CNPJ não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o limite de crédito for nulo")
        void deveFalharQuandoLimiteForNulo() {
            // Arrange & Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new Parceiro("Bayer Crop Science", "12345678901234", null)
            );
            assertEquals("O limite de crédito inicial não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando o limite de crédito for negativo")
        void deveFalharQuandoLimiteForNegativo() {
            // Arrange
            BigDecimal limiteNegativo = new BigDecimal("-0.01");

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new Parceiro("Bayer Crop Science", "12345678901234", limiteNegativo)
            );
            assertEquals("O limite de crédito não pode ser negativo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve cobrir a instanciação do construtor protegido exigido pelo JPA")
        void deveCobrirConstrutorProtegido() {
            // Act
            Parceiro parceiro = new Parceiro();

            // Assert
            assertNotNull(parceiro, "A instância vazia deve ser criada para atender ao contrato do framework");
            assertNull(parceiro.getId());
            assertNull(parceiro.getNome());
            assertNull(parceiro.getCnpj());
            assertNull(parceiro.getLimiteCredito());
        }
    }

    @Nested
    @DisplayName("2. Regra de Negócio: Débito de Crédito")
    class DebitarCreditoTests {

        private Parceiro parceiro;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            parceiro = new Parceiro("Bayer", "12345678901234", new BigDecimal("5000.00"));
        }

        @Test
        @DisplayName("Deve deduzir o valor com sucesso quando o débito for menor que o limite total")
        void deveDebitarValorMenorQueLimite() {
            // Arrange
            BigDecimal valorDebito = new BigDecimal("2000.00");
            BigDecimal limiteEsperado = new BigDecimal("3000.00");

            // Act
            parceiro.debitarCredito(valorDebito);

            // Assert
            assertEquals(0, limiteEsperado.compareTo(parceiro.getLimiteCredito()), "O limite restante deve ser a subtração exata");
        }

        @Test
        @DisplayName("Deve deduzir o valor com sucesso quando o débito zerar exatamente o limite total")
        void deveDebitarValorExatamenteIgualAoLimite() {
            // Arrange
            BigDecimal valorDebito = new BigDecimal("5000.00");

            // Act
            parceiro.debitarCredito(valorDebito);

            // Assert
            assertEquals(0, BigDecimal.ZERO.compareTo(parceiro.getLimiteCredito()), "O limite deve ser zerado com sucesso");
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando tentar debitar valor nulo")
        void deveFalharQuandoDebitoForNulo() {
            // Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    parceiro.debitarCredito(null)
            );
            assertEquals("O valor de débito não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando tentar debitar valor zero")
        void deveFalharQuandoDebitoForZero() {
            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    parceiro.debitarCredito(BigDecimal.ZERO)
            );
            assertEquals("O valor de débito deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando tentar debitar valor negativo")
        void deveFalharQuandoDebitoForNegativo() {
            // Arrange
            BigDecimal valorNegativo = new BigDecimal("-100.00");

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    parceiro.debitarCredito(valorNegativo)
            );
            assertEquals("O valor de débito deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException e proteger o estado interno quando saldo for insuficiente")
        void deveFalharQuandoDebitoForMaiorQueLimite() {
            // Arrange
            BigDecimal valorDebito = new BigDecimal("5000.01"); // Estoura o limite de 5000.00

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    parceiro.debitarCredito(valorDebito)
            );
            assertEquals("Crédito insuficiente para o parceiro: 12345678901234", exception.getMessage());

            // Assert Estado Intacto
            assertEquals(0, new BigDecimal("5000.00").compareTo(parceiro.getLimiteCredito()),
                    "O limite de crédito NÃO DEVE sofrer mutação se a transação de negócio falhar");
        }
    }

    @Nested
    @DisplayName("3. Contratos Básicos: Equals, HashCode e ToString")
    class ContratosBasicosTests {

        private Parceiro parceiroA;
        private Parceiro parceiroB_MesmoCnpj;
        private Parceiro parceiroC_CnpjDiferente;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            parceiroA = new Parceiro("Bayer Alpha", "11111111111111", new BigDecimal("1000.00"));
            parceiroB_MesmoCnpj = new Parceiro("Bayer Beta", "11111111111111", new BigDecimal("2000.00"));
            parceiroC_CnpjDiferente = new Parceiro("Bayer Gama", "22222222222222", new BigDecimal("1000.00"));
        }

        @Test
        @DisplayName("Equals deve retornar true para instâncias em memória que apontem para a mesma referência")
        void equalsDeveRetornarTrueParaMesmaReferencia() {
            assertTrue(parceiroA.equals(parceiroA));
        }

        @Test
        @DisplayName("Equals deve retornar false quando comparado com objeto nulo")
        void equalsDeveRetornarFalseParaNulo() {
            assertFalse(parceiroA.equals(null));
        }

        @Test
        @DisplayName("Equals deve retornar false quando comparado com classe diferente")
        void equalsDeveRetornarFalseParaClasseDiferente() {
            Object objDiferente = new Object();
            assertFalse(parceiroA.equals(objDiferente));
        }

        @Test
        @DisplayName("Equals e HashCode devem se basear estritamente na chave de negócio (CNPJ)")
        void equalsEHashCodeDevemRespeitarChaveDeNegocio() {
            // Act & Assert
            assertTrue(parceiroA.equals(parceiroB_MesmoCnpj), "Parceiros com o mesmo CNPJ são a mesma entidade");
            assertEquals(parceiroA.hashCode(), parceiroB_MesmoCnpj.hashCode(), "HashCodes devem colidir para CNPJs iguais");

            assertFalse(parceiroA.equals(parceiroC_CnpjDiferente), "Parceiros com CNPJs diferentes são entidades distintas");
            assertNotEquals(parceiroA.hashCode(), parceiroC_CnpjDiferente.hashCode(), "HashCodes devem diferir para CNPJs diferentes");
        }

        @Test
        @DisplayName("ToString deve imprimir corretamente os atributos sensíveis da entidade")
        void toStringDeveImprimirAtributosCorretamente() {
            // Act
            String resultado = parceiroA.toString();

            // Assert
            assertTrue(resultado.contains("nome='Bayer Alpha'"));
            assertTrue(resultado.contains("cnpj='11111111111111'"));
            assertTrue(resultado.contains("limiteCredito=1000.00"));
        }
    }

    @Nested
    @DisplayName("4. Regra de Negócio: Estorno de Crédito")
    class EstornarCreditoTests {

        private Parceiro parceiro;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            parceiro = new Parceiro("Bayer", "12345678901234", new BigDecimal("5000.00"));
        }

        @Test
        @DisplayName("Deve devolver o valor ao limite do parceiro com precisão matemática")
        void deveEstornarValorComSucesso() {
            // Arrange
            BigDecimal valorEstorno = new BigDecimal("1500.00");
            BigDecimal limiteEsperado = new BigDecimal("6500.00");

            // Act
            parceiro.estornarCredito(valorEstorno);

            // Assert
            assertEquals(0, limiteEsperado.compareTo(parceiro.getLimiteCredito()),
                    "O limite de crédito deve ser a soma exata do saldo anterior com o valor estornado");
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando tentar estornar valor nulo")
        void deveFalharQuandoEstornoForNulo() {
            // Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    parceiro.estornarCredito(null)
            );
            assertEquals("O valor de estorno não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando tentar estornar valor zero")
        void deveFalharQuandoEstornoForZero() {
            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    parceiro.estornarCredito(BigDecimal.ZERO)
            );
            assertEquals("O valor de estorno deve ser maior que zero", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException e proteger o estado interno quando tentar estornar valor negativo")
        void deveFalharQuandoEstornoForNegativo() {
            // Arrange
            BigDecimal valorNegativo = new BigDecimal("-100.00");

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    parceiro.estornarCredito(valorNegativo)
            );
            assertEquals("O valor de estorno deve ser maior que zero", exception.getMessage());

            // Assert Estado Intacto (Garantia de que o limite original não foi adulterado pela tentativa falha)
            assertEquals(0, new BigDecimal("5000.00").compareTo(parceiro.getLimiteCredito()));
        }
    }
}