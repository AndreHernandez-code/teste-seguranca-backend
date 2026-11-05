package br.com.lavajato.security;

import br.com.lavajato.model.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Encerra as sessoes abertas de um funcionario (pos-Etapa 8).
 *
 * O PROBLEMA QUE ISTO RESOLVE
 *
 * O Spring Security grava o SecurityContext dentro da sessao HTTP no login e o
 * LE DE VOLTA a cada requisicao - sem nunca reconsultar o banco. Confirmei na
 * fonte: HttpSessionSecurityContextRepository.loadContext() apenas desserializa
 * o objeto da sessao (readSecurityContextFromSession -> getAttribute). O
 * UsuarioDetailsService, o AuthenticationProvider e a checagem de "esta ativo?"
 * rodam UMA VEZ, no login.
 *
 * A consequencia, medida em SessaoETrocaDeSenhaTest antes desta correcao:
 *
 *  - Desativar um funcionario NAO encerrava a sessao dele. Demitir alguem e
 *    desativar o login deixava o acesso funcionando ate a sessao expirar
 *    (2 horas) - e o balcao e exatamente onde a sessao fica aberta o dia todo.
 *
 *  - Rebaixar um ADMIN para ATENDENTE NAO tirava os poderes dele. A sessao
 *    continuava passando por hasRole("ADMIN") - o teste provou isso EXCLUINDO um
 *    cliente com a sessao de quem ja tinha sido rebaixado. Pior que o caso
 *    anterior, porque desativar ao menos barra o proximo login; rebaixar deixa
 *    o login novo correto, e so a sessao antiga errada.
 *
 * COMO RESOLVE
 *
 * O SessionRegistry guarda, para cada sessao viva, o ID e o principal
 * autenticado. A partir do login (que e estavel) achamos as sessoes e chamamos
 * expireNow(). Na requisicao seguinte, o ConcurrentSessionFilter ve a sessao
 * marcada como expirada e a invalida.
 *
 * A ARMADILHA DO PRINCIPAL - e por que o codigo NAO usa o login direto
 *
 * registerNewSession() recebe authentication.getPrincipal(), e no nosso login
 * esse principal e o UserDetails que o UsuarioDetailsService montou -
 * org.springframework.security.core.userdetails.User. O equals() dessa classe
 * compara o HASH DA SENHA alem do login:
 *
 *     return username.equals(other.getUsername()) && password.equals(other.getPassword())
 *
 * Entao NUNCA da para procurar por um UserDetails montado na hora: o hash
 * recem-gerado por BCrypt e diferente a cada chamada (o salt e aleatorio), e a
 * busca devolveria lista vazia - silenciosamente, sem erro. O sintoma seria o
 * pior possivel: a correcao "instalada" e sem efeito nenhum.
 *
 * A saida e varrer getAllPrincipals() e comparar SO o getUsername(), que e o
 * que identifica a pessoa. Por isso este metodo existe em vez de uma linha com
 * getAllSessions(...).
 *
 * POR QUE COMPARAR STRING TAMBEM NAO BASTA
 *
 * Ha um segundo detalhe: no login manual do AuthController, o principal
 * registrado e o UserDetails; mas em qualquer caminho em que a autenticacao
 * venha com principal String (um teste, um filtro futuro, uma migracao de
 * versao do Spring), getAllPrincipals() devolveria a String. O codigo aceita as
 * duas formas - comparar apenas com UserDetails deixaria sessoes para tras num
 * desses caminhos, e ninguem descobriria.
 */
@Component
public class GerenciadorDeSessoes {

    private static final Logger log = LoggerFactory.getLogger(GerenciadorDeSessoes.class);

    private final SessionRegistry registro;

    public GerenciadorDeSessoes(SessionRegistry registro) {
        this.registro = registro;
    }

    /**
     * Encerra toda sessao aberta do login informado.
     *
     * Chamar isto nao derruba a requisicao em curso - a sessao fica marcada como
     * expirada e morre na proxima. E o comportamento certo: quem esta editando
     * um cadastro no momento da mudanca termina a operacao, e nao perde o que
     * digitou por um evento que ele nem sabe que aconteceu.
     *
     * @return quantas sessoes foram encerradas, para o log dizer se a acao teve
     *         efeito (um "0" costuma significar que a pessoa nao estava logada,
     *         mas tambem seria o sintoma de a busca ter parado de encontrar as
     *         sessoes - por isso o numero vai para o log em vez de ser ignorado).
     */
    public int encerrarSessoesDe(String login) {
        if (login == null || login.isBlank()) {
            return 0;
        }

        int encerradas = 0;
        for (Object principal : registro.getAllPrincipals()) {
            if (!login.equals(loginDe(principal))) {
                continue;
            }
            // false = nao atualizar a ultima requisicao ao listar; nao ha
            // requisicao acontecendo, e dizer que houve falsearia a expiracao
            // por inatividade.
            List<SessionInformation> sessoes = registro.getAllSessions(principal, false);
            for (SessionInformation sessao : sessoes) {
                sessao.expireNow();
                encerradas++;
            }
        }

        if (encerradas > 0) {
            log.info("Sessoes encerradas para '{}': {}.", login, encerradas);
        } else {
            log.info("Nenhuma sessao aberta para '{}'.", login);
        }
        return encerradas;
    }

    /**
     * Encerra as sessoes do usuario, exceto uma.
     *
     * Usado quando quem opera a mudanca e o proprio usuario: trocar a propria
     * senha nao pode derrubar a sessao que esta fazendo a troca, senao o
     * usuario e deslogado no instante em que confirma - e conclui que o sistema
     * quebrou. As OUTRAS sessoes dele caem, que e o ponto: trocar a senha
     * precisa expulsar quem estivesse usando a senha antiga em outro lugar.
     */
    public int encerrarOutrasSessoesDe(String login, String sessaoAtual) {
        if (login == null || login.isBlank()) {
            return 0;
        }

        int encerradas = 0;
        for (Object principal : registro.getAllPrincipals()) {
            if (!login.equals(loginDe(principal))) {
                continue;
            }
            for (SessionInformation sessao : registro.getAllSessions(principal, false)) {
                if (sessao.getSessionId().equals(sessaoAtual)) {
                    continue;
                }
                sessao.expireNow();
                encerradas++;
            }
        }
        return encerradas;
    }

    /** O login do principal, aceitando UserDetails e String. Ver o javadoc da classe. */
    private static String loginDe(Object principal) {
        if (principal instanceof UserDetails detalhes) {
            return detalhes.getUsername();
        }
        if (principal instanceof String texto) {
            return texto;
        }
        return null;
    }

    /** Conveniencia para o controller: usa o login do funcionario. */
    public int encerrarSessoesDe(Usuario usuario) {
        return encerrarSessoesDe(usuario == null ? null : usuario.getLogin());
    }
}
