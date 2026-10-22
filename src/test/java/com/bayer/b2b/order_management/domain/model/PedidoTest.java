package com.bayer.b2b.order_management.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Suite de testes unitários para a Raiz de Agregação Pedido.
 * <p>
 * O foco desta classe é atestar o comportamento do Aggregate Root do DDD:
 * 1. Garantia de Invariantes (Cálculos de total sempre consistentes com os itens).
 * 2. Proteção de Fronteiras (Coleções imutáveis).
 * 3. Delegação de Estado (Uso correto da Máquina de Estados StatusPedido).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Domínio: Pedido (Aggregate Root)")
class PedidoTest {

    // Mock do parceiro para isolamento estrito (Não testamos o Parceiro aqui, apenas a relação com ele)
    private Parceiro parceiroMock;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        parceiroMock = mock(Parceiro.class);
        pedido = new Pedido(parceiroMock);
    }

    @Nested
    @DisplayName("1. Instanciação e Geração de Identidade (Fail-Fast)")
    class ConstrutorTests {

        @Test
        @DisplayName("Deve instanciar a Raiz de Agregação de forma consistente")
        void deveCriarPedidoComSucesso() {
            assertNotNull(pedido.getId(), "A Identidade (UUID) deve ser gerada na criação do objeto em memória");
            assertEquals(parceiroMock, pedido.getParceiro(), "O Aggregate deve reter a referência ao cliente B2B");
            assertEquals(StatusPedido.PENDENTE, pedido.getStatus(), "Todo pedido deve nascer estritamente como PENDENTE");
            assertEquals(BigDecimal.ZERO, pedido.getValorTotal(), "O valor inicial de um pedido sem itens deve ser ZERO");
            assertTrue(pedido.getItens().isEmpty(), "A coleção de itens deve nascer inicializada, porém vazia");
        }

        @Test
        @DisplayName("Deve lançar NullPointerException ao tentar criar pedido sem Parceiro atrelado")
        void deveFalharQuandoParceiroForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new Pedido(null)
            );
            assertEquals("O parceiro do pedido não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve cobrir a instanciação do construtor protegido exigido pelo Hibernate")
        void deveCobrirConstrutorProtegido() {
            Pedido pedidoVazio = new Pedido();
            assertNotNull(pedidoVazio, "O framework ORM deve conseguir criar instâncias via Reflection");
            assertNull(pedidoVazio.getId());
            assertNull(pedidoVazio.getParceiro());
            // A coleção de itens é inicializada na declaração do atributo (new HashSet<>())
            assertNotNull(pedidoVazio.getItens());
        }
    }

    @Nested
    @DisplayName("2. Gestão de Estado Logístico (State Machine Delegate)")
    class TransitarStatusTests {

        @Test
        @DisplayName("Deve avançar o status com sucesso utilizando os métodos explícitos de intenção")
        void deveTransitarStatusQuandoValido() {
            // Act & Assert: Fluxo Logístico Feliz
            pedido.aprovar();
            assertEquals(StatusPedido.APROVADO, pedido.getStatus());

            pedido.processar();
            assertEquals(StatusPedido.EM_PROCESSAMENTO, pedido.getStatus());

            pedido.enviar();
            assertEquals(StatusPedido.ENVIADO, pedido.getStatus());

            pedido.entregar();
            assertEquals(StatusPedido.ENTREGUE, pedido.getStatus());
        }

        @Test
        @DisplayName("Deve barrar e proteger o estado atual se a intenção acionar uma transição ilegal")
        void deveFalharQuandoTransicaoForInvalida() {
            // Arrange (Pedido já nasce PENDENTE)

            // Act & Assert (Pendente -> Entregue é proibido. O método entregar() força transição para ENTREGUE)
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    pedido.entregar()
            );

            assertEquals("Violação da Máquina de Estados: O pedido não pode transitar de PENDENTE para ENTREGUE", exception.getMessage());

            // Verificação Arquitetural: O estado do pedido em memória NÃO PODE ter sido corrompido
            assertEquals(StatusPedido.PENDENTE, pedido.getStatus(), "A tentativa falha de transição não deve mutar o status atual");
        }

        @Test
        @DisplayName("Deve permitir o cancelamento do pedido e bloquear mutações posteriores")
        void devePermitirCancelamentoEBloquearNovasAcoes() {
            // Act: PENDENTE -> CANCELADO
            pedido.cancelar();

            // Assert
            assertEquals(StatusPedido.CANCELADO, pedido.getStatus());

            // Act & Assert: Um pedido morto não pode ser ressuscitado
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    pedido.aprovar()
            );
            assertEquals("Violação da Máquina de Estados: O pedido não pode transitar de CANCELADO para APROVADO", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("3. Agregação e Integridade Financeira (Adição de Itens)")
    class AdicionarItemTests {

        @Test
        @DisplayName("Deve adicionar múltiplos itens e somar progressivamente o Valor Total do Aggregate")
        void deveAdicionarItemESomarValor() {
            // Arrange: Isolamos o item mockando-o. O foco é se o Pedido extrai e soma o "Preço Total" do item.
            ItemPedido item1 = mock(ItemPedido.class);
            when(item1.getPrecoTotal()).thenReturn(new BigDecimal("100.50"));

            ItemPedido item2 = mock(ItemPedido.class);
            when(item2.getPrecoTotal()).thenReturn(new BigDecimal("49.50"));

            // Act
            pedido.adicionarItem(item1);
            pedido.adicionarItem(item2);

            // Assert
            assertEquals(2, pedido.getItens().size(), "A coleção interna deve conter os dois itens adicionados");

            // A soma matemática perfeita deve ser 150.00
            assertEquals(0, new BigDecimal("150.00").compareTo(pedido.getValorTotal()),
                    "A Raiz de Agregação deve manter a soma financeira estritamente sincronizada com os itens");
        }

        @Test
        @DisplayName("Deve lançar NullPointerException ao tentar adicionar um item nulo")
        void deveFalharQuandoItemForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    pedido.adicionarItem(null)
            );
            assertEquals("O item a ser adicionado não pode ser nulo", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException ao incrementar valor total nulo diretamente")
        void deveFalharQuandoIncrementoForNulo() {
            assertThrows(NullPointerException.class, () ->
                    pedido.incrementarValorTotal(null)
            );
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException se o incremento for negativo ou zero")
        void deveFalharQuandoIncrementoForZeroOuNegativo() {
            assertThrows(IllegalArgumentException.class, () -> pedido.incrementarValorTotal(BigDecimal.ZERO));
            assertThrows(IllegalArgumentException.class, () -> pedido.incrementarValorTotal(new BigDecimal("-10.00")));
        }
    }

    @Nested
    @DisplayName("4. Proteção de Fronteiras (Imutabilidade de Coleções)")
    class ImutabilidadeTests {

        @Test
        @DisplayName("Deve ejetar modificações não autorizadas na coleção de itens via getter")
        void getItensDeveRetornarColecaoImutavel() {
            // Arrange
            Set<ItemPedido> itensReadOnly = pedido.getItens();
            ItemPedido itemFantasma = mock(ItemPedido.class);

            // Act & Assert
            // Prova arquitetural de que desenvolvedores não podem usar pedido.getItens().add(...)
            // para burlar o cálculo do valor total.
            assertThrows(UnsupportedOperationException.class, () -> itensReadOnly.add(itemFantasma));
            assertThrows(UnsupportedOperationException.class, itensReadOnly::clear);
        }
    }

    @Nested
    @DisplayName("5. Auditoria de Tempo (JPA Callbacks)")
    class AuditoriaJpaTests {

        @Test
        @DisplayName("O callback @PrePersist deve carimbar as datas de criação e atualização")
        void deveConfigurarDatasNoPrePersist() {
            // Arrange
            assertNull(pedido.getDataCriacao());
            assertNull(pedido.getDataUltimaAtualizacao());

            // Act
            pedido.onCreate(); // Simulando a chamada que o Hibernate faria nativamente

            // Assert
            assertNotNull(pedido.getDataCriacao());
            assertNotNull(pedido.getDataUltimaAtualizacao());
            assertEquals(pedido.getDataCriacao(), pedido.getDataUltimaAtualizacao(),
                    "No momento do INSERT, a data de criação e atualização devem ser exatamente as mesmas");
        }

        @Test
        @DisplayName("O callback @PreUpdate deve modificar estritamente a data de atualização")
        void deveAtualizarDataNoPreUpdate() throws InterruptedException {
            // Arrange
            pedido.onCreate();
            Instant criacaoOriginal = pedido.getDataCriacao();

            // Pausa minúscula apenas para o relógio do sistema (Instant.now()) gerar uma fração de milissegundo de diferença
            Thread.sleep(1);

            // Act
            pedido.onUpdate(); // Simulando a chamada do Hibernate durante um UPDATE

            // Assert
            assertEquals(criacaoOriginal, pedido.getDataCriacao(), "A data de criação é imutável após o primeiro persist");
            assertTrue(pedido.getDataUltimaAtualizacao().isAfter(criacaoOriginal),
                    "A data de última atualização deve refletir estritamente o timestamp do update");
        }
    }

    @Nested
    @DisplayName("6. Contratos Básicos: Equals e HashCode")
    class ContratosBasicosTests {

        @Test
        @DisplayName("Equals e HashCode devem operar exclusivamente via UUID")
        void equalsEHashCodeDevemRespeitarUuid() {
            Pedido pedido2 = new Pedido(parceiroMock);

            // Cada "new Pedido" gera um novo UUID.randomUUID()
            assertNotEquals(pedido, pedido2, "Pedidos diferentes geram identidades diferentes");
            assertNotEquals(pedido.hashCode(), pedido2.hashCode());

            assertEquals(pedido, pedido, "Reflexividade do equals");
            assertNotEquals(pedido, null);
            assertNotEquals(pedido, new Object());
        }
    }
}