package br.com.lavajato.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Cria o banco de dados quando ele ainda nao existe.
 *
 * POR QUE ISTO EXISTE
 *
 * O Flyway cria as TABELAS, mas nao cria o BANCO: ele conecta em um banco que
 * ja precisa existir. Numa maquina recem-instalada, subir a aplicacao morre com
 *
 *   FATAL: database "lavajato" does not exist
 *
 * O que, para quem acabou de instalar o PostgreSQL e nao conhece o projeto, e
 * uma mensagem que nao diz o que fazer. Era exatamente o caso do pacote de
 * teste de invasao: o amigo nao tem o banco, e criar a mao exigiria mexer no
 * pgAdmin ou no psql.
 *
 * POR QUE DESLIGADO POR PADRAO
 *
 * Criar banco e uma operacao de INSTALACAO, nao algo que a aplicacao deva fazer
 * toda vez que sobe. Ligado por padrao, um erro de digitacao na URL criaria um
 * banco vazio e o Flyway o encheria - e o sistema subiria "funcionando" contra
 * uma base nova, em vez de reclamar da URL errada. Quem liga e o inicializador
 * de teste, de forma explicita, via app.banco.criar-se-faltar=true.
 *
 * POR QUE UM EnvironmentPostProcessor, E NAO UM BEAN
 *
 * Precisa rodar ANTES do datasource existir: se esperar virar bean, o Hikari
 * ja tentou conectar e ja falhou. Aqui o trabalho acontece enquanto o ambiente
 * esta sendo montado, antes de qualquer bean.
 *
 * Diferente do que o javadoc do Boot 4.1 ainda sugere, o Spring Framework 7
 * descobre isto por META-INF/spring.factories com a chave
 * org.springframework.boot.env.EnvironmentPostProcessor - foi assim que este
 * arquivo passou a rodar (a variante .../spring/&lt;chave&gt;.imports nao foi
 * usada aqui porque nao ha necessidade de condicional).
 *
 * A SENHA NAO APARECE EM LUGAR NENHUM
 *
 * Este codigo nunca escreve usuario, senha ou URL no log, nem no caminho de
 * erro. O log de uma maquina de teste vai para a tela e pode ser fotografado ou
 * colado num chat; a credencial do banco nao deve estar nele.
 */
public class CriadorDeBanco implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CriadorDeBanco.class);

    /** Liga o recurso. Desligado por padrao - ver o javadoc da classe. */
    private static final String CHAVE_LIGAR = "app.banco.criar-se-faltar";

    /** Banco de manutencao, sempre presente numa instalacao nova do PostgreSQL. */
    private static final String BANCO_ADMINISTRATIVO = "postgres";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
        if (!ambiente.getProperty(CHAVE_LIGAR, Boolean.class, false)) {
            return;
        }

        String url = ambiente.getProperty("spring.datasource.url");
        String usuario = ambiente.getProperty("spring.datasource.username");
        String senha = ambiente.getProperty("spring.datasource.password");

        if (url == null || url.isBlank() || usuario == null || senha == null) {
            log.warn("CriadorDeBanco: URL/usuario/senha do datasource nao estao definidos. "
                    + "Nada a fazer.");
            return;
        }

        String nomeDoBanco = extrairNomeDoBanco(url);
        if (nomeDoBanco == null) {
            // URL em formato inesperado (nao e o padrao jdbc:postgresql://host:porta/banco).
            // Melhor nao adivinhar: seguir em frente deixa o erro original do driver
            // aparecer, que e mais honesto do que criar um banco com nome errado.
            log.warn("CriadorDeBanco: nao foi possivel identificar o nome do banco na URL "
                    + "configurada. Nenhum banco sera criado.");
            return;
        }

        try {
            if (bancoExiste(url, usuario, senha)) {
                log.info("CriadorDeBanco: o banco '{}' ja existe.", nomeDoBanco);
                return;
            }

            // Chegar aqui significa que o banco NAO existe. A conexao acima falhou
            // com "database does not exist" - nao com erro de credencial nem de
            // servidor fora do ar - entao vale tentar criar.
            criarBanco(url, nomeDoBanco, usuario, senha);

        } catch (SQLException e) {
            // Nao derruba a subida da aplicacao: se a criacao falhar, o erro real
            // (o do Flyway/Hibernate) aparece logo em seguida e e mais informativo.
            // Este metodo existe para AJUDAR quem esta comecando, nao para virar
            // mais uma barreira entre a pessoa e a mensagem de erro de verdade.
            log.warn("CriadorDeBanco: nao foi possivel preparar o banco automaticamente ({}). "
                    + "A aplicacao vai seguir e o erro original deve aparecer abaixo. "
                    + "Se for 'database does not exist', crie o banco a mao.",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * O banco alvo ja existe?
     *
     * Conecta direto na URL configurada: se a conexao funciona, o banco existe.
     * E a checagem mais direta possivel - nao depende de consultar catalogo nem
     * de permissao em pg_database, e vale para qualquer hospedagem.
     */
    private boolean bancoExiste(String url, String usuario, String senha) {
        try (Connection ignorada = conectar(url, usuario, senha)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Cria o banco conectando no banco administrativo "postgres".
     *
     * O CREATE DATABASE nao pode rodar dentro de transacao, e o autocommit do
     * JDBC ja satisfaz isso - por isso nao ha begin/commit aqui.
     */
    private void criarBanco(String url, String nomeDoBanco, String usuario, String senha)
            throws SQLException {

        String urlAdministrativa = trocarNomeDoBanco(url, BANCO_ADMINISTRATIVO);

        try (Connection conexao = conectar(urlAdministrativa, usuario, senha);
             Statement comando = conexao.createStatement()) {

            // O nome do banco vem da configuracao do dono do sistema, nao de
            // entrada de usuario na rede - mas ainda assim passa por uma
            // validacao de formato, porque ele entra numa instrucao SQL por
            // concatenacao (o CREATE DATABASE nao aceita parametro vinculado).
            comando.executeUpdate("CREATE DATABASE " + citarIdentificador(nomeDoBanco));
            log.info("CriadorDeBanco: banco '{}' criado. O Flyway vai criar as tabelas agora.",
                    nomeDoBanco);
        }
    }

    private Connection conectar(String url, String usuario, String senha) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", usuario);
        props.setProperty("password", senha);
        // Curto de proposito: se o servidor nao responde, e melhor falhar rapido e
        // deixar a aplicacao seguir para o erro real do que travar a subida aqui.
        props.setProperty("connectTimeout", "5");
        return DriverManager.getConnection(url, props);
    }

    /**
     * "jdbc:postgresql://localhost:5432/lavajato" -&gt; "lavajato".
     *
     * Devolve null quando o formato nao e o esperado, para que quem chama nao
     * invente um nome.
     */
    static String extrairNomeDoBanco(String url) {
        String semPrefixo = removerPrefixo(url);
        if (semPrefixo == null) {
            return null;
        }

        // Descarta host:porta e qualquer query string (?sslmode=...).
        int barra = semPrefixo.indexOf('/');
        if (barra < 0 || barra == semPrefixo.length() - 1) {
            return null;
        }

        String resto = semPrefixo.substring(barra + 1);
        int interrogacao = resto.indexOf('?');
        if (interrogacao >= 0) {
            resto = resto.substring(0, interrogacao);
        }

        return resto.isBlank() ? null : resto;
    }

    /** Troca o nome do banco na URL, preservando host, porta e parametros. */
    static String trocarNomeDoBanco(String url, String novoNome) {
        String semPrefixo = removerPrefixo(url);
        if (semPrefixo == null) {
            return url;
        }

        int barra = semPrefixo.indexOf('/');
        if (barra < 0) {
            return url;
        }

        String prefixo = url.substring(0, url.length() - semPrefixo.length());
        String ateHost = semPrefixo.substring(0, barra);
        String depois = semPrefixo.substring(barra + 1);

        int interrogacao = depois.indexOf('?');
        String query = interrogacao >= 0 ? depois.substring(interrogacao) : "";

        return prefixo + ateHost + "/" + novoNome + query;
    }

    private static String removerPrefixo(String url) {
        String prefixo = "jdbc:postgresql://";
        if (url == null || !url.startsWith(prefixo)) {
            return null;
        }
        return url.substring(prefixo.length());
    }

    /**
     * Poe o nome entre aspas duplas para o SQL, escapando aspas internas.
     *
     * Sem isto, um nome com maiuscula ou hifen quebraria a instrucao - e mais
     * importante, seria o ponto por onde um valor de configuracao viraria SQL.
     */
    private static String citarIdentificador(String nome) {
        return "\"" + nome.replace("\"", "\"\"") + "\"";
    }
}
