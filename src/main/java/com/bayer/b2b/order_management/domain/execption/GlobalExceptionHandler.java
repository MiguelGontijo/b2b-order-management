package com.bayer.b2b.order_management.domain.execption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * Escudo Global de Exceções (ControllerAdvice).
 * <p>
 * Atua como um interceptador centralizado de erros em toda a camada REST.
 * Garante que exceções de negócio (lançadas pelo Domínio) não vazem
 * stacktraces (erros 500) para o cliente externo. Ele captura as falhas e as traduz
 * para um formato JSON padronizado e com o HTTP Status correto (ex: 422, 404).
 * </p>
 *
 * @author Miguel
 * @version 1.0
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Captura requisições para IDs ou recursos que não existem no banco de dados.
     * <p>
     * <b>Decisão Arquitetural:</b> Intercepta a exceção genérica do domínio e garante
     * que a API responda estritamente com o HTTP Status 404 (Not Found), mantendo a
     * semântica da RFC REST.
     * </p>
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<StandardError> handleResourceNotFoundException(ResourceNotFoundException e) {
        log.warn("[RECURSO INEXISTENTE] Consulta não encontrou dados: {}", e.getMessage());

        StandardError error = new StandardError(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                "Recurso Não Localizado",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Captura qualquer IllegalArgumentException lançada pelos Casos de Uso ou Entidades.
     * <p>
     * <b>Decisão Arquitetural:</b> Um erro de argumento de negócio (como "saldo insuficiente")
     * não é uma falha no servidor (500), mas sim uma entidade que não pode ser
     * processada devido às regras estabelecidas pelo negócio B2B (422 Unprocessable Entity).
     * </p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardError> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("[NEGÓCIO REJEITADO] Falha na requisição: {}", e.getMessage());

        StandardError error = new StandardError(
                Instant.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Regra de Negócio Violada",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Captura NullPointerException provenientes do padrão Fail-Fast (Objects.requireNonNull).
     * <p>
     * <b>Decisão Arquitetural:</b> Como nosso Domínio e Commands utilizam Objects.requireNonNull
     * para blindar a entrada de dados nulos, a ausência de um campo obrigatório no JSON
     * é um erro de payload do cliente, devendo retornar 422, e não uma falha estrutural do servidor (500).
     * </p>
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<StandardError> handleNullPointerException(NullPointerException e) {
        log.warn("[DADOS AUSENTES] Falha de validação de entrada: {}", e.getMessage());

        StandardError error = new StandardError(
                Instant.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Dados Obrigatórios Ausentes",
                e.getMessage()
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Captura falhas genéricas não mapeadas (O Catch-All de Contingência).
     * <p>
     * Se estourar um erro desconhecido no código ou o banco de dados cair, este método
     * garante que o parceiro B2B receba um JSON amigável e obscuro (Erro 500 Genérico),
     * protegendo a infraestrutura de vazar detalhes técnicos do backend.
     * </p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardError> handleGenericException(Exception e) {
        log.error("[FALHA CRÍTICA] Erro interno não tratado.", e);

        StandardError error = new StandardError(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Erro Interno do Servidor",
                "Ocorreu um problema inesperado. Nossa equipe de engenharia já foi notificada."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Captura falhas de conversão de tipos na URL (ex: UUID malformado no PathVariable).
     * <p>
     * <b>Decisão Arquitetural:</b> Protege a borda da aplicação bloqueando requisições com
     * parâmetros ilegítimos antes de acionar o UseCase. A resposta padronizada com HTTP 400
     * (Bad Request) orienta o consumidor da API a corrigir o formato do dado.
     * </p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<StandardError> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("[PARÂMETRO INVÁLIDO] Falha de conversão de tipo na requisição: {}", e.getMessage());

        StandardError error = new StandardError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Parâmetro de URL Inválido",
                "O valor informado para o parâmetro '" + e.getName() + "' não possui um formato suportado."
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // =========================================================================================
    // DTO de Erro Padronizado (Nested Record)
    // =========================================================================================

    public record StandardError(
            Instant timestamp,
            Integer status,
            String error,
            String message
    ) {}
}