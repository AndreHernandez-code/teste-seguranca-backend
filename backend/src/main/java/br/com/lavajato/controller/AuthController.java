package br.com.lavajato.controller;

import br.com.lavajato.security.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextoRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SessionAuthenticationStrategy estrategiaDeSessao;
    private final LoginAttemptService tentativas;

    public AuthController(AuthenticationManager authManager,
                          SecurityContextRepository contextoRepository,
                          CsrfTokenRepository csrfTokenRepository,
                          SessionAuthenticationStrategy estrategiaDeSessao,
                          LoginAttemptService tentativas) {
        this.authManager = authManager;
        this.contextoRepository = contextoRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.estrategiaDeSessao = estrategiaDeSessao;
        this.tentativas = tentativas;
    }

    /** Corpo esperado: {"login": "...", "senha": "..."} */
    public record LoginRequest(
            @NotBlank(message = "Informe o login.") String login,
            @NotBlank(message = "Informe a senha.") String senha) {
    }

    /** So os perfis ROLE_* interessam ao front; FACTOR_* e detalhe interno do Spring Security. */
    private static List<String> perfisDe(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .toList();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest dados,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        // Normaliza ANTES de contar: o LoginAttemptService usa esta mesma forma
        // como chave, senao "Admin" e "admin" seriam contas diferentes e o
        // limite seria contornavel alternando maiusculas.
        String login = dados.login().trim().toLowerCase();
        String ip = tentativas.ipDe(request);

        // Barra antes de autenticar. Chamar o AuthenticationManager aqui gastaria
        // um BCrypt (custo 12) por tentativa, ou seja, o proprio bloqueio viraria
        // um caminho para sobrecarregar a CPU.
        if (!tentativas.podeTentar(login, ip)) {
            long segundos = tentativas.segundosParaLiberar(login, ip);
            log.warn("Tentativa de login recusada por bloqueio temporario. login='{}' ip={}.", login, ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(segundos))
                    .body(Map.of("status", 429,
                            "mensagem", "Muitas tentativas de login. Tente novamente em alguns minutos."));
        }

        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(login, dados.senha()));

            // A sessao e preparada pela MESMA estrategia que o Spring usaria se o
            // login fosse conduzido por ele (ver o bean sessionAuthenticationStrategy
            // em SecurityConfig). Ela faz tres coisas, nesta ordem:
            //
            //   1. ConcurrentSessionControlAuthenticationStrategy - aplica o limite
            //      de 3 sessoes e expira a mais antiga. Antes desta chamada o
            //      .maximumSessions(3) do SecurityConfig nao valia nada neste
            //      caminho: a configuracao existia e nunca era consultada.
            //   2. Troca o ID da sessao, contra session fixation. ISTO SUBSTITUI a
            //      invalidacao manual que existia aqui. Fazer as duas coisas seria
            //      invalidar duas vezes e trocar o ID duas vezes - alem de o
            //      securityContextRepository gravar o contexto numa referencia velha
            //      de request, porque a troca de ID do Spring mexe na referencia
            //      interna do request.
            //   3. RegisterSessionAuthenticationStrategy - registra a sessao nova no
            //      SessionRegistry. E o passo que faz o GerenciadorDeSessoes
            //      encontrar esta sessao depois, quando o funcionario for desativado
            //      ou rebaixado. Sem ele o registro fica vazio e encerrar sessao
            //      vira uma operacao sem efeito que ainda loga "nenhuma sessao
            //      aberta", como se ninguem estivesse logado.
            //
            // Chamar DEPOIS do authenticate() e obrigatorio: a estrategia de
            // concorrencia exige um Authentication ja bem-sucedido.
            estrategiaDeSessao.onAuthentication(auth, request, response);

            SecurityContext contexto = SecurityContextHolder.createEmptyContext();
            contexto.setAuthentication(auth);
            // Nao ha setContext() aqui de proposito: o SecurityContextHolderFilter
            // limpa o contexto ao fim da requisicao, mas o holder nao e lido depois
            // disto nesta mesma thread - quem responde e o repositorio gravado
            // abaixo. Gravar nos dois lugares so criaria duas fontes para o mesmo
            // dado, e a divergencia entre elas seria silenciosa.
            contextoRepository.saveContext(contexto, request, response);

            // Renova o token CSRF: o token emitido antes do login foi gerado para uma
            // sessao anonima. Se nao rotacionar, um atacante que fixou esse token
            // continua com um valor valido depois da autenticacao.
            CsrfToken novoToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(novoToken, request, response);

            tentativas.registrarSucesso(login);

            log.info("Login bem-sucedido para '{}'.", login);

            return ResponseEntity.ok(Map.of("login", auth.getName(), "perfis", perfisDe(auth)));
        } catch (AuthenticationException e) {
            tentativas.registrarFalha(login, ip);
            // Mensagem unica para usuario inexistente e senha errada: nao ajuda a enumerar logins.
            log.warn("Falha de login para '{}' vindo de {}.", login, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", 401, "mensagem", "Usuário ou senha inválidos."));
        }
    }

    /** O front chama isto no boot para saber se ja ha sessao ativa. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        return ResponseEntity.ok(Map.of("login", auth.getName(), "perfis", perfisDe(auth)));
    }

    /** Forca a emissao do cookie XSRF-TOKEN antes do primeiro POST. */
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, Object>> csrf(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
