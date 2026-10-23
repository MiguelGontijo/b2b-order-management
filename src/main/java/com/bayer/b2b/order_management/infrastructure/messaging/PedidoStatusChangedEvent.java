package com.bayer.b2b.order_management.infrastructure.messaging;

import com.bayer.b2b.order_management.domain.model.Pedido;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO (Data Transfer Object) de Evento de Domínio (Domain Event).
 * <p>
 * Decisões Arquiteturais Classe Ouro (Evolução Fase 3):
 * 1. Idempotência Distribuída: O 'eventId' é gerado na origem. O Consumidor utilizará
 *    esta chave no MongoDB para impedir processamento duplicado em caso de retentativas de rede.
 * 2. Versionamento (Backwards Compatibility): O 'schemaVersion' protege o ecossistema
 *    caso a estrutura do payload precise mudar drasticamente no futuro.
 * 3. Ordenação (Out-of-Order Messages): A 'versaoPedido' (Optimistic Lock) viaja no evento.
 *    Isso garante que consumidores satélites possam descartar mensagens atrasadas pela rede.
 * 4. Padrão de Projeto: O Factory Method original foi mantido para não quebrar a compilação
 *    das classes legadas, mas foi adicionado um overload para suportar o rastreio do 'statusAnterior'.
 * </p>
 *
 * @author Miguel
 * @version 2.0
 */
public record PedidoStatusChangedEvent(
        UUID eventId,
        String eventType,
        String schemaVersion,
        UUID pedidoId,
        String codigoPedidoOrigem,
        String cnpjParceiro,
        String statusAnterior,
        String statusNovo,
        BigDecimal valorTotal,
        Long versaoPedido,
        Instant ocorridoEm
) {

    /**
     * Factory method original (Retrocompatibilidade).
     * Garante que as chamadas atuais do sistema continuem compilando perfeitamente
     * enquanto a refatoração do Publisher não é concluída.
     *
     * @param pedido A raiz de agregação no seu estado atual.
     */
    public static PedidoStatusChangedEvent from(Pedido pedido) {
        return from(pedido, "NAO_INFORMADO");
    }

    /**
     * Factory method completo para a nova arquitetura orientada a eventos.
     *
     * @param pedido A raiz de agregação.
     * @param statusAnterior O estado em que o pedido se encontrava antes da mutação.
     * @return O evento imutável enriquecido com metadados de mensageria nível Sênior.
     */
    public static PedidoStatusChangedEvent from(Pedido pedido, String statusAnterior) {
        return new PedidoStatusChangedEvent(
                UUID.randomUUID(),                                   // Identificador único do evento na rede
                "PEDIDO_" + pedido.getStatus().name(),               // Classificação semântica da mensagem
                "v1.0",                                              // Versão do contrato
                pedido.getId(),                                      // Aggregate ID
                pedido.getCodigoPedidoOrigem(),                      // Chave natural B2B para o ERP
                pedido.getParceiro().getCnpj(),                      // Documento fiscal atrelado
                statusAnterior,                                      // Rastreio da máquina de estados
                pedido.getStatus().name(),                           // Status consolidado
                pedido.getValorTotal(),                              // Faturamento
                pedido.getVersao(),                                  // Controle de Concorrência
                Instant.now()                                        // Timestamp de forja (Criação) da mensagem
        );
    }
}