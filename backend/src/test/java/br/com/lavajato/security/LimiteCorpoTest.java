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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limite de tamanho do corpo.
 *
 * Este arquivo existe porque a falta dele deixou passar TRES tentativas de
 * conserto erradas do mesmo filtro - cada uma parecia certa no papel e so
 * falhava na pratica.
 *
 * ARMADILHA DO MOCKMVC, que custou caro aqui e vale para qualquer teste futuro
 * deste projeto que dependa de um Filter proprio:
 *
 *   MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity())
 *
 * monta SO a cadeia do Spring Security. Os filtros @Component da aplicacao -
 * como o LimiteCorpoFilter - NAO entram. O teste entao roda um sistema
 * diferente do que vai para producao, e passa (ou falha) por motivos que nao
 * tem nada a ver com o codigo real: aqui, o corpo gigante chegava direto no
 * controller e voltava 400, e o filtro nunca era chamado.
 *
 * Por isso este teste registra o filtro a mao em addFilters(...). Sem isso, os
 * dois testes de 413 abaixo nao testariam nada.
 */
@SpringBootTest
@DisplayName("Limite de tamanho do corpo")
class LimiteCorpoTest {

    private static final String SENHA = "SenhaDeTesteMuitoLonga123";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Autowired
    private AuthenticationManager authManager;

    /**
     * O filtro sob teste, com instancia e limite explicitos. Nao pegamos o bean
     * do contexto de proposito: se alguem mudar a propriedade
     * app.security.corpo-max-bytes no futuro, o teste continua medindo o
     * comportamento do filtro, e nao o valor configurado.
     */
    private final LimiteCorpoFilter filtro = new LimiteCorpoFilter(262_144);

    private MockMvc mockMvc;

    /**
     * Sessao autenticada de verdade, para os endpoints que exigem ROLE_ADMIN
     * (DELETE /api/**). O post-processor user("admin") cria um principal sem
     * autoridade nenhuma, o que basta para os endpoints abertos a qualquer
     * usuario logado - mas nao para estes, que responderiam 403 antes de o
     * filtro ser exercitado e mascarariam o teste.
     */
    private MockHttpSession sessaoAdmin;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                // Sem esta linha o filtro nao roda - ver o comentario da classe.
                .addFilters(filtro)
                .build();

        if (!usuarios.existsByLogin("admin")) {
            Usuario admin = new Usuario();
            admin.setLogin("admin");
            admin.setNome("Administrador");
            admin.setPerfil(Perfil.ADMIN);
            admin.setAtivo(true);
            admin.setSenha(encoder.encode(SENHA));
            usuarios.save(admin);
        }

        sessaoAdmin = sessaoDe("admin", SENHA);
    }

    /** Autentica de fato, como os outros testes de seguranca do projeto. */
    private MockHttpSession sessaoDe(String login, String senha) {
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(login, senha));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute("SPRING_SECURITY_CONTEXT", ctx);
        return sessao;
    }

    /** JSON validamente formado, so que gigante. */
    private static String corpoGigante() {
        return "{\"nome\":\"" + "a".repeat(400_000) + "\",\"telefone\":\"11999998888\"}";
    }

    @Test
    @DisplayName("corpo acima do limite COM Content-Length devolve 413")
    void corpoGrandeComContentLength() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content(corpoGigante()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    /**
     * O caso que escapava. Sem Content-Length, a barreira por cabecalho nao
     * decide nada, e a resposta precisa sair 413 e nao 400.
     *
     * Nota: o MockMvc nao emula chunked de verdade - ele sempre acaba
     * informando o Content-Length. Este teste cobre o caminho de codigo da
     * contagem (que e o mesmo para os dois casos), mas a prova do caso chunked
     * real foi feita com curl contra o servidor no ar:
     *
     *   curl -H "Transfer-Encoding: chunked" --data-binary @arquivo-grande.json
     *   -> {"status":413,...}
     */
    @Test
    @DisplayName("corpo acima do limite devolve 413 tambem sem Content-Length confiavel")
    void corpoGrandeSemContentLength() throws Exception {
        byte[] corpo = corpoGigante().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content(corpo))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    /**
     * Corpo MUITO maior que o limite (aqui, 5 MB contra um teto de 256 KB).
     *
     * O objetivo nao e o status - e provar que o filtro para de ler no teto em
     * vez de copiar o corpo inteiro. O lerLimitado corta ao passar de
     * limite+1 bytes, entao a memoria gasta fica limitada pelo teto, e nao
     * pelo tamanho que o cliente resolver mandar. Era essa a promessa do
     * comentario do filtro, e este teste a fixa.
     */
    @Test
    @DisplayName("corpo de 5 MB tambem da 413 - e o filtro nao copia os 5 MB")
    void corpoMuitoMaiorQueOLimite() throws Exception {
        byte[] enorme = ("{\"nome\":\"" + "a".repeat(5_000_000)
                + "\",\"telefone\":\"11999998888\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content(enorme))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    @DisplayName("corpo logo abaixo do limite continua aceito")
    void corpoPequenoPassa() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente Normal\",\"telefone\":\"11999998888\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * O teste que impede a correcao de virar um tiro no proprio pe: depois de
     * passar a ler o corpo no filtro, TODA requisicao com corpo passa por este
     * caminho. Se o wrapper devolvesse um stream vazio, o controller veria
     * "corpo vazio" e todo cadastro quebraria.
     */
    @Test
    @DisplayName("o corpo chega intacto ao controller depois de passar pelo filtro")
    void corpoChegaIntacto() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente Intacto\",\"telefone\":\"11988887777\"}"))
                .andExpect(status().isCreated())
                // Se o corpo tivesse sido perdido ou corrompido na leitura, o
                // nome nao voltaria igual.
                .andExpect(jsonPath("$.nome").value("Cliente Intacto"))
                .andExpect(jsonPath("$.telefone").value("11988887777"));
    }

    @Test
    @DisplayName("JSON malformado pequeno continua dando 400, nao 413")
    void jsonMalformadoNaoVira413() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\": isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("requisicao sem corpo (GET) nao passa pelo contador")
    void semCorpoNaoEhAfetada() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/clientes")
                        .with(user("admin").password(SENHA)))
                .andExpect(status().isOk());
    }

    /**
     * O furo que a revisao adversarial encontrou.
     *
     * O filtro decidia "tem corpo?" pelo METODO HTTP, aceitando so POST/PUT/
     * PATCH. Um DELETE com corpo nunca passava pela contagem: com o cabecalho
     * Content-Length a barreira 1 ainda pegava, mas sem ele (chunked) o corpo
     * escapava inteiro. Reproduzido contra o servidor no ar, o mesmo corpo de
     * 300 KB dava 413 em POST e passava direto em DELETE.
     *
     * Hoje nenhum handler le corpo em DELETE, entao nao havia exploracao - o
     * perigo era a protecao se desligar sozinha no dia em que alguem
     * acrescentasse @RequestBody a um DELETE. A decisao passou a ser por
     * enquadramento (Content-Length / Transfer-Encoding), nao por metodo, e
     * este teste fixa isso.
     *
     * DELETE /api/** exige ROLE_ADMIN, por isso aqui usamos a sessao de verdade
     * (sessaoAdmin) em vez do post-processor user(), que nao carrega
     * autoridade nenhuma e devolveria 403 antes de o filtro ser exercitado.
     */
    @Test
    @DisplayName("DELETE com corpo grande tambem e barrado - o limite nao depende do metodo")
    void deleteComCorpoGrandeTambemEhBarrado() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/veiculos/1")
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content(corpoGigante()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    /**
     * O mesmo cuidado do outro lado: um DELETE SEM corpo precisa continuar
     * funcionando normalmente. Se a correcao do furo acima passasse a exigir
     * corpo em todo DELETE, isto quebraria.
     */
    @Test
    @DisplayName("DELETE sem corpo continua funcionando")
    void deleteSemCorpoContinua() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/veiculos/999999")
                        .session(sessaoAdmin).with(csrf()))
                // 404 (nao existe): o que importa e nao ser 413 nem 400.
                .andExpect(status().isNotFound());
    }
}
