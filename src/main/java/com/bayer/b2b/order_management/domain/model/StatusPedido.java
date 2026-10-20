package com.bayer.b2b.order_management.domain.model;

/**
 * Representa a Máquina de Estados (State Machine) de um Pedido B2B.
 * <p>
 * O uso do padrão State encapsulado no Enum garante que transições ilegais
 * (como cancelar um pedido que já foi entregue) sejam bloqueadas nativamente
 * na camada de domínio. Isso blinda a regra de negócio e elimina a necessidade
 * de espalhar lógicas de validação (IFs) pela camada de Aplicação (UseCases).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
public enum StatusPedido {

    PENDENTE {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Um pedido recém-chegado só pode ser aprovado financeiramente ou cancelado.
            return novoStatus == APROVADO || novoStatus == CANCELADO;
        }
    },

    APROVADO {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Após ter o limite de crédito deduzido, segue para a separação (processamento) ou é cancelado.
            return novoStatus == EM_PROCESSAMENTO || novoStatus == CANCELADO;
        }
    },

    EM_PROCESSAMENTO {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Na esteira logística, pode ser despachado ou sofrer cancelamento operacional.
            return novoStatus == ENVIADO || novoStatus == CANCELADO;
        }
    },

    ENVIADO {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Uma vez na transportadora, o único caminho natural de sucesso é a entrega.
            // Cancelamentos operacionais de itens em rota exigem fluxos de logística reversa separados.
            return novoStatus == ENTREGUE;
        }
    },

    ENTREGUE {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Estado terminal de sucesso. Não sofre mutações diretas.
            return false;
        }
    },

    CANCELADO {
        @Override
        public boolean podeTransitarPara(StatusPedido novoStatus) {
            // Estado terminal de falha/desistência. Não pode ser ressuscitado.
            return false;
        }
    };

    /**
     * Valida se a transição do estado atual para o novo estado obedece
     * à trilha logística e financeira do negócio.
     *
     * @param novoStatus O estado destino desejado.
     * @return true se a transição for válida, false caso seja uma violação de fluxo.
     */
    public abstract boolean podeTransitarPara(StatusPedido novoStatus);
}