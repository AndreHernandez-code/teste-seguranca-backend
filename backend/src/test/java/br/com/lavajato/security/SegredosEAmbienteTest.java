package br.com.lavajato.security;

import br.com.lavajato.BackendApplication;
import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Etapa 7 - segredos e ambiente.
 *
 * Duas familias de teste aqui, de proposito:
 *
 *  1. Leitura de arquivo (@Nested puro): trava o CONTEUDO das configuracoes.
 *     Sao rapidos, nao sobem contexto, e pegam a regressao classica - alguem
 *     "resolver um problema no dev" devolvendo valor padrao ao arquivo de
 *     producao.
 *
 *  2. Comportamento com contexto Spring: instancia o AdminInicialSeeder de
 *     verdade contra um repositorio de verdade, e confere se ele CRIA, RECUSA
 *     ou AVISA. Presenca de configuracao nao prova comportamento; este grupo
 *     prova.
 *
 * NOTA sobre @Nested: o JUnit nao propaga o contexto Spring para uma classe
 * aninhada - os campos @Autowired de um @Nested chegam null. Por isso os testes
 * que precisam de repositorio/encoder moram na classe EXTERNA (que e
 * @SpringBootTest) e os de leitura de arquivo ficam aninhados.
 */
@SpringBootTest
@DisplayName("Etapa 7 - segredos e ambiente")
class SegredosEAmbienteTest {

    private static final Path PROD =
            Path.of("src", "main", "resources", "application-prod.properties");
    private static final Path GITIGNORE_RAIZ = Path.of("..", ".gitignore");

    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private Environment ambiente;

    /** Monta o seeder com os valores que o teste quer, sem depender do ambiente real. */
    private AdminInicialSeeder seeder(String login, String senha, String... perfis) {
        MockEnvironment mock = new MockEnvironment();
        mock.setActiveProfiles(perfis);
        return new AdminInicialSeeder(usuarios, encoder, mock, login, senha);
    }

    private void apagarSeExistir(String login) {
        usuarios.findByLogin(login).ifPresent(usuarios::delete);
    }

    @Nested
    @DisplayName("configuracao de producao")
    class ConfiguracaoDeProducao {

        @Test
        @DisplayName("nenhuma credencial tem valor padrao em producao")
        void producaoNaoTemCredencialPadrao() throws IOException {
            List<String> linhas = Files.readAllLines(PROD, StandardCharsets.UTF_8).stream()
                    // Comentarios explicam a decisao e MENCIONAM as chaves; so
                    // as linhas ativas provam o que a aplicacao le.
                    .filter(l -> !l.trim().startsWith("#"))
                    .toList();

            for (String chave : List.of("spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "app.admin.login", "app.admin.senha",
                    "spring.flyway.user", "spring.flyway.password")) {

                String linha = linhas.stream()
                        .filter(l -> l.startsWith(chave + "="))
                        .findFirst()
                        .orElse(null);

                assertThat(linha)
                        .as("producao precisa declarar %s (sem ela, a chave herda o "
                                + "padrao do application.properties em silencio)", chave)
                        .isNotNull();

                // O que separa "obrigatorio" de "com padrao" nesta sintaxe e o
                // dois-pontos: ${X} falha se X faltar; ${X:valor} usa o valor.
                assertThat(linha)
                        .as("%s tem valor padrao em producao - a aplicacao subiria com "
                                + "credencial conhecida se a variavel faltasse", chave)
                        .doesNotContain(":");
            }
        }

        @Test
        @DisplayName("o perfil de producao mantem cookie seguro e schema validado")
        void producaoMantemAsProtecoes() throws IOException {
            String conteudo = ler(PROD);

            assertThat(conteudo).contains("server.servlet.session.cookie.secure=true");
            assertThat(conteudo).contains("app.security.cookie-secure=true");
            assertThat(conteudo).contains("ddl-auto=validate");
        }

        @Test
        @DisplayName("o gitignore da raiz cobre node_modules, backups e segredos")
        void gitignoreDaRaizCobreOEssencial() throws IOException {
            // O backend/.gitignore nao vale para a raiz. Sem este arquivo, um
            // "git add ." levaria 2463 arquivos de node_modules e o .rar de
            // 54 MB - e tirar depois NAO apaga do historico.
            String conteudo = ler(GITIGNORE_RAIZ);

            assertThat(conteudo)
                    .as("node_modules e derivado do package.json; versionar polui "
                            + "toda revisao e nao ajuda ninguem")
                    .contains("node_modules/");

            assertThat(conteudo)
                    .as("backup compactado pode carregar .env e dump de banco junto")
                    .contains("*.rar")
                    .contains("*.zip");

            assertThat(conteudo)
                    .as("o .env guarda DB_PASSWORD e ADMIN_SENHA")
                    .contains(".env");
        }
    }

    @Nested
    @DisplayName("credenciais obrigatorias de producao")
    class CredenciaisObrigatoriasDeProducao {

        /** As sete variaveis, todas preenchidas - o caso "deploy correto". */
        private MockEnvironment producaoCompleta(String... exceto) {
            List<String> pular = List.of(exceto);
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            for (String v : List.of("DB_URL", "DB_USER", "DB_PASSWORD",
                    "DB_MIGRATION_USER", "DB_MIGRATION_PASSWORD",
                    "ADMIN_LOGIN", "ADMIN_SENHA")) {
                if (!pular.contains(v)) {
                    prod.setProperty(v, "valor-presente");
                }
            }
            return prod;
        }

        @Test
        @DisplayName("producao sem nenhuma variavel diz TODAS as que faltam")
        void producaoSemVariaveisListaTodas() {
            // A mensagem lista as sete de uma vez, e nao a primeira: um deploy
            // corrigido uma variavel por tentativa custa uma subida por erro, e
            // cada subida dessas e um ciclo de minutos no painel do provedor.
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");

            assertThatThrownBy(() -> BackendApplication.conferirCredenciaisDeProducao(prod))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB_URL")
                    .hasMessageContaining("DB_USER")
                    .hasMessageContaining("DB_PASSWORD")
                    .hasMessageContaining("DB_MIGRATION_USER")
                    .hasMessageContaining("DB_MIGRATION_PASSWORD")
                    .hasMessageContaining("ADMIN_LOGIN")
                    .hasMessageContaining("ADMIN_SENHA");
        }

        @Test
        @DisplayName("producao com todas as variaveis sobe sem reclamar")
        void producaoCompletaPassa() {
            // O caso positivo precisa de teste tanto quanto o negativo: uma
            // conferencia que recusa TUDO tambem impede todo deploy, e o sintoma
            // seria identico ao de um servidor mal configurado.
            assertThatCode(() -> BackendApplication.conferirCredenciaisDeProducao(producaoCompleta()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("uma variavel presente e vazia conta como ausente")
        void variavelEmBrancoContaComoAusente() {
            // E o caso real do docker-compose com "DB_PASSWORD=" ou do painel em
            // que a variavel foi criada e o valor nao foi preenchido: a chave
            // EXISTE no ambiente, entao uma conferencia ingenua (so presenca)
            // deixaria passar, e o banco subiria com senha vazia.
            MockEnvironment prod = producaoCompleta();
            prod.setProperty("DB_PASSWORD", "");

            assertThatThrownBy(() -> BackendApplication.conferirCredenciaisDeProducao(prod))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB_PASSWORD")
                    .hasMessageNotContaining("DB_URL");
        }

        @Test
        @DisplayName("a mensagem de erro nao imprime o valor de nenhuma variavel")
        void mensagemNaoVazaValores() {
            // O log de subida de um deploy vai para o painel do provedor, para a
            // ferramenta de CI e para o historico de quem assistiu. Uma mensagem
            // de erro que "ajuda" imprimindo o valor transforma o proprio
            // mecanismo de diagnostico num vazamento de credencial.
            // MockEnvironment nao aceita null (o Properties do JDK e um
            // ConcurrentHashMap), entao "ausente" se escreve omitindo a chave:
            // o proprio helper deixa DB_URL de fora. A senha fica no ambiente
            // de proposito - e ela que o assert procura na mensagem.
            MockEnvironment prod = producaoCompleta("DB_URL");
            prod.setProperty("DB_PASSWORD", "SENHA-SECRETA-QUE-NAO-PODE-APARECER");

            assertThatThrownBy(() -> BackendApplication.conferirCredenciaisDeProducao(prod))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB_URL")
                    .hasMessageNotContaining("SENHA-SECRETA-QUE-NAO-PODE-APARECER")
                    .hasMessageNotContaining("valor-presente");
        }

        @Test
        @DisplayName("fora do perfil prod, a conferencia nao interfere")
        void foraDeProducaoNaoInterfere() {
            // Em desenvolvimento as credenciais vem dos padroes do
            // application.properties; exigir as sete variaveis ali quebraria a
            // subida local e a suite inteira.
            MockEnvironment dev = new MockEnvironment();

            assertThatCode(() -> BackendApplication.conferirCredenciaisDeProducao(dev))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("coerencia entre ambiente e perfil")
    class CoerenciaDeAmbiente {

        @Test
        @DisplayName("credenciais de producao sem perfil prod IMPEDEM a subida")
        void credenciaisDeProducaoSemPerfilImpedemSubir() {
            // O erro silencioso que isto pega: o operador define DB_PASSWORD no
            // servidor mas esquece SPRING_PROFILES_ACTIVE=prod. Sem a conferencia,
            // o sistema sobe normalmente com cookie SEM Secure - e nada avisa.
            MockEnvironment dev = new MockEnvironment();
            VerificadorDeAmbiente v = new VerificadorDeAmbiente(dev, "senha-do-banco", "senha-admin", true);

            assertThatThrownBy(() -> v.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("perfil 'prod' NAO esta ativo")
                    // A mensagem precisa dizer COMO corrigir, senao vira um erro
                    // que o operador nao sabe resolver as 3 da manha.
                    .hasMessageContaining("SPRING_PROFILES_ACTIVE=prod");
        }

        @Test
        @DisplayName("sem perfil prod e sem credenciais de producao, sobe normalmente")
        void desenvolvimentoSobeNormalmente() {
            MockEnvironment dev = new MockEnvironment();
            VerificadorDeAmbiente v = new VerificadorDeAmbiente(dev, "", "", true);

            assertThatCode(() -> v.run(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("com perfil prod, a conferencia se cala")
        void comPerfilProdNaoReclama() {
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            VerificadorDeAmbiente v = new VerificadorDeAmbiente(prod, "senha", "senha", true);

            assertThatCode(() -> v.run(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("com a conferencia desligada, nao interfere no build")
        void conferenciaDesligadaNaoInterfere() {
            // Este e o caso do CI e da maquina do dev que exporta o ambiente de
            // deploy: DB_PASSWORD esta definida, o perfil nao e prod, e mesmo
            // assim o build precisa rodar.
            MockEnvironment dev = new MockEnvironment();
            VerificadorDeAmbiente v = new VerificadorDeAmbiente(dev, "senha", "senha", false);

            assertThatCode(() -> v.run(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("o arquivo de teste desliga a conferencia de ambiente")
        void arquivoDeTesteDesligaAConferencia() throws IOException {
            // Guarda o proprio arranjo que faz a suite passar em qualquer
            // maquina: se alguem remover esta chave, o build comeca a quebrar
            // so na maquina de quem tem DB_PASSWORD exportada - e isso e o tipo
            // de falha que ninguem consegue reproduzir.
            String teste = Files.readString(
                    Path.of("src", "test", "resources", "application.properties"),
                    StandardCharsets.UTF_8);

            assertThat(teste).contains("app.security.conferir-ambiente=false");
        }
    }

    @Test
    @DisplayName("a suite roda com a conferencia de ambiente desligada")
    void suiteDesligaAConferencia() {
        // Confere o valor EFETIVO no contexto de teste, nao o texto do arquivo:
        // se o application.properties de teste deixasse de substituir o
        // principal (o trap que ja quebrou a suite duas vezes), este assert
        // pega a diferenca.
        assertThat(ambiente.getProperty("app.security.conferir-ambiente"))
                .isEqualTo("false");
    }

    @Nested
    @DisplayName("politica de senha do admin inicial")
    class PoliticaDeSenhaDoAdmin {

        @Test
        @DisplayName("em producao, senha curta IMPEDE a subida")
        void senhaCurtaImpedeSubirEmProducao() {
            AdminInicialSeeder s = seeder("admin.prod.curto", "curta", "prod");

            assertThatThrownBy(() -> s.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_SENHA")
                    // A mensagem nao pode conter a senha: o log de producao e
                    // lido por mais gente que o dono da credencial.
                    .hasMessageNotContaining("curta");
        }

        @Test
        @DisplayName("em producao, senha igual ao login IMPEDE a subida")
        void senhaIgualAoLoginImpedeSubirEmProducao() {
            // "administrador" tem 13 caracteres - passa no tamanho e ainda e
            // pessima. E o caso que o teste de comprimento sozinho nao pega.
            AdminInicialSeeder s = seeder("administrador", "administrador", "prod");

            assertThatThrownBy(() -> s.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("igual ao proprio login");
        }

        @Test
        @DisplayName("em producao, senha de lista de vazadas IMPEDE a subida")
        void senhaPadraoConhecidaImpedeSubirEmProducao() {
            // "senhadopainel" nao existe na lista; "password" tem 8 caracteres e
            // pararia no teste de comprimento, passando por engano. A entrada
            // usada aqui precisa ter 12+ caracteres E estar na lista.
            //
            // LIMITACAO HONESTA DESTA DEFESA: uma lista de senhas proibidas so
            // pega o que esta escrita nela. "lavajato123456" tem 13 caracteres,
            // e pessima, e passa - porque nao esta na lista. Quem segura esse
            // caso e o COMPRIMENTO, nao a lista. A lista existe para os padroes
            // obvios ("password", "admin123"), e nao como garantia.
            AdminInicialSeeder s = seeder("chefe", "123456789012", "prod");

            assertThatThrownBy(() -> s.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("senhas padrao conhecidas");
        }

        @Test
        @DisplayName("em producao, sem as variaveis IMPEDE a subida")
        void semVariaveisImpedeSubirEmProducao() {
            // Um sistema no ar onde ninguem consegue administrar nao e aceitavel:
            // a falha aparece na subida, com motivo, e nao quando alguem tenta
            // entrar e descobre que nao existe conta.
            AdminInicialSeeder s = seeder("", "", "prod");

            assertThatThrownBy(() -> s.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("obrigatorios em producao");
        }

        @Test
        @DisplayName("em producao, senha forte cria o admin normalmente")
        void senhaForteCriaAdmin() {
            apagarSeExistir("admin.prod.ok");

            seeder("admin.prod.ok", "SenhaForteDeProducao123", "prod").run(null);

            Usuario criado = usuarios.findByLogin("admin.prod.ok").orElseThrow();
            assertThat(criado.getPerfil()).isEqualTo(Perfil.ADMIN);
            assertThat(criado.isAtivo()).isTrue();

            // A senha NUNCA e gravada em claro. Este assert e o que garante
            // que uma mudanca futura no seeder nao passe a guardar texto puro.
            assertThat(criado.getSenha())
                    .as("a senha precisa estar em hash BCrypt, nunca em claro")
                    .isNotEqualTo("SenhaForteDeProducao123")
                    .startsWith("$2");

            usuarios.delete(criado);
        }

        @Test
        @DisplayName("em desenvolvimento, senha fraca apenas avisa e nao derruba o app")
        void emDesenvolvimentoSenhaFracaApenasAvisa() {
            // A diferenca entre os perfis e deliberada: em dev a porta e local e
            // o banco e local, entao travar a subida so faria o dev perder tempo
            // com uma decisao sem consequencia. Em producao e o contrario.
            apagarSeExistir("admin.dev.fraca");

            seeder("admin.dev.fraca", "curta").run(null);

            Usuario criado = usuarios.findByLogin("admin.dev.fraca").orElseThrow();
            assertThat(criado.getPerfil()).isEqualTo(Perfil.ADMIN);
            usuarios.delete(criado);
        }

        @Test
        @DisplayName("criar duas vezes nao duplica nem troca a senha")
        void criacaoEhIdempotente() {
            apagarSeExistir("admin.idempotente");

            seeder("admin.idempotente", "SenhaOriginalLonga123", "prod").run(null);
            String hashOriginal = usuarios.findByLogin("admin.idempotente").orElseThrow().getSenha();

            // Segunda subida com senha DIFERENTE: se o seeder sobrescrevesse,
            // reiniciar a aplicacao resetaria a senha de todo mundo.
            seeder("admin.idempotente", "SenhaDiferenteLonga456", "prod").run(null);

            assertThat(usuarios.findByLogin("admin.idempotente").orElseThrow().getSenha())
                    .as("o seeder nao pode trocar a senha de um admin que ja existe")
                    .isEqualTo(hashOriginal);

            usuarios.delete(usuarios.findByLogin("admin.idempotente").orElseThrow());
        }
    }

    private static String ler(Path caminho) throws IOException {
        assertThat(caminho).as("arquivo ausente: %s", caminho).exists();
        return Files.readString(caminho, StandardCharsets.UTF_8);
    }
}
