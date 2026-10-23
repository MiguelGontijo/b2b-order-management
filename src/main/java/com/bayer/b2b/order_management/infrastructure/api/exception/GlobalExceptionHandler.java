package com.bayer.b2b.order_management.infrastructure.api.exception;

import com.bayer.b2b.order_management.domain.exception.CreditoInsuficienteException;
import com.bayer.b2b.order_management.domain.exception.PedidoNaoEncontradoException;
import com.bayer.b2b.order_management.domain.exception.TransicaoStatusInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Escudo Global de Exceções (ControllerAdvice) para a Fronteira REST.
 * <p>
 * Decisões Arquiteturais:
 * 1. Isolamento de Camadas: Movido para 'infrastructure.api.exception' para não poluir o Domínio.
 * 2. Padronização IETF (RFC 9457): Substitui o DTO customizado legado pelo ProblemDetail
 *    nativo do Spring Boot 3, garantindo interoperabilidade com clientes B2B globais.
 * 3. Fail-Safe: Evita o vazamento de stacktraces para o consumidor da API.
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Intercepta consultas a agregados inexistentes (HTTP 404).
     */
    @ExceptionHandler(PedidoNaoEncontradoException.class)
    public ProblemDetail handlePedidoNaoEncontradoException(PedidoNaoEncontradoException e) {
        log.warn("[RECURSO INEXISTENTE] Consulta não encontrou dados: {}", e.getMessage());
        return criarProblemDetail(HttpStatus.NOT_FOUND, "Recurso Não Localizado", e.getMessage());
    }

    /**
     * Intercepta falhas financeiras de crédito insuficiente (HTTP 422).
     */
    @ExceptionHandler(CreditoInsuficienteException.class)
    public ProblemDetail handleCreditoInsuficienteException(CreditoInsuficienteException e) {
        log.warn("[CRÉDITO INSUFICIENTE] Transação rejeitada: {}", e.getMessage());
        return criarProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Regra Financeira Violada", e.getMessage());
    }

    /**
     * Intercepta violações da Máquina de Estados (HTTP 409).
     * Retorna 409 Conflict, pois o estado atual do recurso não permite a operação solicitada.
     */
    @ExceptionHandler(TransicaoStatusInvalidaException.class)
    public ProblemDetail handleTransicaoStatusInvalidaException(TransicaoStatusInvalidaException e) {
        log.warn("[CONFLITO DE ESTADO] Operação inválida: {}", e.getMessage());
        return criarProblemDetail(HttpStatus.CONFLICT, "Conflito de Estado do Pedido", e.getMessage());
    }

    /**
     * Intercepta colisões de concorrência otimista (Lost Updates) gerenciadas pelo Hibernate (HTTP 409).
     * <p>
     * Decisão Arquitetural: Quando duas requisições tentam alterar o mesmo pedido simultaneamente
     * (mesma @Version), a que chegar por último será ejetada pelo banco de dados. Este handler
     * captura o aborto seguro da transação e orienta o cliente a recarregar o estado mais recente.
     * </p>
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailureException(ObjectOptimisticLockingFailureException e) {
        log.warn("[CONCORRÊNCIA DETECTADA] Atualização perdida prevenida pela anotação @Version: {}", e.getMessage());
        return criarProblemDetail(
                HttpStatus.CONFLICT,
                "Conflito de Atualização Simultânea",
                "O recurso foi modificado por outra transação enquanto você tentava atualizá-lo. Por favor, recarregue os dados mais recentes e tente novamente."
        );
    }

    /**
     * Captura violações estruturais ou valores negativos/inválidos (HTTP 422).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("[NEGÓCIO REJEITADO] Falha de validação de argumentos: {}", e.getMessage());
        return criarProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Validação de Negócio Violada", e.getMessage());
    }

    /**
     * Captura ausência de dados obrigatórios barrados pelo Fail-Fast (HTTP 422).
     */
    @ExceptionHandler(NullPointerException.class)
    public ProblemDetail handleNullPointerException(NullPointerException e) {
        log.warn("[DADOS AUSENTES] Atributo obrigatório nulo: {}", e.getMessage());
        return criarProblemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Dados Obrigatórios Ausentes", e.getMessage());
    }

    /**
     * Captura falhas de conversão na URL, como UUIDs inválidos (HTTP 400).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("[PARÂMETRO INVÁLIDO] Falha de conversão na requisição: {}", e.getMessage());
        String detalhe = String.format("O valor informado para o parâmetro '%s' não possui um formato suportado.", e.getName());
        return criarProblemDetail(HttpStatus.BAD_REQUEST, "Parâmetro de URL Inválido", detalhe);
    }

    /**
     * Intercepta falhas de validação sintática (Bean Validation - @Valid) lançadas pelos Controllers (HTTP 400).
     * <p>
     * Decisão Arquitetural: Mapeia as violações de fronteira extraindo exatamente
     * qual campo falhou e o motivo, adicionando essas informações como uma extensão
     * customizada (propriedade 'violacoes') no ProblemDetail (RFC 9457).
     * Isso implementa o padrão Fail-Fast, impedindo que requisições sujas cheguem ao Domínio.
     * </p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.warn("[PAYLOAD INVÁLIDO] Falha na validação de fronteira. Quantidade de erros: {}", e.getErrorCount());

        ProblemDetail problemDetail = criarProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Falha na Validação dos Dados",
                "Um ou mais campos do payload enviado estão inválidos ou ausentes. Verifique a lista de violações."
        );

        // Extrai os erros de campo e os agrupa em um Map<Campo, Mensagem>
        Map<String, String> violacoes = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (msg1, msg2) -> msg1 + " | " + msg2 // Em caso de múltiplas violações no mesmo campo
                ));

        problemDetail.setProperty("violacoes", violacoes);

        return problemDetail;
    }

    /**
     * Intercepta violações de unicidade no banco de dados (HTTP 409).
     * <p>
     * Decisão Arquitetural: Captura a rejeição física do banco de dados quando ocorre a tentativa
     * de processar um pedido com uma Chave Natural de Idempotência (codigoPedidoOrigem) já existente
     * para o mesmo parceiro. Retorna Conflict, blindando a API contra retentativas de rede
     * e prevenindo cobranças duplicadas (Business Key Constraint).
     * </p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("[CONFLITO DE IDEMPOTÊNCIA] Tentativa de duplicação de pedido rejeitada pelo banco de dados: {}", e.getMessage());
        return criarProblemDetail(
                HttpStatus.CONFLICT,
                "Pedido Duplicado (Conflito de Idempotência)",
                "Já existe um pedido processado com este código de origem para o parceiro informado. A requisição foi ignorada de forma segura para evitar duplicidade financeira."
        );
    }

    /**
     * Catch-all genérico para falhas inesperadas (HTTP 500).
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception e) {
        log.error("[FALHA CRÍTICA] Erro interno não tratado.", e);
        return criarProblemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro Interno do Servidor", "Ocorreu um problema inesperado. Nossa equipe de engenharia já foi notificada.");
    }

    // =========================================================================================
    // Factory Method Privado para Padronização do ProblemDetail (RFC 9457)
    // =========================================================================================

    private ProblemDetail criarProblemDetail(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        // Opcional: Define um tipo de URI amigável para o B2B rastrear a documentação do erro
        problemDetail.setType(URI.create("https://api.bayer.com.br/errors/" + status.value()));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}