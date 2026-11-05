package br.com.lavajato.exception;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 5 - erros e logs.
 *
 * O que estes testes provam, e por que cada um existe:
 *
 *  1. O formato do erro e o MESMO em toda a API - 400 de validacao, 404 de
 *     rota e 401 de autenticacao saem todos com {status, mensagem}. O front tem
 *     um so caminho de tratamento; se um deles mudasse de forma, a tela de erro
 *     mostraria "undefined" em vez da mensagem.
 *
 *  2. Erro inesperado NAO vaza a causa. Este e o teste que importa de verdade:
 *     a mensagem de uma excecao real pode trazer nome de tabela, trecho de
 *     consulta ou dado de outro usuario. Ele confere que nem o texto da
 *     excecao, nem a classe, nem o stack trace aparecem na resposta.
 *
 *  3. O erro inesperado traz o codigo de correlacao que aparece no log. Sem
 *     isso, "deu erro" nao e investigavel - o codigo liga o relato do usuario
 *     a linha do log.
 *
 * O QUE ESTES TESTES NAO PROVAM
 *
 * Que o LOG recebeu o stack trace. Provar isso exigiria capturar o appender do
 * Logback dentro do teste; o que da para afirmar com confianca e o que SAI na
 * resposta, que e o que o usuario ve. A conferencia do log foi feita a mao, no
 * servidor no ar (ver o comentario no fim da classe).
 */
@SpringBootTest
@DisplayName("Etapa 5 - erros e logs")
class ErrosELogsTest {

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

        if (!usuarios.existsByLogin("erros.teste")) {
            Usuario u = new Usuario();
            u.setLogin("erros.teste");
            u.setNome("Usuario de Teste de Erros");
            u.setPerfil(Perfil.ADMIN);
            u.setAtivo(true);
            u.setSenha(encoder.encode("SenhaDeTesteMuitoLonga123"));
            usuarios.save(u);
        }
    }

    @Test
    @DisplayName("rota inexistente responde 404 no formato da API")
    void rotaInexistenteResponde404() throws Exception {
        // Antes da Etapa 5 isto caia no /error do Boot (ou virava 401), com
        // formato diferente. O front nao distinguia "nao existe" de "sem
        // permissao" - e as duas telas sao diferentes.
        mockMvc.perform(get("/api/rota-que-nao-existe")
                        .with(autenticado("erros.teste", "SenhaDeTesteMuitoLonga123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.mensagem").exists());
    }

    @Test
    @DisplayName("todo erro da API sai no formato {status, mensagem}")
    void formatoDeErroEhConsistente() throws Exception {
        // 401 do Security.
        MvcResult semLogin = mockMvc.perform(get("/api/clientes"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(semLogin.getResponse().getContentAsString()).startsWith("{\"status\":401");

        // 400 de validacao, do GlobalExceptionHandler.
        MvcResult validacao = mockMvc.perform(post("/api/clientes")
                        .with(autenticado("erros.teste", "SenhaDeTesteMuitoLonga123"))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"\",\"telefone\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(validacao.getResponse().getContentAsString()).startsWith("{\"status\":400");
    }

    @Test
    @DisplayName("erro inesperado nao revela classe, mensagem nem stack trace")
    void erroInesperadoNaoVazaDetalhe() throws Exception {
        // Um agendamento com veiculo inexistente faz o service estourar. A causa
        // real da excecao NUNCA pode chegar ao cliente.
        MvcResult resultado = mockMvc.perform(post("/api/agendamentos")
                        .with(autenticado("erros.teste", "SenhaDeTesteMuitoLonga123"))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-01T10:00:00\","
                                + "\"veiculo\":{\"id\":999999},\"servico\":{\"id\":1}}"))
                .andReturn();

        String corpo = resultado.getResponse().getContentAsString();

        assertThat(corpo)
                .as("a resposta de erro nao pode conter detalhe interno")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("at br.com.lavajato")
                .doesNotContain("hibernate")
                .doesNotContain("SQL")
                .doesNotContain("select")
                .doesNotContain("lavajato_");
    }

    @Test
    @DisplayName("erro 500 traz codigo de correlacao de 8 caracteres")
    void erroInternoTrazCodigoDeCorrelacao() {
        // O codigo e o que liga o relato do usuario a linha do log. Sem ele,
        // investigar um "deu erro interno" exige varrer o log por horario.
        //
        // Este teste chama o handler DIRETO, e nao por HTTP: forcar um 500 real
        // pela API exigiria um endpoint que explode que nao existe em producao -
        // e criar um so para o teste seria pior que o teste. O que se mede aqui
        // e o contrato do handler, que e onde a mensagem e montada.
        var handler = new GlobalExceptionHandler();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/x");

        var resposta = handler.inesperado(new RuntimeException("detalhe-interno-que-nao-pode-vazar"), request);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String mensagem = (String) resposta.getBody().get("mensagem");
        assertThat(mensagem)
                .as("a mensagem do 500 nao pode conter o texto da excecao")
                .doesNotContain("detalhe-interno-que-nao-pode-vazar");
        assertThat(mensagem)
                .as("a mensagem do 500 precisa trazer o codigo de correlacao")
                .containsPattern("Código: [0-9a-f]{8}");
    }

    @Test
    @DisplayName("erro 4xx inesperado preserva o status, nao vira 500")
    void erro4xxNaoViraErroDeServidor() {
        // Um 405 (metodo nao permitido) ou 415 (tipo nao suportado) e erro do
        // CLIENTE. Se o handler generico transformasse tudo em 500, o front
        // mostraria "falha do servidor" para um engano de quem chamou - e o
        // monitoramento acusaria falha onde nao ha.
        var handler = new GlobalExceptionHandler();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/x");

        var resposta = handler.inesperado(
                new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"), request);

        assertThat(resposta.getStatusCode().value()).isEqualTo(405);
    }

    /** Autentica como o usuario informado, sem passar pelo endpoint de login. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            autenticado(String login, String senha) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(login).password(senha);
    }

    // ---- o que foi conferido a mao, no servidor no ar ----
    //
    // 1. Log de acesso negado (403) sai em WARN com o login de quem tentou:
    //    "Acesso negado para 'admin' em DELETE /api/servicos/1."
    // 2. Log de acesso anonimo (401) sai em INFO com metodo e URI.
    // 3. O arquivo logs/lavajato.log e criado e recebe as linhas.
    // 4. Nenhuma das duas respostas acima traz stack trace.
}
