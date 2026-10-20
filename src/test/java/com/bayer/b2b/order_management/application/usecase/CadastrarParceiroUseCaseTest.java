package com.bayer.b2b.order_management.application.usecase;

import com.bayer.b2b.order_management.application.usecase.CadastrarParceiroUseCase.CadastrarParceiroCommand;
import com.bayer.b2b.order_management.domain.model.Parceiro;
import com.bayer.b2b.order_management.infrastructure.database.ParceiroRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de testes unitários para o Caso de Uso de Cadastro de Parceiros.
 * <p>
 * O foco desta classe é garantir o comportamento de orquestração (Application Service).
 * Utilizamos Mockito para isolar a infraestrutura (Banco de Dados) e focar estritamente
 * no fluxo de controle: Recebimento do Command -> Tradução para Entidade -> Persistência.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Caso de Uso: Cadastrar Parceiro")
class CadastrarParceiroUseCaseTest {

    @Mock
    private ParceiroRepository parceiroRepository;

    @InjectMocks
    private CadastrarParceiroUseCase useCase;

    @Captor
    private ArgumentCaptor<Parceiro> parceiroCaptor;

    @Nested
    @DisplayName("1. Validação do DTO de Entrada (CadastrarParceiroCommand)")
    class CommandValidationTests {

        @Test
        @DisplayName("Deve criar o Command com sucesso ao fornecer dados válidos")
        void deveCriarCommandComSucesso() {
            // Arrange & Act
            CadastrarParceiroCommand command = new CadastrarParceiroCommand(
                    "Bayer Global", "12345678901234", new BigDecimal("50000.00")
            );

            // Assert
            assertNotNull(command);
            assertEquals("Bayer Global", command.nome());
            assertEquals("12345678901234", command.cnpj());
            assertEquals(new BigDecimal("50000.00"), command.limiteCredito());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o nome do Command for nulo")
        void deveFalharQuandoNomeCommandForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new CadastrarParceiroCommand(null, "12345678901234", new BigDecimal("50000.00"))
            );
            assertEquals("O nome do parceiro é obrigatório", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar IllegalArgumentException quando o nome do Command for vazio ou apenas espaços")
        void deveFalharQuandoNomeCommandForVazio() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    new CadastrarParceiroCommand("   ", "12345678901234", new BigDecimal("50000.00"))
            );
            assertEquals("O nome não pode estar vazio", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o CNPJ do Command for nulo")
        void deveFalharQuandoCnpjCommandForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new CadastrarParceiroCommand("Bayer Global", null, new BigDecimal("50000.00"))
            );
            assertEquals("O CNPJ é obrigatório", exception.getMessage());
        }

        @Test
        @DisplayName("Deve lançar NullPointerException quando o limite de crédito do Command for nulo")
        void deveFalharQuandoLimiteCommandForNulo() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    new CadastrarParceiroCommand("Bayer Global", "12345678901234", null)
            );
            assertEquals("O limite de crédito é obrigatório", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("2. Execução da Orquestração do Caso de Uso")
    class ExecuteTests {

        @Test
        @DisplayName("Deve orquestrar o cadastro, traduzir o Command e delegar para o Repositório")
        void deveExecutarOrquestracaoComSucesso() {
            // Arrange
            CadastrarParceiroCommand command = new CadastrarParceiroCommand(
                    "Bayer Brasil", "98765432109876", new BigDecimal("100000.00")
            );

            // Simulamos o comportamento do repositório: ao receber qualquer Parceiro, ele o devolve (comportamento do JPA).
            // Usamos um mock manual interno para simular o ID sendo preenchido no banco.
            Parceiro parceiroSimuladoDoBanco = mock(Parceiro.class);
            when(parceiroRepository.save(any(Parceiro.class))).thenReturn(parceiroSimuladoDoBanco);

            // Act
            Parceiro resultado = useCase.execute(command);

            // Assert
            assertNotNull(resultado, "O Caso de Uso deve retornar a entidade persistida");
            assertEquals(parceiroSimuladoDoBanco, resultado, "A entidade retornada deve ser exatamente a devolvida pelo repositório");

            // Verificação Arquitetural de Ouro (ArgumentCaptor):
            // Interceptamos o que o UseCase tentou salvar no banco de dados.
            verify(parceiroRepository, times(1)).save(parceiroCaptor.capture());
            Parceiro entidadeCapturada = parceiroCaptor.getValue();

            // Provamos que o UseCase não perdeu nem alterou nenhuma informação durante a conversão Command -> Entidade
            assertEquals(command.nome(), entidadeCapturada.getNome());
            assertEquals(command.cnpj(), entidadeCapturada.getCnpj());
            assertEquals(command.limiteCredito(), entidadeCapturada.getLimiteCredito());

            // Garantimos que não houve interações indesejadas (side-effects) com a interface do banco.
            verifyNoMoreInteractions(parceiroRepository);
        }

        @Test
        @DisplayName("Deve barrar a execução e lançar NullPointerException se o Command recebido for nulo")
        void deveFalharQuandoCommandForNulo() {
            // Act & Assert
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                    useCase.execute(null)
            );
            assertEquals("O comando de cadastro não pode ser nulo", exception.getMessage());

            // Garante que o banco de dados NUNCA seja tocado em caso de payload nulo
            verifyNoInteractions(parceiroRepository);
        }

        @Test
        @DisplayName("Deve permitir que exceções de domínio (Fail-Fast) subam sem serem interceptadas indevidamente")
        void deveDeixarExcecoesDeDominioSubirem() {
            // Arrange
            // Apesar de o Command permitir esse input (ele só checa nulls e vazio),
            // a Entidade Parceiro bloqueia limites negativos. O UseCase não deve mascarar esse erro.
            CadastrarParceiroCommand commandComLimiteInvalido = new CadastrarParceiroCommand(
                    "Bayer Alemanha", "11111111111111", new BigDecimal("-50.00")
            );

            // Act & Assert
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                    useCase.execute(commandComLimiteInvalido)
            );

            assertEquals("O limite de crédito não pode ser negativo", exception.getMessage());

            // Garante que a transação morre antes de sequer tentar bater no banco de dados
            verifyNoInteractions(parceiroRepository);
        }
    }
}