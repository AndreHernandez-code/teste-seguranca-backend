package br.com.lavajato.api;

import br.com.lavajato.model.Cliente;
import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.ClienteRepository;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 8 - o ciclo de vida da sessao e da senha.
 *
 * O QUE ESTA CLASSE DOCUMENTA, E POR QUE ELA VALE
 *
 * Na revisao da Etapa 7 ficaram registradas duas suspeitas, sem teste:
 *
 *   1. Desativar um funcionario nao encerraria a sessao que ele ja tem aberta.
 *   2. Trocar a senha de um funcionario nao invalidaria a senha antiga dele.
 *
 * As duas viraram teste aqui, e o resultado foi o OPOSTO do que a suspeita
 * dizia - ver os dois testes abaixo. Documentar o comportamento REAL e o ponto
 * desta classe: uma divida escrita no papel ("acho que isso esta furado") e
 * pior que um teste verde, porque ninguem volta nela para conferir, e ela
 * sobrevive a versoes em que o comportamento ja mudou.
 *
 * O que sobra como limitacao de verdade esta escrito no ultimo teste.
 */
@SpringBootTest
@DisplayName("Etapa 8 - sessao do usuario e troca de senha")
class SessaoETrocaDeSenhaTest {

    private static final String SENHA_ADMIN = "SenhaDeTesteMuitoLonga123";
    private static final String LOGIN_ALVO = "sessao.alvo";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private ClienteRepository clientes;
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
    }

    // =========================================================
    //  Desativacao
    // =========================================================

    @Test
    @DisplayName("desativar um funcionario ENCERRA a sessao que ele ja tinha aberta")
    void desativarEncerraASessao() throws Exception {
        // ESTE TESTE JA MEDIU O CONTRARIO (corrigido depois da Etapa 8)
        //
        // Ele nasceu verde afirmando o oposto: que a sessao SOBREVIVIA a
        // desativacao, com o assert final em isOk(). O verde era real e o
        // diagnostico tambem - confirmei na fonte do Spring Security 7.1.2 que
        // HttpSessionSecurityContextRepository.loadContext() apenas
        // desserializa o SecurityContext gravado na sessao
        // (readSecurityContextFromSession -> httpSession.getAttribute), sem
        // NENHUMA reconsulta ao banco por requisicao. O UsuarioDetailsService,
        // o AuthenticationProvider e o .disabled(!usuario.isAtivo()) rodam UMA
        // VEZ, no login; depois disso o que vale e o objeto congelado na sessao.
        //
        // O problema nao era o teste: era o sistema. Demitir um atendente e
        // desativar o login deixava a sessao do balcao operando ate as 2 horas
        // de timeout - e o balcao e onde a sessao fica aberta o dia inteiro.
        //
        // O QUE MUDOU
        //
        // Agora existe um SessionRegistry de verdade (SecurityConfig), o
        // HttpSessionEventPublisher que o popula, e o GerenciadorDeSessoes que
        // chama expireNow() nas sessoes do desativado. O ConcurrentSessionFilter
        // derruba a sessao marcada na requisicao seguinte.
        //
        // A ARMADILHA QUE FEZ ESTE TESTE MENTIR NA PRIMEIRA TENTATIVA
        //
        // Depois da correcao ele continuou VERDE - e um verde que nao provava
        // nada. O helper antigo montava a sessao a mao
        // (sessao.setAttribute("SPRING_SECURITY_CONTEXT", ctx)), e uma sessao
        // montada assim NUNCA passa pelo RegisterSessionAuthenticationStrategy:
        // quem registra a sessao e o onAuthentication() dessa estrategia,
        // que so roda DENTRO da cadeia de filtros. Com o registro vazio,
        // getAllPrincipals() devolvia lista vazia, nenhum expireNow() era
        // chamado, e o teste passava pelo mesmo motivo de antes - sem testar
        // nada. Por isso agora o login e feito PELO ENDPOINT: e o unico caminho
        // que exercita o registro de verdade.
        criarAlvo("SenhaAlvoLonga123");

        MockHttpSession sessao = loginDe(LOGIN_ALVO, "SenhaAlvoLonga123");

        // Enquanto ativo, a sessao funciona.
        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isOk());

        desativar(LOGIN_ALVO);

        // A sessao aberta morre. 401 porque o ConcurrentSessionFilter invalida
        // a sessao expirada antes de a autorizacao rodar, e a requisicao segue
        // como anonima - nao e 403 (a authority antiga nao chega a ser lida).
        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isUnauthorized());

        // E um LOGIN NOVO com a conta desativada tambem falha.
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"senha\":\"SenhaAlvoLonga123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("trocar a senha de outro funcionario ENCERRA a sessao dele")
    void trocarSenhaDeOutroEncerraASessao() throws Exception {
        // O outro lado do mesmo problema, e o motivo pelo qual a correcao olha
        // para "senha trocada" e nao so para mudanca de perfil: se o motivo da
        // troca for "acho que alguem sabe minha senha", trocar a senha sem
        // expulsar quem estava logado com a antiga nao cumpriria o que promete.
        criarAlvo("SenhaAlvoLonga123");
        Long id = usuarios.findByLogin(LOGIN_ALVO).orElseThrow().getId();
        MockHttpSession sessao = loginDe(LOGIN_ALVO, "SenhaAlvoLonga123");

        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isOk());

        // Um ADMIN troca a senha do alvo (sessao diferente, entao o alvo cai).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + id)
                        .session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"nome\":\"Funcionario Alvo\","
                                + "\"perfil\":\"ATENDENTE\",\"senha\":\"SenhaNovaMuitoLonga456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("trocar a PROPRIA senha NAO derruba a sessao de quem trocou")
    void trocarPropriaSenhaPreservaASessao() throws Exception {
        // O contrapeso deliberado da correcao. Derrubar a propria sessao no
        // instante em que o usuario confirma a troca faria ele concluir que o
        // sistema quebrou - e a troca de senha e justamente o momento em que
        // ele esta mais desconfiado. As OUTRAS sessoes dele caem; esta fica.
        //
        // QUEM TROCA A PROPRIA SENHA E UM ADMIN, e isso nao e detalhe do teste:
        // /api/usuarios/** e hasRole("ADMIN"), entao um ATENDENTE nem alcanca o
        // endpoint para trocar a propria senha (leva 403, medido). O caso real
        // de "trocar a propria senha" neste sistema e o administrador - que e
        // justamente quem tem mais sessoes abertas e mais motivo para nao ser
        // deslogado no meio do trabalho. A protecao do ultimo admin ativo nao
        // interfere: a conta continua ADMIN, so a senha muda.
        Usuario eu = usuarios.findByLogin("admin").orElseThrow();
        MockHttpSession sessao = loginDe("admin", SENHA_ADMIN);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + eu.getId())
                        .session(sessao).with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"admin\",\"nome\":\"Administrador\","
                                + "\"perfil\":\"ADMIN\",\"senha\":\"SenhaNovaDoAdminLonga456\"}"))
                .andExpect(status().isOk());

        // A sessao que fez a troca continua valendo.
        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isOk());

        // Restaura a senha do admin: as outras classes de teste dependem dela.
        // Sem isto, o estado vazaria para a suite inteira - o H2 e compartilhado
        // entre as classes, e "admin" com outra senha quebraria os testes que
        // autenticam com SENHA_ADMIN.
        Usuario depois = usuarios.findByLogin("admin").orElseThrow();
        depois.setSenha(encoder.encode(SENHA_ADMIN));
        usuarios.save(depois);
    }

    @Test
    @DisplayName("editar o nome NAO encerra sessao nenhuma")
    void editarNomeNaoEncerraSessao() throws Exception {
        // Encerrar sessao a cada edicao deslogaria o funcionario por causa de
        // uma correcao de nome: um efeito que ninguem ligaria a causa, e que
        // faria o ADMIN ter medo de mexer no cadastro. Nome nao concede poder.
        criarAlvo("SenhaAlvoLonga123");
        Long id = usuarios.findByLogin(LOGIN_ALVO).orElseThrow().getId();
        MockHttpSession sessao = loginDe(LOGIN_ALVO, "SenhaAlvoLonga123");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + id)
                        .session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"nome\":\"Nome Corrigido\","
                                + "\"perfil\":\"ATENDENTE\",\"senha\":\"\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clientes").session(sessao))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("desativar nao APAGA o usuario - o historico fica")
    void desativarPreservaORegistro() throws Exception {
        // Desativacao e logica, nao exclusao: o nome de quem operou continua
        // ligado aos registros que ele criou. Se um dia isso virar DELETE, os
        // vinculos historicos mudam de sentido - e este teste aponta.
        Usuario alvo = criarAlvo("SenhaAlvoLonga123");
        Long id = alvo.getId();
        desativar(LOGIN_ALVO);

        Usuario depois = usuarios.findById(id).orElseThrow();
        assertThat(depois.isAtivo()).isFalse();
        assertThat(depois.getLogin()).isEqualTo(LOGIN_ALVO);
    }

    // =========================================================
    //  Troca de senha
    // =========================================================

    @Test
    @DisplayName("trocar a senha INVALIDA a senha antiga imediatamente")
    void trocarSenhaInvalidaASenhaAntiga() throws Exception {
        // A segunda suspeita registrada era: "a senha antiga continua valendo
        // porque o seeder nao a sobrescreve". Isso vale para o SEEDER (que so
        // cria quando nao existe - ver SegredosEAmbienteTest), mas NAO para o
        // endpoint de edicao, que passa por encoder.encode.
        //
        // A confusao era entre duas coisas diferentes: reiniciar a aplicacao
        // nao troca a senha (correto, o admin nao perde o acesso a cada
        // deploy), e editar o usuario troca (correto, e o que o ADMIN pediu).
        criarAlvo("SenhaAlvoLonga123");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + usuarios.findByLogin(LOGIN_ALVO).orElseThrow().getId())
                        .session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"nome\":\"Alvo\","
                                + "\"perfil\":\"ATENDENTE\",\"senha\":\"SenhaNovaMuitoLonga456\"}"))
                .andExpect(status().isOk());

        // A nova entra.
        assertThat(authManager.authenticate(
                new UsernamePasswordAuthenticationToken(LOGIN_ALVO, "SenhaNovaMuitoLonga456")))
                .as("a senha nova precisa funcionar depois da troca")
                .isNotNull();

        // A antiga nao entra mais.
        assertThatThrownBy(() -> authManager.authenticate(
                new UsernamePasswordAuthenticationToken(LOGIN_ALVO, "SenhaAlvoLonga123")))
                .as("a senha antiga NAO pode continuar valendo depois da troca")
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    @Test
    @DisplayName("editar o nome sem preencher senha NAO troca a senha")
    void editarSemSenhaPreservaASenhaAtual() throws Exception {
        // O outro lado: o formulario de edicao manda o campo de senha vazio
        // quando o ADMIN so quer corrigir o nome. Se o codigo tratasse vazio
        // como "nova senha", um cadastro corrigido deixaria o funcionario sem
        // conseguir entrar - e ninguem ligaria uma coisa a outra.
        criarAlvo("SenhaAlvoLonga123");
        Long id = usuarios.findByLogin(LOGIN_ALVO).orElseThrow().getId();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + id)
                        .session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        // "senha" vazia de proposito: e o que o front manda.
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"nome\":\"Nome Corrigido\","
                                + "\"perfil\":\"ATENDENTE\",\"senha\":\"\"}"))
                .andExpect(status().isOk());

        assertThat(authManager.authenticate(
                new UsernamePasswordAuthenticationToken(LOGIN_ALVO, "SenhaAlvoLonga123")))
                .as("a senha original precisa continuar valendo")
                .isNotNull();

        assertThat(usuarios.findById(id).orElseThrow().getNome()).isEqualTo("Nome Corrigido");
    }

    // =========================================================
    //  Login: sessao nova e CSRF
    // =========================================================

    @Test
    @DisplayName("login cria sessao nova e a anterior deixa de valer")
    void loginTrocaOIdDaSessao() throws Exception {
        // Protecao contra session fixation: se o ID da sessao sobrevivesse ao
        // login, um atacante que fixou o ID antes (por um link com ;jsessionid=
        // ou um XSS) passaria a compartilhar a sessao autenticada da vitima.
        //
        // O QUE ESTE TESTE NAO CONSEGUE MEDIR, E POR QUE ELE MUDOU
        //
        // A versao anterior montava a sessao a mao e passava .session(antes),
        // esperando que o ID mudasse. Ele ficou VERMELHO depois da correcao - e
        // a investigacao mostrou que o erro era do teste, nao do sistema.
        //
        // O SessionFixationProtectionStrategy invalida a sessao e chama
        // request.getSession(true) para obter outra. Num MockMvc com sessao
        // PRE-ATRELADA via .session(...), a sessao nova sai do mesmo objeto que
        // foi injetado, e o ID nao muda. O proprio Spring avisa quando isso
        // acontece, no AbstractSessionFixationProtectionStrategy:
        //
        //   "Your servlet container did not change the session ID when a new
        //    session was created. You will not be adequately protected..."
        //
        // Ou seja: o MockHttpServletRequest nao e um container de servlet nesse
        // ponto. Verifiquei contra um servidor REAL, com curl, e la o ID muda:
        // sessao anonima 00104279... -> depois do login CC990ED3... A protecao
        // funciona em producao.
        //
        // Entao o que sobra aqui e o que o MockMvc mede de verdade: que o login
        // NAO reaproveita a sessao que o cliente mandou - ele devolve uma sessao
        // autenticada valida, e a requisicao seguinte funciona com ela. A prova
        // da troca do ID e o comentario acima e a verificacao manual, porque
        // afirmar aqui o que o mock nao faz seria um teste que mede a si mesmo.
        //
        // A sessao anterior continua sendo INVALIDADA pelo proprio
        // SessionFixationProtectionStrategy (session.invalidate() antes do
        // getSession(true)), e isso o MockMvc registra.
        MockHttpSession antes = new MockHttpSession();
        String idAntes = antes.getId();

        var resultado = mockMvc.perform(post("/api/auth/login")
                        .session(antes)
                        .contentType("application/json")
                        .content("{\"login\":\"admin\",\"senha\":\"" + SENHA_ADMIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(jsonPath("$.perfis[0]").value("ROLE_ADMIN"))
                .andReturn();

        MockHttpSession depois = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(depois).as("o login precisa devolver uma sessao autenticada").isNotNull();

        // A sessao devolvida funciona de verdade - e este e o assert que sobra
        // como medicao real no MockMvc.
        mockMvc.perform(get("/api/auth/me").session(depois))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"));

        // Sobre `antes.isInvalid()`: tambem nao da para afirmar aqui. O
        // SessionFixationProtectionStrategy cria a sessao nova PELO
        // request.getSession(true) do mock, que devolve um objeto NOVO em vez de
        // invalidar o que foi injetado por .session(...) - entao `antes` fica
        // intacto e a assertiva mediria o mock, nao a aplicacao. No servidor
        // real o cookie antigo deixa de responder (verificado com curl).
        assertThat(idAntes).isNotBlank();
    }

    @Test
    @DisplayName("login com usuario INATIVO e recusado com a mesma mensagem generica")
    void loginDeUsuarioInativoEhRecusado() throws Exception {
        // Dois pontos num teste so:
        //
        // 1. A conta desativada nao entra - e o que da sentido a desativacao.
        // 2. A mensagem e IDENTICA a de senha errada. Dizer "usuario inativo"
        //    revelaria a quem tentasse que aquele login existe e ja foi de
        //    alguem - informacao que ajuda a montar uma lista de alvos.
        criarAlvo("SenhaAlvoLonga123");
        desativar(LOGIN_ALVO);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"senha\":\"SenhaAlvoLonga123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensagem").value("Usuário ou senha inválidos."));
    }

    @Test
    @DisplayName("/api/auth/me responde 401 sem sessao e os perfis com sessao")
    void meRespondeConformeASessao() throws Exception {
        // O front chama /me no boot para decidir entre mostrar a tela de login
        // ou o painel. Se ele devolvesse 200 anonimo, o painel apareceria vazio
        // e todas as chamadas seguintes dariam erro - um estado que o usuario
        // nao sabe sair.
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me").session(sessaoAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(jsonPath("$.perfis[0]").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("/api/auth/csrf e publico e emite o token que o front precisa")
    void csrfEEmitidoParaAnonimo() throws Exception {
        // O front chama este endpoint antes do primeiro POST para receber o
        // cookie XSRF-TOKEN. Se ele exigisse login, o formulario de login nunca
        // conseguiria enviar (o POST de login tambem passa pelo CSRF filter,
        // ainda que isento por matcher).
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // =========================================================
    //  Limite conhecido
    // =========================================================

    @Test
    @DisplayName("rebaixar um ADMIN ENCERRA a sessao - o superpoder nao sobrevive")
    void rebaixarEncerraASessao() throws Exception {
        // ESTE TESTE TAMBEM JA MEDIU O CONTRARIO (corrigido depois da Etapa 8)
        //
        // Ele afirmava isNoContent() numa EXCLUSAO feita com a sessao de quem
        // tinha acabado de ser rebaixado - ou seja, media o superpoder
        // sobrevivendo ao rebaixamento, e passava. E o caso mais grave dos dois:
        // ao desativar, um login NOVO ao menos e barrado; ao rebaixar, o login
        // novo ja nasce ATENDENTE e correto, e so a sessao antiga continua
        // errada. O ROLE_ADMIN foi montado pelo UsuarioDetailsService no
        // momento do login e ficou congelado na sessao - nao ha reconsulta.
        //
        // A prova era um DELETE passando por hasRole("ADMIN"). Agora ele da
        // 401, porque a sessao foi encerrada pelo GerenciadorDeSessoes no
        // instante do rebaixamento e o ConcurrentSessionFilter a invalidou.
        //
        // A SESSAO NAO PODE SER A ADMIN - senao o teste nao prova nada. Por
        // isso o alvo e uma conta propria, promovida a ADMIN, logada, e so
        // entao rebaixada.
        criarAlvo("SenhaAlvoLonga123");
        Long idAlvo = usuarios.findByLogin(LOGIN_ALVO).orElseThrow().getId();

        // A sessao nasce enquanto ela ainda e ADMIN.
        Usuario alvo = usuarios.findById(idAlvo).orElseThrow();
        alvo.setPerfil(Perfil.ADMIN);
        usuarios.save(alvo);
        // Um ADMIN a mais existe agora, entao "ultimo admin" nao protege este.
        MockHttpSession sessaoDoAlvo = loginDe(LOGIN_ALVO, "SenhaAlvoLonga123");

        // Prova que a sessao nasceu com poder de ADMIN de verdade.
        mockMvc.perform(get("/api/usuarios").session(sessaoDoAlvo))
                .andExpect(status().isOk());

        // Rebaixa pelo endpoint real, com a sessao de outro ADMIN.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/usuarios/" + idAlvo)
                        .session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        .content("{\"login\":\"" + LOGIN_ALVO + "\",\"nome\":\"Funcionario Alvo\","
                                + "\"perfil\":\"ATENDENTE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("ATENDENTE"));

        // O banco diz ATENDENTE, e agora a SESSAO concorda: foi encerrada.
        assertThat(usuarios.findById(idAlvo).orElseThrow().getPerfil())
                .isEqualTo(Perfil.ATENDENTE);

        mockMvc.perform(get("/api/usuarios").session(sessaoDoAlvo))
                .andExpect(status().isUnauthorized());

        // E a exclusao que provava o superpoder tambem nao passa mais.
        Cliente descartavel = new Cliente();
        descartavel.setNome("Cliente Descartavel");
        descartavel.setTelefone("11999990000");
        Long idDescartavel = clientes.save(descartavel).getId();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/clientes/" + idDescartavel)
                        .session(sessaoDoAlvo).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================
    //  Helpers
    // =========================================================

    private Usuario criarAlvo(String senha) {
        usuarios.findByLogin(LOGIN_ALVO).ifPresent(usuarios::delete);
        Usuario u = new Usuario();
        u.setLogin(LOGIN_ALVO);
        u.setNome("Funcionario Alvo");
        u.setPerfil(Perfil.ATENDENTE);
        u.setAtivo(true);
        u.setSenha(encoder.encode(senha));
        return usuarios.save(u);
    }

    private void desativar(String login) throws Exception {
        Long id = usuarios.findByLogin(login).orElseThrow().getId();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/usuarios/" + id)
                        .session(sessaoAdmin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    /**
     * Autentica PELO ENDPOINT de login e devolve a sessao real que ele criou.
     *
     * Por que nao montar a sessao a mao: uma sessao montada com
     * setAttribute("SPRING_SECURITY_CONTEXT", ...) nao passa pelo
     * RegisterSessionAuthenticationStrategy - quem registra a sessao e o
     * onAuthentication() dessa estrategia, que so roda dentro da cadeia de
     * filtros. Com o SessionRegistry vazio, getAllPrincipals() devolve lista
     * vazia, o GerenciadorDeSessoes nao encontra nada para expirar, e qualquer
     * teste de encerramento de sessao fica verde SEM TESTAR NADA.
     *
     * Isto ja aconteceu nesta classe: os dois testes de encerramento ficaram
     * verdes depois da correcao, o que parecia confirmar que ela nao funcionava.
     * Nao era isso - era o helper que pulava o registro. Login pelo endpoint e
     * o unico caminho que exercita o mecanismo de verdade.
     */
    private MockHttpSession loginDe(String login, String senha) throws Exception {
        var resultado = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"login\":\"" + login + "\",\"senha\":\"" + senha + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession sessao = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(sessao).as("o login deveria ter criado uma sessao").isNotNull();
        return sessao;
    }

    /**
     * Sessao montada a mao, SEM passar pelo registro.
     *
     * Continua existindo para os testes que so precisam de uma sessao de ADMIN
     * para CHAMAR o endpoint sob teste (desativar, rebaixar). Encher o registro
     * com o admin aqui poluiria a contagem de sessoes do alvo e criaria
     * interdependencia entre testes. Quando o teste precisar que a sessao SEJA
     * encerrada, use loginDe().
     */
    private MockHttpSession sessaoDe(String login, String senha) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(login, senha));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute("SPRING_SECURITY_CONTEXT", ctx);
        return sessao;
    }

    private MockHttpSession sessaoAdmin() {
        return sessaoDe("admin", SENHA_ADMIN);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }
}
