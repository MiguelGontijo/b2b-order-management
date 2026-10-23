package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
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
 * O foco desta classe é atestar o contrato de serialização assíncrona (Mensageria/Kafka).
 * Validamos a imutabilidade do Record e a fidelidade da tradução realizada pelo
 * Factory Method (from), garantindo que o estado da Raiz de Agregação (Pedido)
 * seja refletido com precisão no payload que será distribuído aos microsserviços de
 * Faturamento e Logística.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Infraestrutura/Mensageria: PedidoStatusChangedEvent")
class PedidoStatusChangedEventTest {

    @Nested
    @DisplayName("1. Instanciação Direta e Imutabilidade (Records)")
    class InstanciacaoRecordTests {

        @Test
        @DisplayName("Deve criar o evento diretamente com sucesso preenchendo todos os atributos")
        void deveCriarEventoDiretamente() {
            // Arrange
            UUID pedidoId = UUID.randomUUID();
            String cnpj = "12345678901234";
            String status = "APROVADO";
            BigDecimal valorTotal = new BigDecimal("1500.00");
            Instant agora = Instant.now();

            // Act
            PedidoStatusChangedEvent evento = new PedidoStatusChangedEvent(
                    pedidoId, cnpj, status, valorTotal, agora
            );

            // Assert
            assertNotNull(evento);
            assertEquals(pedidoId, evento.pedidoId());
            assertEquals(cnpj, evento.cnpjParceiro());
            assertEquals(status, evento.novoStatus());
            assertEquals(0, valorTotal.compareTo(evento.valorTotal()));
            assertEquals(agora, evento.dataTransitiva());
        }
    }

    @Nested
    @DisplayName("2. Factory Method de Tradução (from Pedido)")
    class FactoryMethodTests {

        @Test
        @DisplayName("Deve traduzir perfeitamente a Entidade Pedido para o DTO de Evento via método de fábrica")
        void deveTraduzirPedidoParaEventoComSucesso() {
            // Arrange
            Parceiro parceiro = new Parceiro("Bayer Crop Science", "98765432109876", new BigDecimal("10000.00"));
            Pedido pedido = new Pedido(parceiro, "ERP-123");
            pedido.aprovar();

            // Incrementamos um valor para simular itens no pedido
            pedido.incrementarValorTotal(new BigDecimal("450.75"));

            Instant inicioTeste = Instant.now();

            // Act
            PedidoStatusChangedEvent evento = PedidoStatusChangedEvent.from(pedido);

            Instant fimTeste = Instant.now();

            // Assert
            assertNotNull(evento, "O factory method não pode retornar nulo");
            assertEquals(pedido.getId(), evento.pedidoId(), "O ID do evento deve coincidir com o UUID da Raiz de Agregação");
            assertEquals("98765432109876", evento.cnpjParceiro(), "O CNPJ do parceiro deve ser achatado corretamente no evento");
            assertEquals("APROVADO", evento.novoStatus(), "O status atual do enum deve ser traduzido para string");
            assertEquals(0, new BigDecimal("450.75").compareTo(evento.valorTotal()), "O valor total financeiro deve ser mantido exato");

            // Valida o carimbo de tempo transitivo gerado no momento do factory method
            assertNotNull(evento.dataTransitiva());
            assertTrue(
                    !evento.dataTransitiva().isBefore(inicioTeste) && !evento.dataTransitiva().isAfter(fimTeste),
                    "A data transitiva deve registrar o instante exato da geração do evento"
            );
        }
    }
}