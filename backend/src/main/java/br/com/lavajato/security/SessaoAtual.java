package br.com.lavajato.security;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Acesso ao ID da sessao HTTP da requisicao em curso.
 *
 * Existe para o UsuarioController poder dizer "encerre as outras sessoes deste
 * funcionario, menos ESTA" sem receber HttpServletRequest como parametro do
 * metodo - o que mudaria a assinatura do endpoint e o controller passaria a
 * carregar um detalhe de transporte que nao faz parte do caso de uso.
 *
 * Devolve null quando nao ha requisicao: em teste de unidade, num job de
 * inicializacao ou numa chamada fora do ciclo web. Quem chama trata o null -
 * sem sessao atual, nao ha o que preservar, e encerrar todas e a acao segura.
 */
public final class SessaoAtual {

    private SessaoAtual() {
    }

    /** ID da sessao atual, ou null se nao houver requisicao com sessao. */
    public static String idSessao() {
        var atributos = RequestContextHolder.getRequestAttributes();
        if (!(atributos instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        var sessao = servlet.getRequest().getSession(false);
        return sessao == null ? null : sessao.getId();
    }

    /** O mesmo que {@link #idSessao()}, mantido por clareza em quem le o controller. */
    public static String id() {
        return idSessao();
    }
}
