package br.com.lavajato.api;

import br.com.lavajato.model.Cliente;
import br.com.lavajato.model.Servico;
import br.com.lavajato.model.Veiculo;
import br.com.lavajato.repository.ClienteRepository;
import br.com.lavajato.repository.ServicoRepository;
import br.com.lavajato.repository.VeiculoRepository;
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

import java.math.BigDecimal;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 8 - o contrato do JSON que o front consome.
 *
 * POR QUE ESTA CLASSE EXISTE
 *
 * A regra do projeto e que o formato da API nao muda, porque o front depende
 * dele e nao ha contrato escrito em outro lugar. As classes das outras etapas
 * ja travam PEDACOS desse contrato (ValidacaoDtoTest confere clientes, veiculos
 * e precoCobrado). Esta classe fecha o resto, e o faz pelo motivo que apareceu
 * ao revisar a Etapa 8:
 *
 * Um campo que suma do JSON nao quebra o build, nao quebra o teste de
 * seguranca, e nao aparece em log. A tela simplesmente mostra "undefined" numa
 * coluna - e ninguem descobre ate alguem olhar a tabela e achar estranho.
 *
 * As asserções sao sobre a PRESENCA dos campos que o front le, lidos do app.js.
 * Campos a mais sao permitidos de proposito: acrescentar e uma mudanca
 * compativel, remover nao e.
 */
@SpringBootTest
@DisplayName("Etapa 8 - contrato do JSON consumido pelo front")
class ContratoJsonTest {

    private static final String SENHA_ADMIN = "SenhaDeTesteMuitoLonga123";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private ClienteRepository clientes;
    @Autowired
    private VeiculoRepository veiculos;
    @Autowired
    private ServicoRepository servicos;
    @Autowired
    private AuthenticationManager authManager;

    private MockMvc mockMvc;
    private MockHttpSession sessaoAdmin;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();
        sessaoAdmin = sessaoAdmin();
    }

    @Test
    @DisplayName("cliente sai exatamente com id, nome e telefone")
    void clienteTemOsTresCampos() throws Exception {
        // Exatamente estes tres, e nenhum a mais. "Nenhum a mais" e uma decisao
        // de seguranca: a entidade sai inteira se alguem trocar o DTO de saida
        // pela entidade, e um campo interno novo vira campo publico sem
        // ninguem decidir isso.
        Cliente c = new Cliente();
        // Nome com sufixo unico: o H2 e compartilhado entre as classes de
        // teste, entao "$[0]" e um registro de outro teste. Filtrar pelo nome
        // e o que torna este assert sobre o MEU registro.
        c.setNome("Cliente Contrato Unico");
        c.setTelefone("11988887777");
        clientes.save(c);

        mockMvc.perform(get("/api/clientes").session(sessaoAdmin))
                .andExpect(status().isOk())
                // O filtro devolve a lista inteira; a assercao e sobre o item.
                .andExpect(jsonPath("$[?(@.nome=='Cliente Contrato Unico')].telefone")
                        .value("11988887777"))
                .andExpect(jsonPath("$[?(@.nome=='Cliente Contrato Unico')].id").exists());

        // E a trava de "nenhum campo a mais" vale para TODO item da lista.
        mockMvc.perform(get("/api/clientes").session(sessaoAdmin))
                .andExpect(jsonPath("$[*].agendamentos").doesNotExist())
                .andExpect(jsonPath("$[*].veiculos").doesNotExist());
    }

    @Test
    @DisplayName("servico sai com id, nome, preco e tempoEstimado")
    void servicoTemSeusQuatroCampos() throws Exception {
        // O front usa preco para o select e tempoEstimado para calcular o fim
        // previsto na agenda.
        Servico s = new Servico();
        s.setNome("Servico Contrato");
        s.setPreco(new BigDecimal("88.00"));
        s.setTempoEstimado(45);
        servicos.save(s);

        mockMvc.perform(get("/api/servicos").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].nome").value("Servico Contrato"))
                .andExpect(jsonPath("$[0].preco").value(88.00))
                .andExpect(jsonPath("$[0].tempoEstimado").value(45));
    }

    @Test
    @DisplayName("veiculo sai com id, placa, modelo e cliente COMPLETO aninhado")
    void veiculoTemClienteCompleto() throws Exception {
        // Distincao que ja custou um bug nesta base: o front monta a coluna com
        // v.cliente?.nome. Se a saida trouxesse "clienteId" em vez do objeto, a
        // coluna ficaria vazia - o JSON continuaria valido, e nada acusaria.
        Cliente c = new Cliente();
        c.setNome("Dono do Carro");
        c.setTelefone("11977776666");
        c = clientes.save(c);

        Veiculo v = new Veiculo();
        v.setPlaca("CTR1A23");
        v.setModelo("Modelo Contrato");
        v.setCliente(c);
        veiculos.save(v);

        mockMvc.perform(get("/api/veiculos").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].placa").value("CTR1A23"))
                .andExpect(jsonPath("$[0].modelo").value("Modelo Contrato"))
                .andExpect(jsonPath("$[0].cliente.nome").value("Dono do Carro"))
                .andExpect(jsonPath("$[0].cliente.telefone").value("11977776666"))
                .andExpect(jsonPath("$[0].cliente.id").exists())
                // A forma antiga NAO pode voltar: o front nao a le.
                .andExpect(jsonPath("$[0].clienteId").doesNotExist());
    }

    @Test
    @DisplayName("a resposta de login traz login e perfis no formato ROLE_")
    void loginTrazPerfisComPrefixoRole() throws Exception {
        // O front compara com "ROLE_ADMIN" para decidir se mostra os botoes de
        // exclusao e a tela de funcionarios. Sem o prefixo, o ADMIN veria a
        // interface de ATENDENTE - e concluiria que perdeu a permissao.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"login\":\"admin\",\"senha\":\"" + SENHA_ADMIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(jsonPath("$.perfis").isArray())
                .andExpect(jsonPath("$.perfis[0]").value("ROLE_ADMIN"))
                // A senha NUNCA pode aparecer na resposta, em nenhuma forma.
                .andExpect(jsonPath("$.senha").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("a lista de usuarios nunca inclui o campo senha")
    void usuariosNaoExpoemSenha() throws Exception {
        // O hash BCrypt e credencial de facto: quem o obtem pode atacar a senha
        // offline, sem passar pelo limite de tentativas do login. Este teste e
        // uma trava contra alguem "resolver" a tela de funcionarios devolvendo
        // a entidade em vez do DTO.
        mockMvc.perform(get("/api/usuarios").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].login").exists())
                .andExpect(jsonPath("$[0].nome").exists())
                .andExpect(jsonPath("$[0].perfil").exists())
                .andExpect(jsonPath("$[0].ativo").exists())
                .andExpect(jsonPath("$[0].senha").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @DisplayName("erro de validacao sai com status, mensagem e campos, nessa forma")
    void erroDeValidacaoTemFormaConhecida() throws Exception {
        // O front le erro.campos para pintar o campo errado de vermelho e
        // erro.mensagem para o aviso geral. Se a forma mudasse, o front cairia
        // no fallback "erro desconhecido" - perdendo a mensagem util.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/clientes").session(sessaoAdmin()).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"\",\"telefone\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensagem").exists())
                .andExpect(jsonPath("$.campos.nome").exists())
                .andExpect(jsonPath("$.campos.telefone").exists());
    }

    @Test
    @DisplayName("GET de um id inexistente responde 404 sem corpo de erro da API")
    void idInexistenteResponde404Simples() throws Exception {
        // O controller usa ResponseEntity.notFound(), que devolve corpo vazio -
        // e nao o formato {status, mensagem}. O front trata pelo codigo HTTP
        // ("registro nao encontrado"), entao a diferenca e aceitavel hoje; o
        // teste registra qual e o comportamento para a distincao ser uma
        // escolha visivel, e nao uma surpresa.
        mockMvc.perform(get("/api/clientes/999999").session(sessaoAdmin))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private MockHttpSession sessaoAdmin() {
        var auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken("admin", SENHA_ADMIN));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute("SPRING_SECURITY_CONTEXT", ctx);
        return sessao;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }
}
