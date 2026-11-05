package br.com.lavajato.api;

import br.com.lavajato.model.Cliente;
import br.com.lavajato.model.Servico;
import br.com.lavajato.model.Veiculo;
import br.com.lavajato.repository.AgendamentoRepository;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 8 - as regras de negocio que a API promete e que nenhum teste cobria.
 *
 * POR QUE ESTA CLASSE EXISTE SEPARADA DAS OUTRAS
 *
 * As classes das etapas 1 a 7 testam SEGURANCA (autorizacao, CSRF, cabecalhos,
 * forca bruta) e CONTRATO (formato do JSON, validacao de campo). O que ficou de
 * fora foi o MEIO: as decisoes de negocio que o sistema toma - recusar dois
 * agendamentos do mesmo veiculo no mesmo horario, nao excluir um cliente que
 * tem veiculo, nao deixar o sistema sem administrador.
 *
 * Sao justamente as regras com mais chance de regressao silenciosa: elas nao
 * aparecem na tela, nao quebram o build, e so se manifestam quando um dado real
 * entra em conflito - em producao, no balcao, com o cliente esperando.
 *
 * Todas nascem de um teste VERMELHO primeiro (a regra nao existia, ou existia
 * sem cobertura), pelo motivo de sempre: um teste escrito depois do codigo
 * tende a descrever o que o codigo faz, e nao o que ele deveria fazer.
 */
@SpringBootTest
@DisplayName("Etapa 8 - regras de negocio da API")
class RegrasDeNegocioTest {

    private static final String SENHA = "SenhaDeTesteMuitoLonga123";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private ClienteRepository clientes;
    @Autowired
    private VeiculoRepository veiculos;
    @Autowired
    private ServicoRepository servicos;
    @Autowired
    private AgendamentoRepository agendamentos;

    @Autowired
    private AuthenticationManager authManager;

    private MockMvc mockMvc;
    private MockHttpSession sessaoAdmin;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .build();

        // Sessao AUTENTICADA DE VERDADE, e nao o .user("admin") do MockMvc.
        // A diferenca foi medida nesta etapa: o .user() cria uma autenticacao
        // sintetica sem as authorities do banco, entao as regras
        // hasRole("ADMIN") - DELETE em /api/** e /api/usuarios/** - respondem
        // 403 para um teste que so queria medir regra de negocio. O sintoma
        // apareceu em seis testes de uma vez, todos com "expected 409 but was
        // 403", e o motivo nao tinha nada a ver com o que eles testavam.
        //
        // Passar pelo AuthenticationManager resolve porque usa o
        // UsuarioDetailsService real, que monta ROLE_ADMIN a partir do perfil.
        sessaoAdmin = sessaoDe("admin", SENHA);
    }

    private MockHttpSession sessaoDe(String login, String senha) {
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(login, senha));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute("SPRING_SECURITY_CONTEXT", ctx);
        return sessao;
    }

    // =========================================================
    //  Conflito de horario
    // =========================================================

    @Test
    @DisplayName("dois agendamentos do MESMO veiculo no MESMO horario: o segundo e recusado")
    void mesmoVeiculoMesmoHorarioEhRecusado() throws Exception {
        Fixture f = fixture("conflito");
        LocalDateTime horario = LocalDateTime.of(2026, 12, 3, 9, 0);

        agendar(f, horario)
                .andExpect(status().isCreated());

        // O segundo e o caso que a regra existe para impedir: sem ela, o
        // veiculo estaria em dois boxes ao mesmo tempo e a agenda do dia
        // ficaria mentindo.
        agendar(f, horario)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.mensagem").value("Este veículo já tem um agendamento nesse horário."));

        assertThat(agendamentos.countByVeiculoId(f.veiculoId()))
                .as("o agendamento recusado nao pode ter sido gravado")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("horarios diferentes, mesmo veiculo: os dois passam")
    void mesmoVeiculoHorariosDiferentesPassa() throws Exception {
        // A contraprova do teste acima. Uma regra que recusa DEMAIS e tao ruim
        // quanto uma que recusa de menos, e o sintoma seria o mesmo: o balcao
        // nao consegue agendar.
        Fixture f = fixture("conflito-hora");

        agendar(f, LocalDateTime.of(2026, 12, 3, 9, 0)).andExpect(status().isCreated());
        agendar(f, LocalDateTime.of(2026, 12, 3, 10, 0)).andExpect(status().isCreated());

        assertThat(agendamentos.countByVeiculoId(f.veiculoId())).isEqualTo(2);
    }

    @Test
    @DisplayName("veiculos diferentes no mesmo horario: os dois passam")
    void veiculosDiferentesMesmoHorarioPassa() throws Exception {
        // Dois carros em paralelo e a operacao normal de um lava-jato com mais
        // de um box. A regra e por VEICULO, nao por horario global.
        Fixture a = fixture("conflito-a");
        Fixture b = fixture("conflito-b");
        LocalDateTime horario = LocalDateTime.of(2026, 12, 3, 11, 0);

        agendar(a, horario).andExpect(status().isCreated());
        agendar(b, horario).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("editar o proprio agendamento sem mudar o horario NAO acusa conflito consigo mesmo")
    void editarProprioAgendamentoNaoConflitaConsigoMesmo() throws Exception {
        // O caso que o "ignorarId" do repositorio resolve. Sem ele, abrir um
        // agendamento e clicar em salvar daria "ja tem agendamento nesse
        // horario" - apontando para o proprio registro.
        Fixture f = fixture("conflito-self");
        LocalDateTime horario = LocalDateTime.of(2026, 12, 3, 13, 0);

        String criado = agendar(f, horario)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = idDe(criado);

        mockMvc.perform(put("/api/agendamentos/" + id)
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content(corpoAgendamento(f, horario, "\"status\":\"EM_ANDAMENTO\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));
    }

    @Test
    @DisplayName("agendamento CANCELADO libera o horario para um novo")
    void canceladoLiberaOHorario() throws Exception {
        // O cancelado nao ocupa o box. Se ele bloqueasse, cancelar viraria uma
        // operacao sem efeito pratico: o horario ficaria preso para sempre.
        Fixture f = fixture("conflito-cancel");
        LocalDateTime horario = LocalDateTime.of(2026, 12, 3, 15, 0);

        String criado = agendar(f, horario)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = idDe(criado);

        mockMvc.perform(put("/api/agendamentos/" + id)
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content(corpoAgendamento(f, horario, "\"status\":\"CANCELADO\",")))
                .andExpect(status().isOk());

        // Pode marcar de novo no mesmo horario.
        agendar(f, horario).andExpect(status().isCreated());
    }

    // =========================================================
    //  Exclusao protegida por vinculo
    // =========================================================

    @Test
    @DisplayName("cliente com veiculo nao pode ser excluido (409, nao 500)")
    void clienteComVeiculoNaoEhExcluido() throws Exception {
        // Sem esta checagem, o banco devolveria violacao de chave estrangeira e
        // a tela mostraria "erro interno" - um erro de servidor para uma
        // situacao que e regra de negocio e tem explicacao clara.
        Fixture f = fixture("exclui-cliente");

        mockMvc.perform(delete("/api/clientes/" + f.clienteId())
                        .session(sessaoAdmin).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value(
                        "Não é possível excluir: este cliente ainda tem veículos cadastrados."));

        assertThat(clientes.existsById(f.clienteId())).isTrue();
    }

    @Test
    @DisplayName("veiculo com agendamento nao pode ser excluido")
    void veiculoComAgendamentoNaoEhExcluido() throws Exception {
        Fixture f = fixture("exclui-veiculo");
        agendar(f, LocalDateTime.of(2026, 12, 4, 9, 0)).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/veiculos/" + f.veiculoId())
                        .session(sessaoAdmin).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value(
                        "Não é possível excluir: este veículo ainda tem agendamentos."));

        assertThat(veiculos.existsById(f.veiculoId())).isTrue();
    }

    @Test
    @DisplayName("servico usado em agendamento nao pode ser excluido")
    void servicoUsadoEmAgendamentoNaoEhExcluido() throws Exception {
        // O preco ja esta gravado no agendamento como historico, mas o SERVICO
        // em si nao pode sumir: a tela de agendamentos le o nome dele.
        Fixture f = fixture("exclui-servico");
        agendar(f, LocalDateTime.of(2026, 12, 4, 10, 0)).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/servicos/" + f.servicoId())
                        .session(sessaoAdmin).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value(
                        "Não é possível excluir: este serviço já foi usado em agendamentos."));

        assertThat(servicos.existsById(f.servicoId())).isTrue();
    }

    @Test
    @DisplayName("servico sem uso pode ser excluido normalmente")
    void servicoSemUsoEhExcluido() throws Exception {
        // A contraprova: uma trava que impede excluir TUDO tambem seria um
        // defeito, e o sintoma so aparece quando alguem tenta limpar um
        // cadastro errado.
        Servico s = servico("Servico Descartavel", "10.00");

        mockMvc.perform(delete("/api/servicos/" + s.getId())
                        .session(sessaoAdmin).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(servicos.existsById(s.getId())).isFalse();
    }

    @Test
    @DisplayName("excluir um id que nao existe responde 404, nao 409")
    void excluirInexistenteResponde404() throws Exception {
        // A ordem importa no controller: ele confere existencia ANTES de
        // conferir vinculo. Invertido, um id inexistente diria "tem agendamentos"
        // - mensagem sem sentido para quem clicou num registro ja apagado.
        mockMvc.perform(delete("/api/servicos/999999")
                        .session(sessaoAdmin).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // =========================================================
    //  Referencias entre recursos
    // =========================================================

    @Test
    @DisplayName("criar veiculo apontando para cliente inexistente responde 422")
    void veiculoComClienteInexistenteResponde422() throws Exception {
        // 422 e nao 404: o veiculo em si e valido, o que nao existe e a
        // referencia dentro dele. A distincao importa para o front saber que o
        // problema esta no select de cliente, e nao na URL chamada.
        mockMvc.perform(post("/api/veiculos")
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content("{\"placa\":\"XYZ1A23\",\"modelo\":\"Carro Orfao\","
                                + "\"cliente\":{\"id\":999999}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.mensagem").value("Cliente não encontrado."));
    }

    @Test
    @DisplayName("agendamento com veiculo E servico validos grava o preco vigente do servico")
    void agendamentoGravaOPrecoDoServico() throws Exception {
        Fixture f = fixture("preco");
        agendar(f, LocalDateTime.of(2026, 12, 5, 9, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.precoCobrado").value(75.50));
    }

    @Test
    @DisplayName("mudar o preco do servico NAO altera o agendamento ja feito")
    void precoAntigoNaoMudaNoHistorico() throws Exception {
        // Este e o motivo de "precoCobrado" existir como coluna, em vez de o
        // sistema sempre ler o preco atual do servico: o valor combinado com o
        // cliente fica congelado. Reajustar a tabela de precos nao pode
        // reescrever o que ja foi cobrado.
        Fixture f = fixture("preco-historico");
        String criado = agendar(f, LocalDateTime.of(2026, 12, 5, 11, 0))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idAgendamento = idDe(criado);

        // Reajuste de 75.50 para 199.00.
        mockMvc.perform(put("/api/servicos/" + f.servicoId())
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Servico Reajustado\",\"preco\":199.00,\"tempoEstimado\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/agendamentos/" + idAgendamento).session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precoCobrado").value(75.50));
    }

    @Test
    @DisplayName("trocar o servico de um agendamento recalcula o preco cobrado")
    void trocarServicoRecalculaOPreco() throws Exception {
        // A outra metade da regra: o preco e congelado, mas nao para sempre.
        // Se o cliente troca a lavagem simples pela completa, o valor cobrado
        // precisa acompanhar - senao a tela mostraria o preco do servico
        // errado, e o caixa fecharia com diferenca.
        Fixture f = fixture("troca-servico");
        Servico caro = servico("Servico Mais Caro", "250.00");

        String criado = agendar(f, LocalDateTime.of(2026, 12, 5, 14, 0))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = idDe(criado);

        mockMvc.perform(put("/api/agendamentos/" + id)
                        .session(sessaoAdmin).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-05T14:00:00\","
                                + "\"veiculo\":{\"id\":" + f.veiculoId() + "},"
                                + "\"servico\":{\"id\":" + caro.getId() + "}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precoCobrado").value(250.00));
    }

    // =========================================================
    //  Contrato do JSON que o front le
    // =========================================================

    @Test
    @DisplayName("a lista de agendamentos sempre traz os campos que o front usa")
    void listaDeAgendamentosTemOsCamposDoFront() throws Exception {
        // O front le ag.id, ag.dataHora, ag.status, ag.precoCobrado,
        // ag.veiculo.placa, ag.veiculo.cliente.nome e ag.servico.nome. Um campo
        // que suma do JSON nao quebra o build nem os testes de seguranca - a
        // tela simplesmente mostra "undefined", e ninguem descobre ate alguem
        // olhar a tabela.
        Fixture f = fixture("contrato");
        agendar(f, LocalDateTime.of(2026, 12, 6, 8, 0)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/agendamentos").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].dataHora").exists())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].precoCobrado").exists())
                .andExpect(jsonPath("$[0].veiculo.placa").exists())
                .andExpect(jsonPath("$[0].veiculo.cliente.nome").exists())
                .andExpect(jsonPath("$[0].servico.nome").exists());
    }

    @Test
    @DisplayName("a lista de veiculos traz o cliente aninhado, nao so o id")
    void listaDeVeiculosTrazClienteAninhado() throws Exception {
        // O front monta a tabela com v.cliente?.nome. Se a saida viesse com
        // clienteId solto, a coluna ficaria vazia em todas as linhas.
        Fixture f = fixture("contrato-veiculo");

        mockMvc.perform(get("/api/veiculos").session(sessaoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cliente.nome").exists())
                .andExpect(jsonPath("$[0].cliente.telefone").exists())
                // E o formato antigo, que o front NAO le, nao pode aparecer:
                // seria uma referencia sem nome para exibir.
                .andExpect(jsonPath("$[0].clienteId").doesNotExist());
    }

    @Test
    @DisplayName("nao existe rota para apagar agendamento em massa nem usuario por login")
    void rotasInexistentesNaoRespondem() throws Exception {
        // Trava contra o futuro: se alguem acrescentar um endpoint novo por
        // conveniencia, este teste aponta. As duas formas abaixo sao as que um
        // cliente tentaria por engano (ou de proposito).
        mockMvc.perform(get("/api/usuarios/login/admin").session(sessaoAdmin))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/agendamentos").session(sessaoAdmin).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    // =========================================================
    //  Helpers
    // =========================================================

    /** Cria cliente + veiculo + servico e devolve os ids para o teste usar. */
    private Fixture fixture(String marca) {
        Cliente cliente = new Cliente();
        cliente.setNome("Cliente " + marca);
        cliente.setTelefone("11999990000");
        cliente = clientes.save(cliente);

        Veiculo veiculo = new Veiculo();
        // Placa unica por fixture: o banco tem unique em placa, e duas fixtures
        // com a mesma placa fariam o teste falhar por motivo alheio ao que ele
        // testa.
        veiculo.setPlaca(placaUnica(marca));
        veiculo.setModelo("Modelo " + marca);
        veiculo.setCliente(cliente);
        veiculo = veiculos.save(veiculo);

        Servico servico = servico("Servico " + marca, "75.50");

        return new Fixture(cliente.getId(), veiculo.getId(), servico.getId());
    }

    private Servico servico(String nome, String preco) {
        Servico s = new Servico();
        s.setNome(nome);
        s.setPreco(new BigDecimal(preco));
        s.setTempoEstimado(30);
        return servicos.save(s);
    }

    /**
     * Placa valida (padrao Mercosul ou antigo) derivada do nome da fixture.
     * Letras do nome vao para as 3 primeiras posicoes; o resto e preenchido
     * para nao colidir.
     */
    private static String placaUnica(String marca) {
        String letras = marca.replaceAll("[^A-Za-z]", "").toUpperCase();
        while (letras.length() < 3) letras = letras + "X";
        letras = letras.substring(0, 3);
        int n = Math.abs(marca.hashCode()) % 10;
        return letras + n + "A" + (n % 10) + (n % 10);
    }

    /** Monta a requisicao de criacao/edicao de agendamento. */
    private org.springframework.test.web.servlet.ResultActions agendar(Fixture f, LocalDateTime horario)
            throws Exception {
        return mockMvc.perform(post("/api/agendamentos")
                .session(sessaoAdmin).with(csrf())
                .contentType("application/json")
                .content(corpoAgendamento(f, horario, "")));
    }

    private static String corpoAgendamento(Fixture f, LocalDateTime horario, String extra) {
        return "{\"dataHora\":\"" + horario + "\"," + extra
                + "\"veiculo\":{\"id\":" + f.veiculoId() + "},"
                + "\"servico\":{\"id\":" + f.servicoId() + "}}";
    }

    private static SecurityMockMvcRequestPostProcessors.CsrfRequestPostProcessor csrf() {
        return SecurityMockMvcRequestPostProcessors.csrf();
    }

    /** Extrai o "id" de um JSON simples de resposta. */
    private static int idDe(String json) {
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("Resposta sem id: " + json);
        return Integer.parseInt(m.group(1));
    }

    private record Fixture(Long clienteId, Long veiculoId, Long servicoId) {
    }
}
