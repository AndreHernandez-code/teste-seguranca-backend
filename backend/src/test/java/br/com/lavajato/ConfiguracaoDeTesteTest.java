package br.com.lavajato;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava a armadilha que quebrou o contexto inteiro na Etapa 4.
 *
 * src/test/resources/application.properties NAO complementa o
 * src/main/resources/application.properties: os dois ficam na raiz do
 * classpath, o de teste ganha, e o da aplicacao nunca e lido. Uma propriedade
 * que exista so no arquivo principal vira placeholder nao resolvido e o
 * contexto nem sobe - sete classes de teste falham de uma vez, e a mensagem
 * ("Could not resolve placeholder") so aparece no relatorio do surefire, nao
 * na saida do Maven.
 *
 * Este teste e leitura de arquivo puro: nao sobe contexto, nao depende do
 * Spring, roda em milissegundos. Ele compara a LISTA de chaves dos dois
 * arquivos e exige que tudo que a aplicacao exige exista tambem no de teste.
 *
 * Nao exige o contrario (chave so no teste e normal: datasource H2, admin de
 * teste, connection-timeout). E nao compara valores: o teste pode querer um
 * valor diferente do de producao - mas precisa dizer isso explicitamente, ai.
 */
@DisplayName("Configuracao de teste cobre a da aplicacao")
class ConfiguracaoDeTesteTest {

    private static final Path PRINCIPAL =
            Path.of("src", "main", "resources", "application.properties");
    private static final Path DE_TESTE =
            Path.of("src", "test", "resources", "application.properties");

    /**
     * Propriedades que a aplicacao LE mas nao precisa ter no arquivo de teste.
     * Cada uma aqui e' uma excecao declarada, com motivo - nao uma lista para
     * engordar sem pensar.
     */
    private static final Set<String> ISENTAS_NO_TESTE = Set.of(
            // O perfil prod define ddl-auto=validate para o banco real. Testar
            // com validate exigiria um schema pre-existente; o teste usa
            // create-drop no H2.
            "spring.jpa.hibernate.ddl-auto",
            // Banco de producao: no teste o H2 substitui url/driver/user/senha.
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            // Cookie Secure quebraria a sessao sobre http:// no MockMvc.
            "server.servlet.session.cookie.secure",
            "app.security.cookie-secure",
            // ---- Isentas anteriores a Etapa 4, encontradas por este teste ----
            //
            // Todas tem @Value com valor padrao embutido no codigo
            // (${propriedade:valor}), entao a aplicacao sobe mesmo sem elas no
            // arquivo de teste - foi por isso que ninguem tropecou nelas antes
            // da Etapa 4. Ficam isentas de proposito: o padrao do codigo e o
            // valor certo para testar, e repetir aqui so criaria duas fontes
            // de verdade para o mesmo numero.
            //
            // O que este teste realmente protege e a propriedade SEM padrao,
            // porque essa derruba o contexto. As de baixo nao tem, e por isso
            // apareceriam nesta lista se alguem tirasse o padrao delas. Ficam
            // listadas para o dia em que isso acontecer - e nesse dia o teste
            // vermelho e a lembranca de que a decisao mudou.
            "app.security.corpo-max-bytes",
            "app.security.login.janela",
            "app.security.login.max-tentativas-ip",
            "app.security.login.max-tentativas-usuario",
            "app.security.login.trust-forwarded-for",
            "app.security.logout-url",
            "spring.application.name",
            "spring.jpa.open-in-view",
            "spring.jpa.show-sql",
            // ---- Isenta na Etapa 5 ----
            // O arquivo de log fica DESLIGADO nos testes de proposito: cada
            // execucao criaria um logs/lavajato.log, e rodar a suite centenas
            // de vezes encheria o diretorio do projeto com lixo. Como nao ha
            // @Value lendo esta propriedade (quem a le e o Logback), a
            // ausencia dela nao derruba o contexto - por isso cabe isentar.
            "logging.file.name"
    );

    /**
     * Propriedades de servlet que NAO precisam existir no arquivo de teste
     * porque nao passam por @Value: quem as le e o proprio Tomcat, antes de o
     * contexto da aplicacao subir, com os padroes do container. Ficam separadas
     * das demais so para deixar claro que a razao da isencao e diferente.
     */
    private static final Set<String> ISENTAS_PORQUE_O_CONTAINER_LE = Set.of(
            "server.servlet.session.cookie.http-only",
            "server.servlet.session.cookie.name",
            "server.servlet.session.cookie.path",
            "server.servlet.session.cookie.same-site",
            "server.servlet.session.timeout"
    );

    @Test
    @DisplayName("toda propriedade da aplicacao existe tambem no arquivo de teste")
    void propriedadesDaAplicacaoExistemNoTeste() throws IOException {
        Set<String> daAplicacao = chaves(PRINCIPAL);
        Set<String> doTeste = chaves(DE_TESTE);

        Set<String> faltando = new TreeSet<>(daAplicacao);
        faltando.removeAll(doTeste);
        faltando.removeAll(ISENTAS_NO_TESTE);
        faltando.removeAll(ISENTAS_PORQUE_O_CONTAINER_LE);

        assertThat(faltando)
                .as("""
                    Propriedade(s) lida(s) pela aplicacao que nao existem em
                    src/test/resources/application.properties. Como aquele arquivo
                    SUBSTITUI o principal nos testes, um @Value apontando para
                    qualquer uma delas derruba o contexto inteiro. Acrescente a
                    chave ao arquivo de teste (mesmo valor ou um valor de teste)
                    ou inclua-a em ISENTAS_NO_TESTE com o motivo.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a excecao declarada nao virou desculpa: as isentas existem mesmo no principal")
    void isentasRealmenteExistemNoPrincipal() throws IOException {
        // Se alguem remover spring.datasource.url da aplicacao, a isencao
        // correspondente vira letra morta e engana quem ler a lista depois.
        Set<String> daAplicacao = chaves(PRINCIPAL);

        Set<String> todasAsIsencoes = new TreeSet<>(ISENTAS_NO_TESTE);
        todasAsIsencoes.addAll(ISENTAS_PORQUE_O_CONTAINER_LE);

        for (String isenta : todasAsIsencoes) {
            assertThat(daAplicacao)
                    .as("A isencao '%s' nao existe mais na aplicacao; remova-a da lista de isentas.", isenta)
                    .contains(isenta);
        }
    }

    private static Set<String> chaves(Path caminho) throws IOException {
        assertThat(caminho).as("arquivo de configuracao ausente: %s", caminho).exists();

        // Properties.load: mesma leitura que o Spring faz. O arquivo e lido
        // como ISO-8859-1 (os apontamentos unicode estilo backslash-u
        // continuam valendo), por isso o charset esta explicito aqui.
        Properties propriedades = new Properties();
        try (InputStream entrada = Files.newInputStream(caminho);
             Reader leitor = new InputStreamReader(entrada, StandardCharsets.ISO_8859_1)) {
            propriedades.load(leitor);
        }
        return new LinkedHashSet<>(propriedades.stringPropertyNames());
    }
}
