package br.com.lavajato.exception;

import org.springframework.http.HttpStatus;

/** Erro de regra de negócio: vira uma resposta JSON com mensagem clara para o front. */
public class RegraNegocioException extends RuntimeException {

    private final HttpStatus status;

    public RegraNegocioException(String mensagem) {
        this(HttpStatus.CONFLICT, mensagem);
    }

    public RegraNegocioException(HttpStatus status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
