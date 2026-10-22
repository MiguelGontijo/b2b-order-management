package com.bayer.b2b.order_management.infrastructure.database;

import com.bayer.b2b.order_management.application.usecase.PedidoFiltro;
import com.bayer.b2b.order_management.domain.model.ItemPedido;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de Testes de Integração para a interface PedidoRepository.
 * <p>
 * O foco desta classe é validar:
 * 1. O mapeamento ORM da Raiz de Agregação e sua cascata de persistência (CascadeType.ALL).
 * 2. A integridade sintática das consultas HQL (Otimização de Leitura com JOIN FETCH).
 * </p>
 * <p>
 * Utiliza @DataJpaTest para subir uma fatia (slice) do contexto do Spring
 * executando cada teste dentro de uma transação isolada com rollback automático.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DataJpaTest
@DisplayName("Integração: PedidoRepository")
class PedidoRepositoryIT {

    @Autowired
    private PedidoRepository pedidoRepository;

    /**
     * Motor de controle manual do contexto de persistência.
     * Vital para preparar cenários e limpar a memória (L1 Cache) do Hibernate.
     */
    @Autowired
    private TestEntityManager entityManager;

    private Parceiro parceiroBase;

    /**
     * O Pedido tem um relacionamento estrito e não nulo com o Parceiro.
     * Precisamos garantir que existe um Parceiro no banco de dados antes de cada teste,
     * caso contrário o PostgreSQL negará o INSERT do Pedido (Violação de FK).
     */
    @BeforeEach
    void setUp() {
        Parceiro parceiro = new Parceiro("Bayer Sul", "33333333333333", new BigDecimal("50000.00"));
        // PersistAndFlush garante que a entidade já tenha recebido seu ID (Primary Key) no banco
        this.parceiroBase = entityManager.persistAndFlush(parceiro);
    }

    @Nested
    @DisplayName("1. Persistência Base e Cascata (Aggregate Root)")
    class PersistenciaCascataTests {

        @Test
        @DisplayName("Deve salvar o Pedido e ejetar o INSERT dos itens automaticamente (CascadeType.ALL)")
        void deveSalvarPedidoEItensEmCascata() {
            // Arrange - Prepara a agregação na memória
            Pedido pedido = new Pedido(parceiroBase);
            ItemPedido item1 = new ItemPedido(pedido, "PROD-A", 2, new BigDecimal("50.00")); // Total: 100
            ItemPedido item2 = new ItemPedido(pedido, "PROD-B", 1, new BigDecimal("100.00")); // Total: 100
            pedido.adicionarItem(item1);
            pedido.adicionarItem(item2); // Total do pedido: 200

            // Act - O repositório só conhece o Pedido, ele não conhece o repositório de itens
            Pedido salvo = pedidoRepository.save(pedido);

            // Forçamos o commit das SQLs no banco e limpamos a memória do ORM
            entityManager.flush();
            entityManager.clear();

            // Assert
            Optional<Pedido> recuperado = pedidoRepository.findById(salvo.getId());
            assertTrue(recuperado.isPresent(), "O pedido deve ter sido salvo e recuperado do banco");

            Pedido pedidoDB = recuperado.get();
            assertEquals(2, pedidoDB.getItens().size(), "A cascata deve ter persistido os 2 itens subordinados");

            // Valida a integridade financeira atestando o mapeamento do precision/scale do banco
            assertEquals(0, new BigDecimal("200.00").compareTo(pedidoDB.getValorTotal()),
                    "O valor total agregado deve ter sido persistido com precisão exata");
        }
    }

    @Nested
    @DisplayName("2. Otimização de Leitura (N+1 Mitigation)")
    class JoinFetchTests {

        @Test
        @DisplayName("Deve executar a query HQL com JOIN FETCH sem erros de sintaxe e hidratar as coleções")
        void deveBuscarPedidoComItensPreCarregados() {
            // Arrange - Insere dados físicos no banco de testes
            Pedido pedido = new Pedido(parceiroBase);
            pedido.adicionarItem(new ItemPedido(pedido, "PROD-X", 10, new BigDecimal("5.00")));
            Pedido salvo = entityManager.persistAndFlush(pedido);

            // CRÍTICO: Limpa a memória. Se não fizermos isso, o findByIdWithItens vai devolver o objeto
            // da RAM e o teste não validará a nossa sintaxe SQL customizada.
            entityManager.clear();

            // Act
            Optional<Pedido> recuperado = pedidoRepository.findByIdWithItens(salvo.getId());

            // Assert
            assertTrue(recuperado.isPresent(), "A query customizada deve conseguir localizar o registro pelo UUID");

            Pedido pedidoDB = recuperado.get();
            assertFalse(pedidoDB.getItens().isEmpty(), "O JOIN FETCH deve ter preenchido a coleção LAZY na mesma viagem ao banco");
            assertEquals(1, pedidoDB.getItens().size());
            assertEquals("PROD-X", pedidoDB.getItens().iterator().next().getProdutoId());
        }

        @Test
        @DisplayName("Deve retornar Optional.empty() quando o UUID não existir no banco")
        void deveRetornarVazioQuandoNaoExistir() {
            // Act
            Optional<Pedido> recuperado = pedidoRepository.findByIdWithItens(UUID.randomUUID());

            // Assert
            assertTrue(recuperado.isEmpty(), "Queries customizadas devem respeitar o contrato do Optional ao não encontrar dados");
        }
    }

    @Nested
    @DisplayName("3. Consultas Dinâmicas e Paginação (Specification)")
    class SpecificationTests {

        @Test
        @DisplayName("Deve retornar todos os pedidos paginados quando o filtro for nulo (Predicate vazio)")
        void deveRetornarTodosQuandoFiltroNulo() {
            // Arrange - Prepara a massa de dados
            Pedido pedido1 = new Pedido(parceiroBase);
            Pedido pedido2 = new Pedido(parceiroBase);
            entityManager.persist(pedido1);
            entityManager.persistAndFlush(pedido2);
            entityManager.clear();

            Specification<Pedido> spec = PedidoSpecification.comFiltros(null);
            PageRequest pageable = PageRequest.of(0, 10); // Página 0, limite 10

            // Act
            Page<Pedido> resultado = pedidoRepository.findAll(spec, pageable);

            // Assert
            assertNotNull(resultado, "O objeto Page não pode ser nulo");
            assertEquals(2, resultado.getTotalElements(), "Deve encontrar os 2 pedidos criados na base isolada");
        }

        @Test
        @DisplayName("Deve montar a cláusula WHERE corretamente e filtrar por Status")
        void deveFiltrarPorStatus() {
            // Arrange - Usa Reflection para forçar um status imutável simulando um pedido em andamento
            Pedido pedido = new Pedido(parceiroBase);
            ReflectionTestUtils.setField(pedido, "status", StatusPedido.APROVADO);
            entityManager.persistAndFlush(pedido);
            entityManager.clear();

            PedidoFiltro filtro = new PedidoFiltro(StatusPedido.APROVADO, null, null);
            Specification<Pedido> spec = PedidoSpecification.comFiltros(filtro);

            // Act
            Page<Pedido> resultado = pedidoRepository.findAll(spec, PageRequest.of(0, 10));

            // Assert
            assertFalse(resultado.isEmpty(), "O repositório deve encontrar o registro com o status alvo");
            assertEquals(StatusPedido.APROVADO, resultado.getContent().get(0).getStatus(),
                    "O status recuperado do banco deve bater com o filtro aplicado");
        }

        @Test
        @DisplayName("Deve montar a cláusula WHERE corretamente e filtrar pelo range de Datas (Between)")
        void deveFiltrarPorDataDeCriacao() {
            // Arrange - Salva o registro normalmente (o Hibernate vai gerar a data de hoje)
            Pedido pedidoAntigo = new Pedido(parceiroBase);
            entityManager.persistAndFlush(pedidoAntigo);

            // Força a alteração histórica direto no banco (SQL Nativo), ignorando as travas do JPA
            Instant dataPassada = Instant.now().minus(10, ChronoUnit.DAYS);
            entityManager.getEntityManager()
                    .createNativeQuery("UPDATE pedidos SET data_criacao = :data WHERE id = :id")
                    .setParameter("data", dataPassada)
                    .setParameter("id", pedidoAntigo.getId())
                    .executeUpdate();

            // Limpa o cache L1 do Hibernate para forçar a releitura do disco com a data alterada
            entityManager.clear();

            // Configura o filtro cercando a data do pedido (de 15 dias atrás até 5 dias atrás)
            Instant inicio = Instant.now().minus(15, ChronoUnit.DAYS);
            Instant fim = Instant.now().minus(5, ChronoUnit.DAYS);
            PedidoFiltro filtro = new PedidoFiltro(null, inicio, fim);

            Specification<Pedido> spec = PedidoSpecification.comFiltros(filtro);

            // Act
            Page<Pedido> resultado = pedidoRepository.findAll(spec, PageRequest.of(0, 10));

            // Assert
            assertFalse(resultado.isEmpty(), "Deve encontrar o pedido que está dentro do limite temporal");
            assertTrue(resultado.getContent().stream()
                            .allMatch(p -> !p.getDataCriacao().isBefore(inicio) && !p.getDataCriacao().isAfter(fim)),
                    "Garante que o banco aplicou as restrições greaterThanOrEqualTo e lessThanOrEqualTo com precisão");
        }
    }
}