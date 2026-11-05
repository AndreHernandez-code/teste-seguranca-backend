package br.com.lavajato.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Todas as respostas de erro da API seguem o formato {status, mensagem, campos?}.
 *
 * ETAPA 5 - ERROS E LOGS
 *
 * O que mudou aqui, e por que:
 *
 * 1. Erro INESPERADO agora tem tratamento e formato. Antes, uma excecao que
 *    ninguem previu caia no /error do Spring Boot, que devolve um JSON com
 *    outra forma (timestamp, path, error, trace...) - o front nao reconhecia e
 *    mostrava "erro desconhecido". Agora sai no mesmo {status, mensagem}.
 *
 * 2. A mensagem devolvida ao cliente NUNCA e a da excecao. Uma
 *    NullPointerException ou um SQLException pode conter nome de tabela, texto
 *    de consulta, caminho de arquivo ou dado de outro usuario. O cliente recebe
 *    um texto generico; o detalhe vai para o LOG, que e onde ele serve.
 *
 * 3. Excecao inesperada agora e LOGADA com stack trace. Antes ela sumia: o
 *    Boot registrava num nivel que a configuracao padrao nao mostrava, e o
 *    erro passava silencioso em producao.
 *
 * 4. Cada erro inesperado ganha um id curto, que aparece NO LOG e NA RESPOSTA.
 *    E o que transforma "deu erro" num chamado que da para investigar: o
 *    usuario informa o id, e o log se acha a linha exata sem procurar por data.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Mensagem unica para erro inesperado: nao revela nada sobre a causa. */
    private static final String MENSAGEM_ERRO_INTERNO =
            "Erro interno. Tente novamente; se persistir, informe o código ao suporte.";

    @ExceptionHandler(RegraNegocioException.class)
    public ResponseEntity<Map<String, Object>> regraNegocio(RegraNegocioException e) {
        // Regra de negocio e fluxo esperado: o status vem da propria excecao e a
        // mensagem foi escrita para o usuario ler. Nao e erro de servidor, entao
        // fica em INFO/WARN e sem stack trace.
        if (e.getStatus().is5xxServerError()) {
            log.warn("Regra de negocio devolveu status de servidor ({}): {}", e.getStatus(), e.getMessage());
        } else {
            log.info("Regra de negocio recusada ({}): {}", e.getStatus().value(), e.getMessage());
        }
        return corpo(e.getStatus(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacao(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        // Nomes de campo e motivo, nunca o valor enviado: o valor pode ser dado
        // pessoal (nome, documento) e nao tem por que voltar num log.
        log.info("Requisicao recusada por validacao. Campos com problema: {}", campos.keySet());
        return corpo(HttpStatus.BAD_REQUEST, "Dados inválidos.", campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> jsonInvalido(HttpMessageNotReadableException e) {
        // A causa costuma ser um valor fora do enum ou JSON malformado. A mensagem
        // do Spring inclui trecho do corpo recebido - por isso ela NAO vai para o
        // cliente nem para o log; so a causa raiz entra no log, em DEBUG.
        log.info("Corpo de requisicao ilegivel (JSON malformado ou valor fora do dominio).");
        log.debug("Detalhe do corpo ilegivel: {}", e.getMostSpecificCause().getMessage());
        return corpo(HttpStatus.BAD_REQUEST,
                "Corpo da requisição inválido. Confira os campos e valores (ex.: status).", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integridade(DataIntegrityViolationException e) {
        // Conflito previsto (registro em uso, chave duplicada): e erro do cliente,
        // nao do servidor, mas a causa raiz interessa ao log - sem ela nao da para
        // saber se foi FK, unique ou not null.
        log.warn("Violacao de integridade no banco: {}", e.getMostSpecificCause().getMessage());
        return corpo(HttpStatus.CONFLICT,
                "A operação viola uma regra do banco (registro em uso ou duplicado).", null);
    }

    /**
     * Recurso que nao existe. Sem este handler, um GET para URL errada virava
     * 401/500 generico e o front nao distinguia "nao existe" de "sem permissao".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> naoEncontrado(NoResourceFoundException e) {
        log.info("Recurso nao encontrado: {}", e.getResourcePath());
        return corpo(HttpStatus.NOT_FOUND, "Recurso não encontrado.", null);
    }

    /**
     * Rede de seguranca. Qualquer excecao nao tratada pelos handlers acima cai
     * aqui.
     *
     * A ordem importa: o Spring escolhe o handler MAIS ESPECIFICO, entao os
     * tipos acima tem precedencia e este so pega o que sobrar. Se ele nao
     * existisse, a excecao iria para o /error do Boot, que devolve um JSON com
     * forma diferente e pode incluir o stack trace em algumas configuracoes.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inesperado(Exception e, HttpServletRequest request) {

        // Se a propria excecao ja sabe o status certo, respeita (ex.: 405 de
        // metodo nao permitido, 415 de tipo nao suportado). Sem isto, um erro
        // 405 viraria 500 e o front trataria como falha de servidor.
        HttpStatusCode status = (e instanceof ErrorResponse erro)
                ? erro.getStatusCode()
                : HttpStatus.INTERNAL_SERVER_ERROR;

        String codigo = gerarCodigo();

        if (status.is5xxServerError()) {
            // ERRO DE SERVIDOR: e o unico caso em que o stack trace interessa.
            // Vai inteiro para o log, junto do codigo, para casar com o que o
            // usuario reportar. Nada disso vai para a resposta.
            log.error("Erro interno [{}] em {} {} - codigo para o usuario: {}",
                    e.getClass().getSimpleName(), request.getMethod(), request.getRequestURI(),
                    codigo, e);
            return corpo(HttpStatus.INTERNAL_SERVER_ERROR, MENSAGEM_ERRO_INTERNO + " Código: " + codigo, null);
        }

        // 4xx: e erro do cliente, nao do servidor. Stack trace aqui so poluiria o
        // log - o que interessa e qual requisicao foi recusada e por que.
        log.warn("Requisicao recusada [{}] em {} {}: {}",
                status.value(), request.getMethod(), request.getRequestURI(), e.getMessage());
        return corpo(status, "Requisição inválida.", null);
    }

    /**
     * Id curto, so para correlacionar a resposta do cliente com a linha do log.
     * Nao e segredo nem identificador de usuario: e um rotulo de ocorrencia.
     */
    private String gerarCodigo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<Map<String, Object>> corpo(HttpStatusCode status, String mensagem, Object campos) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("status", status.value());
        corpo.put("mensagem", mensagem);
        if (campos != null) corpo.put("campos", campos);
        return ResponseEntity.status(status).body(corpo);
    }
}
