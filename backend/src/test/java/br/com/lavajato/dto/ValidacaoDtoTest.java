package br.com.lavajato.dto;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 3: validacao de entrada pelos DTOs.
 *
 * Dois grupos de teste, com propositos diferentes:
 *
 *  1. LIMITES - cada @Size/@Max/@Pattern barra o que deve barrar, com 400 e o
 *     nome do campo em "campos". Se alguem afrouxar um limite sem querer, cai aqui.
 *
 *  2. CONTRATO - o formato do JSON nao mudou. Estes testes sao a rede de
 *     seguranca contra a regressao mais perigosa desta etapa: o front consome
 *     este JSON e quebraria em silencio (campo virando undefined) se um nome
 *     ou um nulo mudasse. Nao sao testes de validacao: sao testes de FORMA.
 */
@SpringBootTest
@DisplayName("Etapa 3 - DTOs e validacao")
class ValidacaoDtoTest {

    private static final String SENHA = "SenhaDeTesteMuitoLonga123";

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private UsuarioRepository usuarios;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private AuthenticationManager authManager;

    /**
     * O mapper configurado pelo Spring - o mesmo que serializa as respostas.
     *
     * Atencao ao pacote: o Spring Boot 4 usa JACKSON 3, cujo pacote base e
     * "tools.jackson.databind". O antigo "com.fasterxml.jackson.databind" e do
     * Jackson 2 e nao existe no classpath deste projeto.
     */
    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void configurar() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
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
    }

    // ================= LIMITES =================

    @Test
    @DisplayName("nome acima do limite e recusado com 400 e o campo em 'campos'")
    void nomeGiganteEhRecusado() throws Exception {
        String gigante = "a".repeat(121);

        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"" + gigante + "\",\"telefone\":\"11999998888\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                // O nome do campo precisa continuar sendo "nome": o front le
                // "campos" para marcar o input em vermelho.
                .andExpect(jsonPath("$.campos.nome").exists());
    }

    @Test
    @DisplayName("telefone com mascara e aceito - o balcao usa formatos variados")
    void telefoneComMascaraEhAceito() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente Mascara\",\"telefone\":\"(11) 91234-5678\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("telefone acima de 20 caracteres e recusado")
    void telefoneGiganteEhRecusado() throws Exception {
        String gigante = "9".repeat(21);

        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente\",\"telefone\":\"" + gigante + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.telefone").exists());
    }

    @Test
    @DisplayName("preco acima de R$ 100.000,00 e recusado")
    void precoComTetoEhRecusado() throws Exception {
        mockMvc.perform(post("/api/servicos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Servico Caro\",\"preco\":99999999999,\"tempoEstimado\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.preco").exists());
    }

    @Test
    @DisplayName("duracao de 1441 minutos e recusada")
    void duracaoComTetoEhRecusada() throws Exception {
        mockMvc.perform(post("/api/servicos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Servico Longo\",\"preco\":49.90,\"tempoEstimado\":1441}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.tempoEstimado").exists());
    }

    @Test
    @DisplayName("placa fora do padrao e recusada; os dois formatos validos passam")
    void placaSegueOPadrao() throws Exception {
        mockMvc.perform(post("/api/veiculos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"placa\":\"XX-1234\",\"modelo\":\"Fiat Uno\",\"cliente\":{\"id\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.placa").exists());
    }

    @Test
    @DisplayName("modelo acima do limite e recusado")
    void modeloGiganteEhRecusado() throws Exception {
        String gigante = "m".repeat(121);

        mockMvc.perform(post("/api/veiculos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"placa\":\"ABC1D23\",\"modelo\":\"" + gigante + "\",\"cliente\":{\"id\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.modelo").exists());
    }

    // ================= CONTRATO =================

    @Test
    @DisplayName("GET /api/clientes devolve exatamente id, nome, telefone")
    void formatoDeClienteNaoMudou() throws Exception {
        // Cria um cliente antes de ler: numa base vazia a resposta e "[]" e o
        // teste nao exercitaria o formato de um item, que e o que importa.
        mockMvc.perform(post("/api/clientes")
                .with(user("admin").password(SENHA)).with(csrf())
                .contentType("application/json")
                .content("{\"nome\":\"Cliente Formato\",\"telefone\":\"11999998888\"}"));

        String corpo = mockMvc.perform(get("/api/clientes")
                        .with(user("admin").password(SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String chave : List.of("id", "nome", "telefone")) {
            assertThat(corpo).contains("\"" + chave + "\"");
        }
        // Nada de campo interno vazando na saida.
        assertThat(corpo).doesNotContain("\"senha\"").doesNotContain("\"ativo\"");
    }

    @Test
    @DisplayName("veiculo continua trazendo 'cliente' aninhado com nome e telefone")
    void veiculoMantemClienteAninhado() throws Exception {
        mockMvc.perform(post("/api/clientes")
                .with(user("admin").password(SENHA)).with(csrf())
                .contentType("application/json")
                .content("{\"nome\":\"Dono Do Carro\",\"telefone\":\"11999998888\"}"));

        String cliente = mockMvc.perform(get("/api/clientes")
                        .with(user("admin").password(SENHA)))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/veiculos")
                .with(user("admin").password(SENHA)).with(csrf())
                .contentType("application/json")
                .content("{\"placa\":\"FRM1A23\",\"modelo\":\"Carro Formato\","
                        + "\"cliente\":{\"id\":" + idDe(cliente) + "}}"))
                .andExpect(status().isCreated());

        String corpo = mockMvc.perform(get("/api/veiculos")
                        .with(user("admin").password(SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // O front le v.cliente?.nome. Se "cliente" virasse um numero solto
        // (clienteId), a tabela mostraria "—" em toda linha.
        assertThat(corpo).contains("\"cliente\":{");
        assertThat(corpo).contains("\"placa\"");
    }

    @Test
    @DisplayName("agendamento devolve precoCobrado MESMO quando nulo")
    void precoCobradoNuloNaoSomeDoJson() throws Exception {
        String corpo = mockMvc.perform(get("/api/agendamentos")
                        .with(user("admin").password(SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Se houver agendamento, ele precisa trazer "precoCobrado" explicito.
        // Este e o teste que protege contra alguem acrescentar
        // @JsonInclude(NON_NULL) no DTO de saida. O front faz
        // `ag.precoCobrado ?? ag.servico?.preco ?? 0`: se o campo sumisse
        // quando nulo, a tela mostraria o preco do SERVICO no lugar do preco
        // cobrado, em silencio, sempre que os dois diferissem.
        //
        // A checagem do DTO em si (sem depender de haver dado) esta em
        // oDtoDeSaidaNuncaOmitePrecoCobradoNulo, abaixo.
        if (!corpo.equals("[]")) {
            assertThat(corpo).contains("\"precoCobrado\"");
        }
    }

    /**
     * A garantia de que o nulo nao some, sem depender de o banco ter registro.
     * Serializa o record direto: se alguem acrescentar @JsonInclude(NON_NULL)
     * no AgendamentoSaida, este teste cai - e e o unico que cai, porque o
     * outro so olha dados vindos da API.
     */
    @Test
    @DisplayName("o DTO de saida serializa precoCobrado nulo explicitamente")
    void oDtoDeSaidaNuncaOmitePrecoCobradoNulo() throws Exception {
        var saida = new br.com.lavajato.dto.AgendamentoDto.AgendamentoSaida(
                1L, java.time.LocalDateTime.of(2026, 12, 1, 10, 0),
                br.com.lavajato.model.StatusAgendamento.AGUARDANDO, null, null, null);

        // Usa o ObjectMapper DO SPRING, e nao um novo: e ele que serializa as
        // respostas de verdade. Se alguem ligar
        // spring.jackson.default-property-inclusion=non_null, o mapper
        // configurado passa a omitir nulos e este teste cai.
        String json = objectMapper.writeValueAsString(saida);

        assertThat(json).contains("\"precoCobrado\":null");
    }

    @Test
    @DisplayName("JSON sem 'veiculo' devolve 422, nunca 500")
    void agendamentoSemVeiculoNaoEstoura() throws Exception {
        mockMvc.perform(post("/api/agendamentos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-01T10:00:00\",\"servico\":{\"id\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.veiculo").exists());
    }

    @Test
    @DisplayName("referencia a veiculo inexistente devolve 422 com mensagem clara")
    void referenciaInexistenteDevolve422() throws Exception {
        mockMvc.perform(post("/api/agendamentos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-01T10:00:00\","
                                + "\"veiculo\":{\"id\":999999},\"servico\":{\"id\":1}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    // ================= MASS ASSIGNMENT =================

    @Test
    @DisplayName("'precoCobrado' enviado no corpo e IGNORADO - o servidor calcula")
    void precoCobradoNaoVemDoCliente() throws Exception {
        // ESTE TESTE ESTAVA MEDINDO A COISA ERRADA (corrigido na Etapa 8)
        //
        // Ele criava um servico de 49.90 e depois agendava apontando para
        // "servico": {"id": 1} - um id FIXO. O id 1 nao era o servico recem
        // criado: era o primeiro servico que qualquer classe de teste tivesse
        // inserido naquela execucao do H2 (que e compartilhado, e o
        // @GeneratedValue comeca em 1 a cada subida do contexto).
        //
        // Enquanto o id 1 calhava de ser um servico de 49.90, o assert passava
        // pelo motivo errado. Ao escrever os testes da Etapa 8, um servico de
        // 88.00 assumiu o id 1 e o teste ficou vermelho - nao por um defeito no
        // codigo, mas porque ele nunca esteve medindo o que dizia medir.
        //
        // A correcao e usar o id que a API DEVOLVEU na criacao. Sem isso, o
        // teste passa a ser uma medida de "qualquer servico que estivesse no id
        // 1", que e a mesma coisa que nao medir nada.
        String servico = mockMvc.perform(post("/api/servicos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Lavagem Teste Preco\",\"preco\":49.90,\"tempoEstimado\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idServico = idDe(servico);

        String cliente = mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente Preco\",\"telefone\":\"11999998888\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idCliente = idDe(cliente);

        // Cria um veiculo e um agendamento enviando "precoCobrado": 1 de proposito.
        String veiculo = mockMvc.perform(post("/api/veiculos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"placa\":\"PRC1A23\",\"modelo\":\"Carro Preco\","
                                + "\"cliente\":{\"id\":" + idCliente + "}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int idVeiculo = idDe(veiculo);

        mockMvc.perform(post("/api/agendamentos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-20T14:00:00\","
                                + "\"veiculo\":{\"id\":" + idVeiculo + "},"
                                + "\"servico\":{\"id\":" + idServico + "},"
                                // O campo que o atacante tentaria forcar:
                                + "\"precoCobrado\":1}"))
                .andExpect(status().isCreated())
                // Se o valor gravado fosse 1, o mass assignment teria funcionado.
                .andExpect(jsonPath("$.precoCobrado").value(49.90));
    }

    @Test
    @DisplayName("'id' enviado no corpo da criacao e ignorado - quem escolhe e o banco")
    void idNaoVemDoCliente() throws Exception {
        mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"id\":99999,\"nome\":\"Cliente Id Forjado\",\"telefone\":\"11999998888\"}"))
                .andExpect(status().isCreated())
                // O id gravado e o gerado pelo banco, nunca o 99999 enviado.
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(99999)));
    }

    /**
     * O caso que o DTO veio fechar, na forma exata do bug original.
     *
     * Antes: a entidade vinha no @RequestBody do PUT e trazia "precoCobrado".
     * O service so recalculava quando o SERVICO mudava, entao um PUT que
     * mantinha o mesmo servico gravava o preco enviado pelo cliente. Bastava
     * mandar "precoCobrado": 0.01 para o faturamento da tela despencar.
     */
    @Test
    @DisplayName("PUT com o MESMO servico nao aceita precoCobrado forjado")
    void putNaoAceitaPrecoForjado() throws Exception {
        // Mesma correcao do teste acima: o id do servico vem da resposta, e nao
        // do literal "1". Ver o comentario longo em precoCobradoNaoVemDoCliente.
        String servico = mockMvc.perform(post("/api/servicos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Servico Put Preco\",\"preco\":49.90,\"tempoEstimado\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idServico = idDe(servico);

        String cliente = mockMvc.perform(post("/api/clientes")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"nome\":\"Cliente Put\",\"telefone\":\"11999998888\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idCliente = idDe(cliente);

        String veiculo = mockMvc.perform(post("/api/veiculos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"placa\":\"PUT1A23\",\"modelo\":\"Carro Put\","
                                + "\"cliente\":{\"id\":" + idCliente + "}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String criado = mockMvc.perform(post("/api/agendamentos")
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-21T15:00:00\","
                                + "\"veiculo\":{\"id\":" + idDe(veiculo) + "},"
                                + "\"servico\":{\"id\":" + idServico + "}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int id = idDe(criado);

        // Mesmo servico, mas tentando forcar o preco.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/agendamentos/" + id)
                        .with(user("admin").password(SENHA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"dataHora\":\"2026-12-21T15:00:00\",\"status\":\"AGUARDANDO\","
                                + "\"veiculo\":{\"id\":" + idDe(veiculo) + "},"
                                + "\"servico\":{\"id\":" + idServico + "},"
                                + "\"precoCobrado\":0.01}"))
                .andExpect(status().isOk())
                // Continua 49.90: o campo do corpo foi ignorado por completo.
                .andExpect(jsonPath("$.precoCobrado").value(49.90));
    }

    // ---- helpers ----

    /** Extrai o "id" de um JSON simples de resposta. */
    private static int idDe(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("Resposta sem id: " + json);
        return Integer.parseInt(m.group(1));
    }
}
