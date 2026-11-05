package br.com.lavajato;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 6 - endurecer o banco.
 *
 * Este teste e leitura de arquivo puro (nao sobe contexto, roda em
 * milissegundos) e tranca as decisoes da etapa que o build consegue conferir
 * sozinho.
 *
 * O QUE ELE NAO PROVA, e por que esta registrado aqui
 *
 * Que as migracoes criam um schema compativel com as entidades, e que o
 * ddl-auto=validate barra um schema divergente. As duas coisas exigem um
 * Postgres e foram verificadas A MAO, contra o banco real:
 *
 *  1. Base NOVA (vazia): o Flyway aplicou o V1, criou as 5 tabelas e o
 *     flyway_schema_history registrou "V1 | schema inicial | success=true";
 *     a aplicacao subiu com validate sem reclamar.
 *  2. Base EXISTENTE (criada antes pelo Hibernate, sem historico): o
 *     baseline-on-migrate marcou o V1 como baseline e a aplicacao subiu sem
 *     tentar recriar as tabelas.
 *  3. Schema DIVERGENTE (uma coluna removida a mao): a subida FALHOU com
 *     "Schema validation: missing column [telefone] in table [cliente]" - que e
 *     exatamente o comportamento desejado: falhar no deploy, nao em producao.
 *
 * Os testes daqui cobrem a parte que nao depende de banco. O resto e o
 * procedimento de instalacao, documentado no proprio arquivo SQL.
 */
@DisplayName("Etapa 6 - banco endurecido")
class BancoEndurecidoTest {

    private static final Path MIGRACOES = Path.of("src", "main", "resources", "db", "migration");
    private static final Path PROPRIEDADES =
            Path.of("src", "main", "resources", "application.properties");
    private static final Path PROPRIEDADES_PROD =
            Path.of("src", "main", "resources", "application-prod.properties");
    private static final Path SCRIPT_USUARIO =
            Path.of("src", "main", "resources", "db", "usuario-minimo-privilegio.sql");

    @Test
    @DisplayName("nenhuma configuracao deixa o Hibernate alterar o schema sozinho")
    void nenhumPerfilUsaDdlAutoQueAlteraSchema() throws IOException {
        // "update" e "create"/"create-drop" deixam o Hibernate mexer no schema.
        // Em dev isso ja e ruim (o banco real do dev divirge em silencio do que
        // as entidades dizem); em producao pode perder dado. O unico valor
        // aceitavel nos arquivos de PRODUCAO e validate ou none.
        for (Path arquivo : List.of(PROPRIEDADES, PROPRIEDADES_PROD)) {
            String conteudo = ler(arquivo);

            assertThat(conteudo)
                    .as("%s nao pode usar ddl-auto=update/create: quem cria o "
                            + "schema e o Flyway, o Hibernate so confere", arquivo)
                    .doesNotContain("ddl-auto=update")
                    .doesNotContain("ddl-auto=create")
                    .doesNotContain("ddl-auto=create-drop");

            assertThat(conteudo)
                    .as("%s precisa declarar ddl-auto=validate explicitamente - "
                            + "um padrao de versao futura nao e uma decisao", arquivo)
                    .contains("ddl-auto=validate");
        }
    }

    @Test
    @DisplayName("o Flyway esta ligado e o clean esta bloqueado")
    void flywayLigadoECleanBloqueado() throws IOException {
        String conteudo = ler(PROPRIEDADES);

        assertThat(conteudo)
                .as("o Flyway e quem cria o schema agora")
                .contains("spring.flyway.enabled=true");

        // clean-disabled=true e o que impede um "flyway:clean" (que APAGA o
        // banco) de estar a um comando de distancia de uma base com dado real.
        assertThat(conteudo)
                .as("flyway.clean e destrutivo e precisa estar desabilitado")
                .contains("spring.flyway.clean-disabled=true");

        // baseline-on-migrate e o que permite adotar uma base que ja existia
        // (criada pelo Hibernate antes da etapa) sem tentar recriar as tabelas.
        assertThat(conteudo)
                .as("sem baseline, adotar uma base existente quebraria")
                .contains("spring.flyway.baseline-on-migrate=true");
    }

    @Test
    @DisplayName("as migracoes tem nome versionado e ordem definida")
    void migracoesTemNomeVersionado() throws IOException {
        assertThat(MIGRACOES).as("a pasta de migracoes precisa existir").exists();

        List<Path> arquivos;
        try (Stream<Path> lista = Files.list(MIGRACOES)) {
            arquivos = lista.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }

        assertThat(arquivos)
                .as("precisa haver ao menos a migracao inicial")
                .isNotEmpty();

        for (Path arquivo : arquivos) {
            // O Flyway recusa o arquivo se o nome nao seguir o padrao - e a
            // recusa acontece na subida, em producao, se ninguem conferir antes.
            assertThat(arquivo.getFileName().toString())
                    .as("nome de migracao fora do padrao V<numero>__<descricao>.sql")
                    .matches("V\\d+__[a-zA-Z0-9_]+\\.sql");
        }
    }

    @Test
    @DisplayName("as migracoes nao contem DROP nem TRUNCATE")
    void migracoesNaoApagamDados() throws IOException {
        // Uma migracao roda uma vez e nao tem desfazer. Um DROP TABLE ou um
        // TRUNCATE escrito por engano apaga dado de producao sem chance de
        // volta - e o tipo de linha que passa na revisao porque "e so uma
        // migracao". Este teste forca a conversa antes do commit.
        //
        // Se um dia for mesmo necessario apagar algo, a decisao tem que ser
        // explicita - e vai exigir mudar este teste, o que e o ponto.
        try (Stream<Path> lista = Files.list(MIGRACOES)) {
            for (Path arquivo : lista.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = ler(arquivo).toUpperCase();
                assertThat(sql)
                        .as("%s contem DROP ou TRUNCATE: migracao nao tem desfazer, "
                                + "confirme que e intencional e ajuste este teste", arquivo)
                        .doesNotContain("DROP TABLE")
                        .doesNotContain("DROP SCHEMA")
                        .doesNotContain("TRUNCATE");
            }
        }
    }

    @Test
    @DisplayName("o script de usuario de banco nao tem senha fixa e nega superusuario")
    void scriptDeUsuarioNaoTemSenhaFixa() throws IOException {
        String script = ler(SCRIPT_USUARIO);

        // A senha nunca entra num arquivo versionado. O marcador ':SENHA_AQUI'
        // e substituido na instalacao.
        assertThat(script)
                .as("senha nao pode estar escrita no repositorio")
                .contains(":SENHA_AQUI");

        // As negativas que fazem o usuario ser de privilegio minimo.
        assertThat(script)
                .as("o usuario da aplicacao nao pode ser superusuario nem criar banco/papel")
                .contains("NOSUPERUSER")
                .contains("NOCREATEDB")
                .contains("NOCREATEROLE")
                .contains("NOREPLICATION")
                .contains("NOBYPASSRLS");
    }

    private static String ler(Path caminho) throws IOException {
        assertThat(caminho).as("arquivo ausente: %s", caminho).exists();
        return Files.readString(caminho, StandardCharsets.UTF_8);
    }
}
