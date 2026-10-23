package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de testes unitários para o DTO de Evento PedidoStatusChangedEvent.
 * <p>
 * Decisões Arquiteturais Classe Ouro (Fase 3):
 * O foco desta classe é atestar o contrato de serialização para a arquitetura Transactional Outbox.
 * Validamos a imutabilidade do Record e a fidelidade da tradução realizada pelo
 * Factory Method (from), garantindo que o novo payload enriquecido (eventId, schemaVersion,
 * versaoPedido) seja gerado perfeitamente para garantir idempotência nos microsserviços vizinhos.
 * </p>
 *
 * @author Miguel
 * @version 2.0
 */
@DisplayName("Infraestrutura/Mensageria: PedidoStatusChangedEvent")
class PedidoStatusChangedEventTest {

    @Nested
    @DisplayName("1. Instanciação Direta e Imutabilidade (Records)")
    class InstanciacaoRecordTests {

        @Test
        @DisplayName("Deve criar o evento diretamente com sucesso preenchendo o novo contrato enriquecido")
        void deveCriarEventoDiretamente() {
            // Arrange
            UUID eventId = UUID.randomUUID();
            UUID pedidoId = UUID.randomUUID();
            String eventType = "PEDIDO_APROVADO";
            String schemaVersion = "v1.0";
            String codigoPedidoOrigem = "ERP-999";
            String cnpj = "12345678901234";
            String statusAnterior = "PENDENTE";
            String statusNovo = "APROVADO";
            BigDecimal valorTotal = new BigDecimal("1500.00");
            Long versaoPedido = 1L;
            Instant agora = Instant.now();

            // Act - Utilizando a nova assinatura do Record (Fase 3)
            PedidoStatusChangedEvent evento = new PedidoStatusChangedEvent(
                    eventId, eventType, schemaVersion, pedidoId, codigoPedidoOrigem,
                    cnpj, statusAnterior, statusNovo, valorTotal, versaoPedido, agora
            );

            // Assert
            assertNotNull(evento);
            assertEquals(eventId, evento.eventId());
            assertEquals(eventType, evento.eventType());
            assertEquals(schemaVersion, evento.schemaVersion());
            assertEquals(pedidoId, evento.pedidoId());
            assertEquals(codigoPedidoOrigem, evento.codigoPedidoOrigem());
            assertEquals(cnpj, evento.cnpjParceiro());
            assertEquals(statusAnterior, evento.statusAnterior());
            assertEquals(statusNovo, evento.statusNovo());
            assertEquals(0, valorTotal.compareTo(evento.valorTotal()));
            assertEquals(versaoPedido, evento.versaoPedido());
            assertEquals(agora, evento.ocorridoEm());
        }
    }

    @Nested
    @DisplayName("2. Factory Method de Tradução (from Pedido)")
    class FactoryMethodTests {

        @Test
        @DisplayName("Deve traduzir perfeitamente a Entidade Pedido para o DTO enriquecido via método de fábrica")
        void deveTraduzirPedidoParaEventoComSucesso() {
            // Arrange
            Parceiro parceiro = new Parceiro("Bayer Crop Science", "98765432109876", new BigDecimal("10000.00"));
            // O pedido já nasce com a chave de idempotência do ERP no construtor
            Pedido pedido = new Pedido(parceiro, "ERP-123");
            pedido.aprovar();
            pedido.incrementarValorTotal(new BigDecimal("450.75"));

            Instant inicioTeste = Instant.now();

            // Act - Usamos o overload que aceita o status anterior
            PedidoStatusChangedEvent evento = PedidoStatusChangedEvent.from(pedido, "PENDENTE");

            Instant fimTeste = Instant.now();

            // Assert - Validações do Domínio
            assertNotNull(evento, "O factory method não pode retornar nulo");
            assertEquals(pedido.getId(), evento.pedidoId(), "O ID do evento deve coincidir com o UUID da Raiz de Agregação");
            assertEquals("ERP-123", evento.codigoPedidoOrigem(), "A chave natural do ERP deve trafegar no evento");
            assertEquals("98765432109876", evento.cnpjParceiro(), "O CNPJ do parceiro deve ser achatado corretamente");
            assertEquals("PENDENTE", evento.statusAnterior(), "O status anterior deve ser mapeado");
            assertEquals("APROVADO", evento.statusNovo(), "O status atual do enum deve ser traduzido para string");
            assertEquals(0, new BigDecimal("450.75").compareTo(evento.valorTotal()), "O valor total financeiro deve ser mantido exato");

            // Assert - Validações de Mensageria Resiliente (Fase 3)
            assertNotNull(evento.eventId(), "O evento deve possuir um UUID único para garantir idempotência no destino");
            assertEquals("PEDIDO_APROVADO", evento.eventType(), "O tipo de evento deve refletir o status atual");
            assertEquals("v1.0", evento.schemaVersion(), "A versão do schema deve estar presente");
            assertEquals(pedido.getVersao(), evento.versaoPedido(), "O Optimistic Lock deve trafegar para garantir ordenação no consumidor");

            // Valida o timestamp forjado no momento da criação
            assertNotNull(evento.ocorridoEm());
            assertTrue(
                    !evento.ocorridoEm().isBefore(inicioTeste) && !evento.ocorridoEm().isAfter(fimTeste),
                    "O campo 'ocorridoEm' deve registrar o instante exato da geração do evento"
            );
        }
    }
}