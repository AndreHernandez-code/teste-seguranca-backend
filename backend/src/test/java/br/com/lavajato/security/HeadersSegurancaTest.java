package br.com.lavajato.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 4 - cabecalhos de seguranca.
 *
 * Tres coisas que este teste NAO prova, e que ficam registradas aqui para
 * ninguem confundir verde com "testado":
 *
 *  1. Nao prova que a CSP e a certa para o front, so que ela e a configurada.
 *     O que ele prova e que a politica nao tem 'unsafe-inline' nem
 *     'unsafe-eval' - se alguem acrescentar uma dessas para "resolver" um
 *     problema, o teste vermelho aponta o que foi afrouxado.
 *
 *  2. Nao prova que o HSTS nao quebra o dev. Em MockMvc o request nao e
 *     "seguro", entao o HstsHeaderWriter do Spring nao escreve o cabecalho -
 *     e justamente isso que o teste confirma (ausencia sob HTTP). O caminho
 *     positivo (sob HTTPS) so se verifica com o servidor no ar atras de TLS.
 *
 *  3. Nao exercita o LimiteCorpoFilter nem os demais filtros @Component da
 *     aplicacao: webAppContextSetup(...).apply(springSecurity()) monta apenas
 *     a cadeia do Spring Security, onde o HeaderWriterFilter vive.
 */
@SpringBootTest
@DisplayName("Etapa 4 - cabecalhos de seguranca")
class HeadersSegurancaTest {

    /** Precisa ser a MESMA string de application.properties (app.security.csp.politica). */
    private static final String CSP_ESPERADA =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; "
            + "base-uri 'self'; form-action 'self'";

    @Autowired
    private WebApplicationContext contexto;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("o HTML do painel vem com Content-Security-Policy")
    void htmlTemCsp() throws Exception {
        mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", CSP_ESPERADA));
    }

    @Test
    @DisplayName("a CSP nao abre 'unsafe-inline' nem 'unsafe-eval'")
    void cspNaoTemUnsafe() throws Exception {
        MvcResult resultado = mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String csp = resultado.getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).isNotNull();
        // Os dois que transformam a CSP em decoracao. Ver o comentario no topo
        // da classe: este e o assert que pega o afrouxamento silencioso.
        assertThat(csp).doesNotContain("unsafe-inline");
        assertThat(csp).doesNotContain("unsafe-eval");
        // script-src e o que mais importa num XSS; sem ele a diretiva cai em
        // default-src e o teste deixaria de medir o que promete.
        assertThat(csp).contains("script-src 'self'");
    }

    @Test
    @DisplayName("o HTML do painel vem com X-Frame-Options: DENY")
    void htmlTemFrameOptionsDeny() throws Exception {
        mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("o HTML do painel vem com X-Content-Type-Options: nosniff")
    void htmlTemNoSniff() throws Exception {
        mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("o HTML do painel nega camera, microfone e geolocalizacao")
    void htmlTemPermissionsPolicy() throws Exception {
        MvcResult resultado = mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String politica = resultado.getResponse().getHeader("Permissions-Policy");

        assertThat(politica).isNotNull();
        assertThat(politica).contains("camera=()")
                .contains("microphone=()")
                .contains("geolocation=()");
    }

    @Test
    @DisplayName("a resposta de erro do login tambem leva os cabecalhos")
    void respostaDeErroTambemTemCabecalhos() throws Exception {
        // O HeaderWriterFilter escreve no fim do encadeamento, com o status ja
        // definido. Se o 401 do AuthController saisse sem cabecalho, a pagina de
        // login - que e onde um XSS mais doeria - ficaria sem CSP.
        mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"ninguem\",\"senha\":\"errada-mas-longa\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy", CSP_ESPERADA))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("sob HTTP o HSTS NAO e emitido (nao prende o dev em HTTPS)")
    void semHttpsNaoEmiteHsts() throws Exception {
        // Este e o teste que protege o ambiente de desenvolvimento. O
        // HstsHeaderWriter padrao so escreve quando request.isSecure(); o
        // MockMvc monta o request como HTTP puro. Se alguem trocar o writer por
        // um que escreve sempre (ou passar includeSubDomains sem revisar), e
        // aqui que aparece - antes de prender o navegador de todo mundo em
        // HTTPS por meses.
        MvcResult resultado = mockMvc().perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(resultado.getResponse().getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("os assets estaticos NAO saem com o no-store do Spring Security")
    void assetsNaoSaoMarcadosComoNoStore() throws Exception {
        // O padrao do Spring Security escreve em toda resposta
        //   Cache-Control: no-cache, no-store, max-age=0, must-revalidate
        // Sem o .cacheControl(disable) do SecurityConfig, o app.js (43 KB) e o
        // styles.css (21 KB) sao rebaixados do servidor a cada F5 - nao quebra
        // nada, so fica lento e ninguem percebe olhando a tela.
        //
        // Este teste e o unico que pega a reativacao daquele padrao. Nao da para
        // medi-lo pelo MockMvc dos outros testes (que monta so a cadeia do
        // Security e nao passa pelo Tomcat); o que se mede aqui e o cabecalho
        // que a cadeia de escrita do Security produz para um GET de asset.
        for (String asset : new String[]{"/app.js", "/styles.css", "/"}) {
            MvcResult resultado = mockMvc().perform(get(asset))
                    .andExpect(status().isOk())
                    .andReturn();

            String cacheControl = resultado.getResponse().getHeader("Cache-Control");

            assertThat(cacheControl)
                    .as("%s nao pode sair com no-store: o navegador passaria a "
                            + "rebaixar o arquivo inteiro a cada navegacao", asset)
                    .satisfies(valor -> {
                        if (valor != null) {
                            assertThat(valor).doesNotContain("no-store");
                        }
                    });
        }
    }
}
