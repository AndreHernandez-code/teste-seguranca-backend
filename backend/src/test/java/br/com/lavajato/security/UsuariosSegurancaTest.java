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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Item 2 do hardening: /api/usuarios nao vaza hash, exige 12 caracteres de
 * senha e nunca deixa o sistema sem um ADMIN ativo.
 */
@SpringBootTest
@DisplayName("/api/usuarios - seguranca")
class UsuariosSegurancaTest {

    private static final String SENHA_ADMIN = "SenhaDeTesteMuitoLonga123";
    private static final String SPRING_SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private AuthenticationManager authManager;

    private MockMvc mockMvc;
    private MockHttpSession sessaoAdmin;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();

        // Os testes desativam e rebaixam usuarios, e o banco H2 e compartilhado
        // entre eles: sem restaurar o admin antes de cada um, um teste que
        // desativou o admin derruba a autenticacao de todos os seguintes.
        reativarAdminPrincipal();
        removerAdminsAuxiliares();

        sessaoAdmin = sessaoDe("admin", SENHA_ADMIN);
    }

    /** Garante o admin do seeder ativo e como ADMIN, aconteca o que acontecer. */
    private void reativarAdminPrincipal() {
        usuarios.findAll().stream()
                .filter(u -> "admin".equals(u.getLogin()))
                .findFirst()
                .ifPresent(u -> {
                    u.setPerfil(Perfil.ADMIN);
                    u.setAtivo(true);
                    usuarios.save(u);
                });
    }

    /** Remove admins criados por testes anteriores, para nao falsear "o ultimo". */
    private void removerAdminsAuxiliares() {
        usuarios.findAll().stream()
                .filter(u -> "admin.auxiliar".equals(u.getLogin()))
                .forEach(usuarios::delete);
    }

    @Test
    @DisplayName("GET /api/usuarios nao devolve senha nem hash")
    void naoVazaSenha() throws Exception {
        String corpo = mockMvc.perform(get("/api/usuarios").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain("senha");
        assertThat(corpo).doesNotContain("$2a$");
        assertThat(corpo).doesNotContain("$2b$");
    }

    @Test
    @DisplayName("criar usuario com senha de 11 caracteres responde 400")
    void senhaCurtaNaCriacaoEhRejeitada() throws Exception {
        mockMvc.perform(post("/api/usuarios")
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"curto.teste\",\"senha\":\"12345678901\","
                                + "\"nome\":\"Curto\",\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.senha").exists());
    }

    @Test
    @DisplayName("criar usuario sem senha responde 400")
    void senhaAusenteNaCriacaoEhRejeitada() throws Exception {
        mockMvc.perform(post("/api/usuarios")
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"sem.senha\",\"senha\":\"\","
                                + "\"nome\":\"Sem Senha\",\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.senha").exists());
    }

    @Test
    @DisplayName("editar so o nome, sem informar senha, e aceito")
    void editarSemSenhaMantemASenhaAtual() throws Exception {
        Long id = criarAtendente("editar.teste", "SenhaOriginalLonga123");
        String hashAntes = usuarios.findById(id).orElseThrow().getSenha();

        mockMvc.perform(put("/api/usuarios/" + id)
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"editar.teste\",\"senha\":\"\","
                                + "\"nome\":\"Nome Editado\",\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Nome Editado"));

        Usuario depois = usuarios.findById(id).orElseThrow();
        assertThat(depois.getSenha()).isEqualTo(hashAntes);
        assertThat(depois.getNome()).isEqualTo("Nome Editado");
    }

    @Test
    @DisplayName("editar com perfil nulo responde 400 - nao deixa o PUT pular a validacao")
    void editarComPerfilNuloEhRejeitado() throws Exception {
        // REGRESSAO de auditoria: o PUT validava SOMENTE o grupo Edicao.class.
        // As constraints de login/nome/perfil nao declaram grupos, entao
        // pertencem ao grupo Default - e o Bean Validation NAO inclui o Default
        // quando se pede apenas Edicao (a interface e vazia e nao estende
        // Default). Resultado: "perfil": null passava pela validacao e ia para
        // o banco, virando violacao de NOT NULL (409/500) em vez do 400 correto.
        //
        // A correcao inclui Default.class na lista de grupos validados do PUT
        // (ver o javadoc de UsuarioController.atualizar). Este teste trava isso.
        Long id = criarAtendente("perfil.nulo", "SenhaOriginalLonga123");

        mockMvc.perform(put("/api/usuarios/" + id)
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"perfil.nulo\",\"senha\":\"\","
                                + "\"nome\":\"Nome Valido\",\"perfil\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.perfil").exists());

        // E o perfil gravado continua o anterior: a requisicao nao alterou nada.
        assertThat(usuarios.findById(id).orElseThrow().getPerfil())
                .isEqualTo(Perfil.ATENDENTE);
    }

    @Test
    @DisplayName("editar com nome em branco responde 400 - constraint Default tambem no PUT")
    void editarComNomeEmBrancoEhRejeitado() throws Exception {
        // Mesmo defeito, pelo lado do nome: sem Default.class no PUT, um
        // "nome": "" tambem escapava da validacao.
        Long id = criarAtendente("nome.invalido", "SenhaOriginalLonga123");

        mockMvc.perform(put("/api/usuarios/" + id)
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"nome.invalido\",\"senha\":\"\","
                                + "\"nome\":\"\",\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nome").exists());
    }

    @Test
    @DisplayName("nao e possivel desativar o ultimo ADMIN ativo")
    void naoDesativaUltimoAdmin() throws Exception {
        Long idUnico = garantirAdminUnicoAtivo();

        mockMvc.perform(delete("/api/usuarios/" + idUnico)
                        .session(sessaoAdmin)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value(
                        org.hamcrest.Matchers.containsString("ultimo administrador ativo")));

        assertThat(usuarios.findById(idUnico).orElseThrow().isAtivo()).isTrue();
    }

    @Test
    @DisplayName("nao e possivel rebaixar o ultimo ADMIN ativo")
    void naoRebaixaUltimoAdmin() throws Exception {
        Long idUnico = garantirAdminUnicoAtivo();

        mockMvc.perform(put("/api/usuarios/" + idUnico)
                        .session(sessaoAdmin)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"admin\",\"senha\":\"\","
                                + "\"nome\":\"Administrador\",\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value(
                        org.hamcrest.Matchers.containsString("ultimo administrador ativo")));

        assertThat(usuarios.findById(idUnico).orElseThrow().getPerfil()).isEqualTo(Perfil.ADMIN);
    }

    @Test
    @DisplayName("havendo outro ADMIN ativo, desativar o primeiro e permitido")
    void desativaQuandoHaOutroAdmin() throws Exception {
        Usuario outro = new Usuario();
        outro.setLogin("admin.auxiliar");
        outro.setNome("Admin Auxiliar");
        outro.setPerfil(Perfil.ADMIN);
        outro.setAtivo(true);
        outro.setSenha(encoder.encode("SenhaAuxiliarLonga123"));
        usuarios.save(outro);

        Long idAdmin = usuarios.findAll().stream()
                .filter(u -> "admin".equals(u.getLogin()))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(delete("/api/usuarios/" + idAdmin)
                        .session(sessaoAdmin)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(usuarios.findById(idAdmin).orElseThrow().isAtivo()).isFalse();
    }

    // ---- helpers ----

    /**
     * Deixa exatamente um ADMIN ativo (o "admin" do seeder) e devolve o id dele,
     * desativando os demais direto no repositorio para nao depender da API.
     */
    private Long garantirAdminUnicoAtivo() {
        Long idAdmin = null;
        for (Usuario u : usuarios.findAll()) {
            if (u.getPerfil() == Perfil.ADMIN) {
                boolean ehAdminPrincipal = "admin".equals(u.getLogin());
                if (ehAdminPrincipal) {
                    u.setAtivo(true);
                    idAdmin = u.getId();
                } else {
                    u.setAtivo(false);
                }
                usuarios.save(u);
            }
        }
        return idAdmin;
    }

    private Long criarAtendente(String login, String senha) {
        Usuario u = new Usuario();
        u.setLogin(login);
        u.setNome("Criado no teste");
        u.setPerfil(Perfil.ATENDENTE);
        u.setAtivo(true);
        u.setSenha(encoder.encode(senha));
        return usuarios.save(u).getId();
    }

    private MockHttpSession sessaoDe(String login, String senha) {
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(login, senha));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute(SPRING_SECURITY_CONTEXT, ctx);
        return sessao;
    }

    private static SecurityMockMvcRequestPostProcessors.CsrfRequestPostProcessor csrf() {
        return SecurityMockMvcRequestPostProcessors.csrf();
    }
}
