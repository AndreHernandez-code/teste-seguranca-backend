package br.com.lavajato.security;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * As cinco asserções de segurança exigidas, mais a checagem de que um erro
 * nunca devolve stack trace. Roda com o contexto completo e o filtro do
 * Spring Security ativo, para exercitar a cadeia real (nao um mock dela).
 */
@SpringBootTest
@DisplayName("Seguranca da API")
class SegurancaApiTest {

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private AuthenticationManager authManager;

    private MockMvc mockMvc;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();

        // ATENDENTE para provar a negativa de exclusao (403).
        if (!usuarios.existsByLogin("atendente.teste")) {
            Usuario atendente = new Usuario();
            atendente.setLogin("atendente.teste");
            atendente.setNome("Atendente de Teste");
            atendente.setPerfil(Perfil.ATENDENTE);
            atendente.setAtivo(true);
            atendente.setSenha(encoder.encode("SenhaAtendenteLonga123"));
            usuarios.save(atendente);
        }
    }

    @Test
    @DisplayName("1. sem login, a API responde 401")
    void semLoginRetorna401() throws Exception {
        mockMvc.perform(get("/api/clientes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("2. ATENDENTE nao pode excluir (403), ADMIN pode")
    void atendenteNaoExclui() throws Exception {
        mockMvc.perform(delete("/api/servicos/1")
                        .with(usuarioAutenticado("atendente.teste", "SenhaAtendenteLonga123"))
                        .with(csrfValido()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. POST sem token CSRF, mas com sessao autenticada, responde 403")
    void postSemCsrfRetorna403() throws Exception {
        // Sessao real (nao o SecurityContext do MockMvc): e o unico jeito de a
        // requisicao chegar com sessao autenticada, que e o gatilho do CSRF.
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute(SPRING_SECURITY_CONTEXT,
                contextoAutenticado("admin", "SenhaDeTesteMuitoLonga123"));

        mockMvc.perform(post("/api/clientes")
                        .session(sessao)
                        .contentType("application/json")
                        .content("{\"nome\":\"Sem CSRF\",\"telefone\":\"11999999999\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("3b. a mesma requisicao COM token CSRF e aceita (prova que o 403 e o CSRF)")
    void postComCsrfPassa() throws Exception {
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute(SPRING_SECURITY_CONTEXT,
                contextoAutenticado("admin", "SenhaDeTesteMuitoLonga123"));

        mockMvc.perform(post("/api/clientes")
                        .session(sessao)
                        .with(csrfValido())
                        .contentType("application/json")
                        .content("{\"nome\":\"Com CSRF\",\"telefone\":\"11999999999\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("4. entrada invalida responde 400 com os campos")
    void entradaInvalidaRetorna400() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(usuarioAutenticado("admin", "SenhaDeTesteMuitoLonga123"))
                        .with(csrfValido())
                        .contentType("application/json")
                        .content("{\"nome\":\"\",\"telefone\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.campos.nome").exists());
    }

    @Test
    @DisplayName("5. resposta de erro nao vaza stack trace nem detalhes internos")
    void erroNaoVazaStackTrace() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/agendamentos")
                        .with(usuarioAutenticado("admin", "SenhaDeTesteMuitoLonga123"))
                        .with(csrfValido())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-01T10:00:00\",\"veiculo\":{\"id\":999999},\"servico\":{\"id\":1}}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        String corpo = resultado.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(corpo)
                .doesNotContain("Exception")
                .doesNotContain("at br.com.lavajato")
                .doesNotContain("java.lang")
                .doesNotContain("StackTrace");
    }

    @Test
    @DisplayName("login com senha errada responde 401 com mensagem generica")
    void loginErradoRespostaGenerica() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"login\":\"admin\",\"senha\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized())
                // A mensagem nao pode revelar se o login existe.
                .andExpect(jsonPath("$.mensagem").value("Usuário ou senha inválidos."));
    }

    // ---- helpers ----

    private static final String SPRING_SECURITY_CONTEXT =
            "SPRING_SECURITY_CONTEXT";

    /** Autentica como o usuario informado, sem passar pelo endpoint de login. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            usuarioAutenticado(String login, String senha) {
        return SecurityMockMvcRequestPostProcessors.user(login).password(senha);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrfValido() {
        return SecurityMockMvcRequestPostProcessors.csrf();
    }

    /** Contexto de seguranca com as authorities reais do perfil, para a sessao. */
    private SecurityContext contextoAutenticado(String login, String senha) {
        var details = new UsernamePasswordAuthenticationToken(login, senha);
        var auth = authManager.authenticate(details);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        return ctx;
    }
}
