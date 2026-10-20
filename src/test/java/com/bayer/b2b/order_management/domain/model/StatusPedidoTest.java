package com.bayer.b2b.order_management.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite de testes unitários para a Máquina de Estados do Pedido (StatusPedido).
 * <p>
 * O foco desta classe é garantir 100% de Branch Coverage sobre as regras de transição.
 * Como o StatusPedido rege o ciclo de vida e a segurança logística da aplicação B2B,
 * testamos exaustivamente todas as combinações possíveis (matriz NxN) para atestar
 * que nenhuma transição ilegal ou pulo de etapa seja permitido pelo Domínio.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@DisplayName("Domínio: Máquina de Estados (StatusPedido)")
class StatusPedidoTest {

    @Nested
    @DisplayName("1. Estado Inicial: PENDENTE")
    class PendenteTests {

        @Test
        @DisplayName("Deve permitir transições válidas (Aprovação Financeira ou Cancelamento Imediato)")
        void devePermitirTransicoesValidas() {
            assertTrue(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.APROVADO),
                    "Um pedido pendente deve poder ser aprovado após reserva de limite");

            assertTrue(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.CANCELADO),
                    "Um pedido pendente deve poder ser cancelado antes de qualquer processamento");
        }

        @Test
        @DisplayName("Deve bloquear transições ilegais (Pulos logísticos ou auto-transição)")
        void deveBloquearTransicoesInvalidas() {
            assertFalse(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.PENDENTE));
            assertFalse(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.EM_PROCESSAMENTO));
            assertFalse(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.ENVIADO));
            assertFalse(StatusPedido.PENDENTE.podeTransitarPara(StatusPedido.ENTREGUE));
        }
    }

    @Nested
    @DisplayName("2. Estado Financeiro: APROVADO")
    class AprovadoTests {

        @Test
        @DisplayName("Deve permitir transições válidas (Início da Separação ou Cancelamento)")
        void devePermitirTransicoesValidas() {
            assertTrue(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.EM_PROCESSAMENTO),
                    "Um pedido aprovado deve seguir para a esteira de processamento/separação");

            assertTrue(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.CANCELADO),
                    "Um pedido aprovado pode ser cancelado se houver desistência antes da separação física");
        }

        @Test
        @DisplayName("Deve bloquear retrocessos, pulos logísticos e auto-transição")
        void deveBloquearTransicoesInvalidas() {
            assertFalse(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.PENDENTE), "Não pode retroceder para Pendente");
            assertFalse(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.APROVADO));
            assertFalse(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.ENVIADO));
            assertFalse(StatusPedido.APROVADO.podeTransitarPara(StatusPedido.ENTREGUE));
        }
    }

    @Nested
    @DisplayName("3. Estado Logístico: EM_PROCESSAMENTO")
    class EmProcessamentoTests {

        @Test
        @DisplayName("Deve permitir transições válidas (Despacho na Transportadora ou Cancelamento Operacional)")
        void devePermitirTransicoesValidas() {
            assertTrue(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.ENVIADO),
                    "Um pedido processado fisicamente deve poder ser enviado");

            assertTrue(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.CANCELADO),
                    "Um pedido em processamento pode sofrer quebra operacional e ser cancelado");
        }

        @Test
        @DisplayName("Deve bloquear retrocessos, pulos e auto-transição")
        void deveBloquearTransicoesInvalidas() {
            assertFalse(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.PENDENTE));
            assertFalse(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.APROVADO));
            assertFalse(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.EM_PROCESSAMENTO));
            assertFalse(StatusPedido.EM_PROCESSAMENTO.podeTransitarPara(StatusPedido.ENTREGUE));
        }
    }

    @Nested
    @DisplayName("4. Estado de Rota: ENVIADO")
    class EnviadoTests {

        @Test
        @DisplayName("Deve permitir exclusivamente a transição de Sucesso Final (ENTREGUE)")
        void devePermitirTransicoesValidas() {
            assertTrue(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.ENTREGUE),
                    "O único caminho natural pós-envio é a entrega no cliente");
        }

        @Test
        @DisplayName("Deve bloquear cancelamentos em rota, retrocessos e auto-transição")
        void deveBloquearTransicoesInvalidas() {
            // Nota de Engenharia: A regra de negócio definiu que cancelamento em rota não é permitido via State Machine direta.
            // Exigiria um fluxo de logística reversa.
            assertFalse(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.CANCELADO), "Cancelamento de carga em trânsito não é permitido nativamente");
            assertFalse(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.PENDENTE));
            assertFalse(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.APROVADO));
            assertFalse(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.EM_PROCESSAMENTO));
            assertFalse(StatusPedido.ENVIADO.podeTransitarPara(StatusPedido.ENVIADO));
        }
    }

    @Nested
    @DisplayName("5. Estados Terminais (ENTREGUE e CANCELADO)")
    class EstadosTerminaisTests {

        @Test
        @DisplayName("Um pedido ENTREGUE deve ser imutável e rejeitar qualquer transição")
        void entregueDeveRejeitarQualquerMudanca() {
            for (StatusPedido status : StatusPedido.values()) {
                assertFalse(StatusPedido.ENTREGUE.podeTransitarPara(status),
                        "Um pedido ENTREGUE atingiu o final do ciclo de vida e é blindado.");
            }
        }

        @Test
        @DisplayName("Um pedido CANCELADO deve ser imutável, rejeitando ressurreição ou qualquer transição")
        void canceladoDeveRejeitarQualquerMudanca() {
            for (StatusPedido status : StatusPedido.values()) {
                assertFalse(StatusPedido.CANCELADO.podeTransitarPara(status),
                        "Um pedido CANCELADO é o terminal de falha e não pode ser ressuscitado para nenhum outro status.");
            }
        }
    }
}