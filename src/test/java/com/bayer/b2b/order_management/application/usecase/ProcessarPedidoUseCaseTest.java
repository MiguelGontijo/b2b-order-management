package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ItemCommand;
import com.bayer.b2b.order_management.application.usecase.ProcessarPedidoUseCase.ProcessarPedidoCommand;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.domain.model.Pedido;
import com.bayer.b2b.order_management.domain.model.StatusPedido;
import com.bayer.b2b.order_management.domain.port.OrderEventPublisherPort;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
import com.bayer.b2b.order_management.infrastructure.database.PedidoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para o Caso de Uso de Processamento de Pedido.
 * <p>
 * O foco desta classe é atestar a Orquestração Perfeita:
 * 1. Tradução do Command para Aggregate Root.
 * 2. Aplicação de regras que disparam ações de persistência e mensageria.
 * 3. Blindagem estrita contra side-effects: Em caso de falha de negócio,
 *    repositórios e publishers não podem ser acionados (Rollback prevention).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Caso de Uso: Processar Pedido")
class ProcessarPedidoUseCaseTest {

    @Mock
    private ParceiroRepository parceiroRepository;

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private OrderEventPublisherPort eventPublisher;

    @InjectMocks
    private ProcessarPedidoUseCase useCase;

    @Captor
    private ArgumentCaptor<Pedido> pedidoCaptor;

    @Nested
    @DisplayName("1. Validação dos DTOs de Entrada (Commands)")
    class CommandsValidationTests {

        @Test
        @DisplayName("Deve instanciar ProcessarPedidoCommand com sucesso quando dados forem válidos")
        void deveCriarProcessarPedidoCommand() {
            List<ItemCommand> itens = List.of(new ItemCommand("PROD-1", 10, new BigDecimal("10.00")));
            ProcessarPedidoCommand command = new ProcessarPedidoCommand(1L, itens);

            assertEquals(1L, command.parceiroId());
            assertEquals(1, command.itens().size());
        }

        @Test
        @DisplayName("Deve lançar exceção quando o parceiroId do Command for nulo")
        void deveFalharQuandoParceiroIdNulo() {
            List<ItemCommand> itens = List.of(new ItemCommand("PROD-1", 10, new BigDecimal("10.00")));
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new ProcessarPedidoCommand(null, itens)
            );
            assertEquals("O ID do parceiro é obrigatório", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar exceção quando a lista de itens do Command for nula ou vazia")
        void deveFalharQuandoItensNulosOuVazios() {
            IllegalArgumentException exNulo = assertThrows(IllegalArgumentException.class, () ->
                    new ProcessarPedidoCommand(1L, null)
            );
            assertEquals("O pedido deve conter pelo menos um item", exNulo.getMessage());

            IllegalArgumentException exVazio = assertThrows(IllegalArgumentException.class, () ->
                    new ProcessarPedidoCommand(1L, Collections.emptyList())
            );
            assertEquals("O pedido deve conter pelo menos um item", exVazio.getMessage());
        }

        @Test
        @DisplayName("Deve validar a recusa de propriedades nulas no ItemCommand")
        void deveFalharQuandoCamposDoItemForemNulos() {
            assertThrows(NullPointerException.class, () -> new ItemCommand(null, 1, BigDecimal.TEN));
            assertThrows(NullPointerException.class, () -> new ItemCommand("PROD", null, BigDecimal.TEN));
            assertThrows(NullPointerException.class, () -> new ItemCommand("PROD", 1, null));
        }
    }

    @Nested
    @DisplayName("2. Orquestração e Execução (Happy Path)")
    class OrquestracaoSucessoTests {

        @Test
        @DisplayName("Deve processar a recepção do pedido, salvar como PENDENTE e publicar o evento sem reter saldo")
        void deveProcessarPedidoComSucesso() {
            // Arrange - Criamos os DTOs do cliente
            List<ItemCommand> itensCommand = List.of(
                    new ItemCommand("PROD-X", 2, new BigDecimal("50.00")), // Total: 100.00
                    new ItemCommand("PROD-Y", 1, new BigDecimal("150.00")) // Total: 150.00
            ); // Total do Pedido = 250.00
            ProcessarPedidoCommand command = new ProcessarPedidoCommand(99L, itensCommand);

            // Arrange - Simulamos a existência do Parceiro no banco com limite inicial (1000.00)
            Parceiro parceiroRealDoDominio = new Parceiro("Bayer Agro", "11111111111111", new BigDecimal("1000.00"));
            // Alteração Arquitetural: O bloqueio pessimista foi removido da fase de criação
            when(parceiroRepository.findById(99L)).thenReturn(Optional.of(parceiroRealDoDominio));

            // Arrange - Simulamos o repositório de persistência apenas devolvendo o objeto que ele recebeu
            when(pedidoRepository.save(any(Pedido.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Pedido pedidoFinal = useCase.execute(command);

            // Assert - Validações de Retorno
            assertNotNull(pedidoFinal, "O caso de uso deve retornar a Raiz de Agregação");
            assertEquals(StatusPedido.PENDENTE, pedidoFinal.getStatus(), "O pedido recém-criado deve nascer estritamente como PENDENTE");

            // Compara com 0 usando compareTo para ignorar problemas de precisão e zeros à direita
            assertEquals(0, new BigDecimal("250.00").compareTo(pedidoFinal.getValorTotal()), "O valor total orquestrado deve ser a soma exata dos comandos");

            // Assert - Garantia Arquitetural: O limite de crédito NÃO DEVE ser deduzido na criação
            assertEquals(0, new BigDecimal("1000.00").compareTo(parceiroRealDoDominio.getLimiteCredito()),
                    "O limite de crédito do parceiro deve permanecer intacto na fase de processamento inicial");

            // Assert - Auditoria Arquitetural (Mockito Verification)
            // 1. Capturamos o que foi enviado para o banco
            verify(pedidoRepository, times(1)).save(pedidoCaptor.capture());
            Pedido pedidoSalvoNoBanco = pedidoCaptor.getValue();
            assertEquals(2, pedidoSalvoNoBanco.getItens().size(), "A conversão DTO -> Entidade deve preservar todos os itens");

            // 2. Garantimos que o evento foi despachado para a mensageria exatamente com o pedido que saiu do banco
            verify(eventPublisher, times(1)).publicarEventoStatus(pedidoSalvoNoBanco);

            // 3. Garantimos ausência de interações inúteis
            verifyNoMoreInteractions(parceiroRepository, pedidoRepository, eventPublisher);
        }
    }

    @Nested
    @DisplayName("3. Falhas de Negócio e Infraestrutura (Rollback Protection)")
    class FalhasNegocioTests {

        @Test
        @DisplayName("Deve barrar e lançar IllegalArgumentException quando o parceiro não for encontrado no banco")
        void deveFalharQuandoParceiroNaoExistir() {
            // Arrange
            ProcessarPedidoCommand command = new ProcessarPedidoCommand(99L, List.of(new ItemCommand("PROD-1", 1, BigDecimal.TEN)));

            // Simulamos o banco não encontrando ninguém (leitura simples sem lock)
            when(parceiroRepository.findById(99L)).thenReturn(Optional.empty());

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    useCase.execute(command)
            );
            assertEquals("Parceiro não encontrado para o ID: 99", exception.getMessage());

            // GARANTIA DE OURO: O sistema abortou rápido e NENHUM dado sujo foi mandado pro banco ou mensageria
            verifyNoInteractions(pedidoRepository, eventPublisher);
        }

        @Test
        @DisplayName("Deve barrar logo na entrada e não processar nada se o Command for nulo")
        void deveFalharQuandoCommandNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(null)
            );
            assertEquals("O comando de processamento não pode ser nulo", exception.getMessage());
            verifyNoInteractions(parceiroRepository, pedidoRepository, eventPublisher);
        }
    }
}