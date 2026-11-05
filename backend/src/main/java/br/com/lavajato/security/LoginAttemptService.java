package br.com.lavajato.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita tentativas de login por usuario e por origem, para tornar a forca
 * bruta inviavel sem bloquear quem apenas errou a senha uma vez.
 *
 * Duas chaves, com limites diferentes, porque os ataques sao diferentes:
 *  - por LOGIN: alguem martelando uma conta especifica. Poucas tentativas bastam.
 *  - por IP: alguem varrendo muitos logins do mesmo lugar. Precisa de folga
 *    maior, senao um escritorio inteiro atras de um NAT se bloqueia sozinho.
 *
 * LIMITACAO CONHECIDA: o estado vive na memoria do processo. Ele zera quando a
 * aplicacao reinicia e NAO e compartilhado entre instancias - com dois servidores
 * atras de um balanceador, cada um conta metade das tentativas. Se um dia o
 * sistema rodar assim, trocar os dois mapas por um store compartilhado (Redis
 * ou tabela). Nao e um detalhe de implementacao: e uma mudanca de garantia.
 *
 * Por que memoria e nao banco aqui: o proposito e frear tentativa em massa. Um
 * registrador em banco escreveria a cada tentativa falha, ou seja, daria ao
 * atacante um caminho para sobrecarregar o banco. Em memoria, a defesa nao vira
 * o vetor.
 */
@Service
public class  LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Uma contagem de falhas dentro de uma janela deslizante. */
    private record Contagem(int tentativas, Instant expiraEm) {

        boolean expirou() {
            return Instant.now().isAfter(expiraEm);
        }

        Contagem incrementar(Duration janela) {
            return new Contagem(tentativas + 1, Instant.now().plus(janela));
        }
    }

    private final Map<String, Contagem> porLogin = new ConcurrentHashMap<>();
    private final Map<String, Contagem> porIp = new ConcurrentHashMap<>();

    private final int maxPorLogin;
    private final int maxPorIp;
    private final Duration janela;
    private final boolean confiarEmForwardedFor;

    public LoginAttemptService(
            @Value("${app.security.login.max-tentativas-usuario:5}") int maxPorLogin,
            @Value("${app.security.login.max-tentativas-ip:20}") int maxPorIp,
            @Value("${app.security.login.janela:PT15M}") Duration janela,
            @Value("${app.security.login.trust-forwarded-for:false}") boolean confiarEmForwardedFor) {
        this.maxPorLogin = maxPorLogin;
        this.maxPorIp = maxPorIp;
        this.janela = janela;
        this.confiarEmForwardedFor = confiarEmForwardedFor;
    }

    /**
     * O login esta livre? Nao revela qual das duas chaves bloqueou - quem esta
     * bloqueado nao precisa saber, e saber ajudaria a escolher outro alvo.
     */
    public boolean podeTentar(String login, String ip) {
        return !bloqueado(porLogin, chaveLogin(login), maxPorLogin)
                && !bloqueado(porIp, chaveIp(ip), maxPorIp);
    }

    /** Quantos segundos faltam para liberar. Usado no cabecalho Retry-After. */
    public long segundosParaLiberar(String login, String ip) {
        long porUsuario = restante(porLogin, chaveLogin(login), maxPorLogin);
        long porOrigem = restante(porIp, chaveIp(ip), maxPorIp);
        return Math.max(porUsuario, porOrigem);
    }

    /** Chamado quando a autenticacao FALHA. Soma nas duas chaves. */
    public void registrarFalha(String login, String ip) {
        incrementar(porLogin, chaveLogin(login));
        incrementar(porIp, chaveIp(ip));

        if (!podeTentar(login, ip)) {
            // Log sem senha: o suficiente para investigar depois.
            log.warn("Login bloqueado por excesso de tentativas. login='{}' ip={}.", login, ip);
        }
    }

    /**
     * Chamado quando a autenticacao da CERTO. Zera so a chave do login.
     *
     * Nao zera o IP de proposito: um atacante que controle uma conta valida
     * poderia limpar o proprio bloqueio de IP acertando a senha dela no meio da
     * varredura, e as tentativas contra as outras contas ficariam sem freio.
     */
    public void registrarSucesso(String login) {
        porLogin.remove(chaveLogin(login));
    }

    /** Descobre a origem. Ver a propriedade trust-forwarded-for. */
    public String ipDe(HttpServletRequest request) {
        if (!confiarEmForwardedFor) {
            return request.getRemoteAddr();
        }
        // So o PRIMEIRO valor interessa: os seguintes foram acrescentados por
        // proxies no caminho, e podem ter sido forjados pelo cliente.
        String cabecalho = request.getHeader("X-Forwarded-For");
        if (cabecalho == null || cabecalho.isBlank()) {
            return request.getRemoteAddr();
        }
        String primeiro = cabecalho.split(",")[0].trim();
        return primeiro.isEmpty() ? request.getRemoteAddr() : primeiro;
    }

    // ---- internos ----

    /**
     * O login e normalizado do mesmo jeito que o AuthController faz antes de
     * autenticar. Sem isso, "Admin" e "admin" contariam como contas diferentes e
     * o limite seria contornavel alternando maiusculas.
     */
    private String chaveLogin(String login) {
        return "login:" + (login == null ? "" : login.trim().toLowerCase());
    }

    private String chaveIp(String ip) {
        return "ip:" + (ip == null ? "" : ip);
    }

    private boolean bloqueado(Map<String, Contagem> mapa, String chave, int limite) {
        Contagem c = mapa.get(chave);
        return c != null && !c.expirou() && c.tentativas() >= limite;
    }

    private long restante(Map<String, Contagem> mapa, String chave, int limite) {
        Contagem c = mapa.get(chave);
        if (c == null || c.expirou() || c.tentativas() < limite) {
            return 0;
        }
        return Math.max(0, Duration.between(Instant.now(), c.expiraEm()).toSeconds());
    }

    private void incrementar(Map<String, Contagem> mapa, String chave) {
        // Aproveita a passagem para varrer entradas vencidas. Sem isso o mapa
        // cresceria para sempre com IPs sorteados por um atacante - virando ele
        // mesmo um consumo de memoria.
        mapa.entrySet().removeIf(e -> e.getValue().expirou());
        mapa.merge(chave, new Contagem(1, Instant.now().plus(janela)),
                (atual, novo) -> atual.incrementar(janela));
    }
}
