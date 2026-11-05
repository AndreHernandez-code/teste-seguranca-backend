package br.com.lavajato;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 8 - os testes nao podem depender de estado que eles nao criaram.
 *
 * O QUE ESTA CLASSE PEGA
 *
 * Achado ao escrever os testes da Etapa 8, dentro do proprio ValidacaoDtoTest:
 * varios testes criam um servico e AGENDAM apontando para "servico": {"id": 1}
 * - um id FIXO. Se o registro de id 1 nao existir, a requisicao falha com
 * "Servico nao encontrado" (422) ANTES de exercitar o que o teste queria
 * exercitar, e o teste fica vermelho por um motivo que nao tem nenhuma relacao
 * com o nome dele. Foi medido: trocando "id": 1 por "id": 888888 naquele
 * arquivo, dois testes passaram a falhar com 422.
 *
 * Hoje eles passam porque um teste ALHEIO, que roda antes, cria o servico de id
 * 1 pela ordem de execucao. Isso e uma dependencia de ORDEM: o resultado de um
 * teste passa a depender de outro ter rodado. O sintoma classico e o pior
 * possivel - roda verde na maquina de quem escreveu e vermelho em outro lugar,
 * ou vermelho so quando alguem roda um teste isolado com -Dtest=.
 *
 * O QUE ESTA CLASSE NAO FAZ
 *
 * Nao reorganiza os testes existentes: mexer em teste que passa, na mesma etapa
 * em que se escreve teste novo, mistura duas mudancas e dificulta saber qual
 * delas quebrou algo. O que ela faz e deixar a pratica VISIVEL - se alguem
 * acrescentar mais um "id" fixo, o build aponta o arquivo e a linha.
 *
 * E uma checagem de padrao, e nao de comportamento: le o codigo-fonte dos
 * testes. E o mesmo tipo de trava do BancoEndurecidoTest, e pelo mesmo motivo -
 * o que se quer impedir e uma pratica que so se manifesta sob uma condicao
 * dificil de reproduzir (ordem de execucao, banco limpo).
 */
@DisplayName("Etapa 8 - isolamento dos testes")
class IsolamentoDeTesteTest {

    private static final Path RAIZ_TESTES = Path.of("src", "test", "java");

    /**
     * Referencia a um recurso por id FIXO dentro de um corpo JSON enviado pela
     * API. O padrao exige a forma escapada que aparece no codigo Java
     * (\{\"servico\":{\"id\":1}}) para nao acusar id de entidade montada em
     * memoria, que e legitimo.
     */
    private static final Pattern ID_FIXO_EM_JSON = Pattern.compile(
            "\\\\\"(servico|veiculo|cliente)\\\\\"\\s*:\\s*\\{\\\\\"id\\\\\"\\s*:\\s*(\\d+)");

    /**
     * Arquivos que a varredura ignora.
     *
     * Esta PROPRIA classe entra na lista: o regex acima, escrito como literal,
     * casa consigo mesmo - a primeira execucao deste teste acusou
     * "IsolamentoDeTesteTest.java:57 -> servico id=1", que e a linha do
     * Pattern.compile. Um teste-guarda que se denuncia e ruido que ensina a
     * ignorar o teste, entao a exclusao e explicita e comentada, e nao um
     * filtro generico que poderia esconder um caso real.
     */
    private static final List<String> IGNORADOS = List.of("IsolamentoDeTesteTest.java");

    @Test
    @DisplayName("nenhum teste novo referencia recurso por id fixo em JSON")
    void nenhumTesteNovoUsaIdFixo() throws IOException {
        List<String> ocorrencias = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(RAIZ_TESTES)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !IGNORADOS.contains(p.getFileName().toString()))
                    .toList()) {
                List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
                for (int i = 0; i < linhas.size(); i++) {
                    Matcher m = ID_FIXO_EM_JSON.matcher(linhas.get(i));
                    while (m.find()) {
                        ocorrencias.add(arquivo.getFileName() + ":" + (i + 1)
                                + "  -> " + m.group(1) + " id=" + m.group(2));
                    }
                }
            }
        }

        // O teto e o numero de ocorrencias que existem HOJE, herdadas das etapas
        // anteriores. Ele nao pode crescer: um teste novo que traga mais um id
        // fixo falha aqui, com o arquivo e a linha na mensagem.
        //
        // Baixar o teto (limpando as antigas) e trabalho para uma etapa de
        // limpeza, quando houver uma - e o teste permite, porque a assercao e
        // "no maximo", e nao "exatamente".
        int tetoAtual = 10;

        assertThat(ocorrencias)
                .as("teste que aponta para um id que ele nao criou depende da ORDEM de "
                        + "execucao de outro teste. Crie o registro na propria classe e use "
                        + "o id devolvido pela API. Ocorrencias encontradas:%n%s",
                        String.join("\n", ocorrencias))
                .hasSizeLessThanOrEqualTo(tetoAtual);
    }

    @Test
    @DisplayName("o teto declarado acompanha o numero real - nem frouxo, nem apertado")
    void oTetoAcompanhaARealidade() throws IOException {
        // A contagem NAO e "quantas sao ruins". Das 14 ocorrencias de hoje
        // (medidas, e nao estimadas), uma boa parte e legitima:
        //
        //   - "id": 999999 (ou 888888, 99999) e um id que se quer INEXISTENTE de
        //     proposito, para medir o 404/422. Nao depende de nada.
        //   - "cliente": {"id": 1} dentro de um teste que acabou de criar o
        //     primeiro cliente da classe funciona, porque o id 1 e o primeiro
        //     insert - mas funciona por SORTE de ordenacao, e nao por desenho.
        //
        // A contagem e um CENSO, e nao um veredito: serve para o numero nao
        // subir sozinho ao longo das proximas etapas, que e o sinal de que a
        // pratica voltou a ser copiada. Quando alguem limpar os casos de ordem,
        // este teste aponta o novo numero e o teto cai junto - um teto folgado
        // demais deixa de proteger, e um teto apertado demais vira ruido.
        int encontradas = contarOcorrencias();

        assertThat(encontradas)
                .as("se as ocorrencias antigas foram limpas, baixe o 'tetoAtual' deste "
                        + "teste para o novo numero (%d) - ver a lista em "
                        + "nenhumTesteNovoUsaIdFixo(). Um teto alto demais deixa de "
                        + "proteger.", encontradas)
                .isEqualTo(10);
    }

    private int contarOcorrencias() throws IOException {
        int total = 0;
        try (Stream<Path> arquivos = Files.walk(RAIZ_TESTES)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !IGNORADOS.contains(p.getFileName().toString()))
                    .toList()) {
                for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                    Matcher m = ID_FIXO_EM_JSON.matcher(linha);
                    while (m.find()) total++;
                }
            }
        }
        return total;
    }

    @Test
    @DisplayName("cada classe de teste que sobe contexto cria seus proprios dados")
    void classesDeTesteCriamSeusDados() throws IOException {
        // Checagem complementar e mais fraca, mas util: uma classe @SpringBootTest
        // que so LE a API sem nunca gravar nada depende, por construcao, de dado
        // deixado por outra. Hoje nenhuma faz isso - todas criam a fixture que
        // precisam (fixture(), criarAlvo(), os POST de setup). Se uma classe
        // nova aparecer nessa forma, ela e um candidato a quebrar sob outra
        // ordem, e o build aponta o nome dela.
        List<String> soLeitura = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(RAIZ_TESTES)) {
            for (Path arquivo : arquivos
                    .filter(p -> p.toString().endsWith("Test.java"))
                    .toList()) {

                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
                boolean sobeContexto = fonte.contains("@SpringBootTest");
                // "grava algo" inclui instanciar um runner/seeder e chama-lo: o
                // SegredosEAmbienteTest cria usuario chamando AdminInicialSeeder.run(),
                // sem passar por .save() nem por POST - e ainda assim cria os
                // proprios dados. A checagem e heuristica, e a heuristica precisa
                // enxergar essa forma, senao acusa um falso positivo que ensina a
                // desligar o teste.
                boolean gravaAlgo = fonte.contains(".save(")
                        || fonte.contains("MockMvcRequestBuilders.post(")
                        || fonte.contains("post(\"/api/")
                        || fonte.contains(".run(null)")
                        || fonte.contains("seeder(");

                if (sobeContexto && !gravaAlgo) {
                    soLeitura.add(arquivo.getFileName().toString());
                }
            }
        }

        assertThat(soLeitura)
                .as("estas classes sobem o contexto mas nunca gravam nada: elas dependem "
                        + "de dado deixado por outro teste. Crie a fixture na propria classe.%n"
                        + "Classes: %s", String.join(", ", soLeitura))
                .isEmpty();
    }
}
