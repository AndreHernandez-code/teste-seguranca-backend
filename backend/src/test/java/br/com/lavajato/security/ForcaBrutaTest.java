package br.com.lavajato.security;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 2: limite de tentativas de login.
 *
 * Janela curta e limites baixos SO NESTE TESTE, para nao esperar 15 minutos
 * nem disparar 20 requisicoes. A logica exercitada e a mesma de producao.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.security.login.max-tentativas-usuario=3",
        "app.security.login.max-tentativas-ip=5",
        "app.security.login.janela=PT30S"
})
@DisplayName("Forca bruta no login")
class ForcaBrutaTest {

    private static final String SENHA_ADMIN = "SenhaDeTesteMuitoLonga123";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private LoginAttemptService tentativas;

    private MockMvc mockMvc;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();

        // O servico guarda estado em memoria e o contexto do Spring e reaproveitado
        // entre os testes: sem limpar, o bloqueio de um teste vaza para o seguinte
        // e a ordem de execucao passa a decidir o resultado.
        reiniciarContadores();

        if (!usuarios.existsByLogin("admin")) {
            Usuario admin = new Usuario();
            admin.setLogin("admin");
            admin.setNome("Administrador");
            admin.setPerfil(Perfil.ADMIN);
            admin.setAtivo(true);
            admin.setSenha(encoder.encode(SENHA_ADMIN));
            usuarios.save(admin);
        }
    }

    @Test
    @DisplayName("apos o limite de falhas, a proxima tentativa recebe 429")
    void bloqueiaAposLimiteDeFalhas() throws Exception {
        for (int i = 0; i < 3; i++) {
            errarSenha("admin");
        }

        mockMvc.perform(login("admin", SENHA_ADMIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("bloqueado, nem a senha CORRETA passa - o bloqueio nao e contornavel")
    void senhaCorretaNaoFuraOBloqueio() throws Exception {
        for (int i = 0; i < 3; i++) {
            errarSenha("admin");
        }

        // A senha esta certa, mas a requisicao nem chega ao AuthenticationManager.
        mockMvc.perform(login("admin", SENHA_ADMIN))
                .andExpect(status().isTooManyRequests());

        // E continua bloqueado na sequencia.
        mockMvc.perform(login("admin", SENHA_ADMIN))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a mensagem do 429 e generica e nao revela se o login existe")
    void mensagemNaoRevelaSeOLoginExiste() throws Exception {
        for (int i = 0; i < 3; i++) {
            errarSenha("nao.existe.mesmo");
        }

        String corpo = mockMvc.perform(login("nao.existe.mesmo", "qualquer"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(corpo)
                .doesNotContain("nao.existe.mesmo")
                .doesNotContain("inexistente")
                .doesNotContain("não encontrado")
                .doesNotContain("nao encontrado");
    }

    @Test
    @DisplayName("login normalizado: 'ADMIN' conta junto com 'admin'")
    void contagemIgnoraMaiusculas() throws Exception {
        errarSenha("ADMIN");
        errarSenha("Admin");
        errarSenha("admin");

        // Tres falhas somadas na mesma chave: a quarta ja esta bloqueada.
        mockMvc.perform(login("ADMIN", SENHA_ADMIN))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("sucesso zera o contador do proprio login")
    void sucessoZeraOContador() throws Exception {
        errarSenha("admin");
        errarSenha("admin");

        mockMvc.perform(login("admin", SENHA_ADMIN))
                .andExpect(status().isOk());

        // Contador zerado: da para errar de novo sem cair no bloqueio.
        mockMvc.perform(login("admin", "errada-de-novo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("o bloqueio por IP dispara ao alternar logins diferentes")
    void bloqueioPorIpPegaVarreduraDeLogins() throws Exception {
        // Limite por usuario e 3, por IP e 5. Alternando os logins, nenhuma chave
        // de usuario passa de 2 falhas - quem barra e o IP.
        errarSenha("alvo1");
        errarSenha("alvo2");
        errarSenha("alvo1");
        errarSenha("alvo2");
        errarSenha("alvo3");

        mockMvc.perform(login("alvo4", "qualquer"))
                .andExpect(status().isTooManyRequests());
    }

    // ---- helpers ----

    private void errarSenha(String login) throws Exception {
        mockMvc.perform(login(login, "senha-errada-de-proposito"))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            login(String login, String senha) {
        return post("/api/auth/login")
                .contentType("application/json")
                .content("{\"login\":\"" + login + "\",\"senha\":\"" + senha + "\"}");
    }

    /**
     * Zera os contadores entre testes. O servico nao expoe isso de proposito -
     * e um detalhe interno, e expor um "reset" na API seria uma porta para o
     * atacante limpar o proprio bloqueio. Por isso o teste acessa os mapas.
     */
    private void reiniciarContadores() {
        limparMapa("porLogin");
        limparMapa("porIp");
    }

    private void limparMapa(String nome) {
        var mapa = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
                .getField(tentativas, nome);
        mapa.clear();
    }
}
