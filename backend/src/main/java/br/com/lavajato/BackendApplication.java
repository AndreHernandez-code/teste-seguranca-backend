package br.com.lavajato;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class BackendApplication {

	/**
	 * Variaveis que o application-prod.properties exige, sem valor padrao.
	 *
	 * Ver {@link #conferirCredenciaisDeProducao} para o motivo de a conferencia
	 * estar aqui e nao numa anotacao.
	 */
	private static final List<String> OBRIGATORIAS_EM_PRODUCAO = List.of(
			"DB_URL",
			"DB_USER",
			"DB_PASSWORD",
			"DB_MIGRATION_USER",
			"DB_MIGRATION_PASSWORD",
			"ADMIN_LOGIN",
			"ADMIN_SENHA");

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(BackendApplication.class);
		app.addInitializers(contexto -> conferirCredenciaisDeProducao(contexto.getEnvironment()));
		app.run(args);
	}

	/**
	 * Recusa subir em producao quando falta uma credencial obrigatoria, dizendo
	 * QUAL falta (Etapa 7).
	 *
	 * POR QUE ISTO EXISTE
	 *
	 * Em application-prod.properties as credenciais estao escritas como
	 * ${DB_URL}, ${DB_PASSWORD} etc - sem o ":valor" -, na intencao de que a
	 * aplicacao falhe se a variavel nao existir. Isso foi TESTADO contra o jar
	 * de verdade, e a intencao NAO se cumpre:
	 *
	 *   java -jar backend.jar --spring.profiles.active=prod      (sem DB_URL)
	 *   -> exit 1, mensagem: 'url' must start with "jdbc"
	 *
	 * A aplicacao para (que e o essencial, e e o que a etapa precisava
	 * garantir), mas a mensagem nao menciona variavel nenhuma. Motivo: nessa
	 * posicao o Spring resolve o placeholder ausente para string VAZIA em vez
	 * de falhar, e quem reclama primeiro e o Hikari, com um erro generico de
	 * sintaxe de URL. Quem esquecer DB_URL no servidor vai procurar "erro de
	 * configuracao de banco" - quando o problema e uma variavel nao exportada.
	 *
	 * POR QUE AQUI E NAO NUMA ANOTACAO OU PROCESSOR
	 *
	 * Tentei o caminho "correto" primeiro: um EnvironmentPostProcessor
	 * registrado em META-INF/spring.factories. Nao rodou, e a causa vale
	 * registro porque nao da erro nenhum:
	 *
	 *   - O javadoc do Boot 4.1 ainda manda registrar em spring.factories com a
	 *     chave org.springframework.boot.EnvironmentPostProcessor.
	 *   - O Spring Framework 7, na pratica, procura o arquivo em
	 *     META-INF/spring/&lt;chave&gt;.imports.
	 *   - Nos dois formatos, arquivo presente no jar, classe compilada, e
	 *     NENHUM aviso: simplesmente nao roda, e o erro generico do Hikari
	 *     volta, como se a classe nao existisse.
	 *
	 * Uma conferencia que depende de um mecanismo que falha em silencio e pior
	 * que uma conferencia chata de ler. Um ApplicationContextInitializer
	 * registrado no proprio main e o oposto disso: roda sempre, nao depende de
	 * descoberta por arquivo, e da para testar chamando o metodo direto.
	 *
	 * Roda ANTES de qualquer bean - inclusive do datasource. Por isso a falha
	 * aparece com a lista do que falta, e nao disfarcada de erro de driver.
	 *
	 * A mensagem NAO imprime o valor de nada, nem o das variaveis presentes: o
	 * console de um deploy vai para o painel do provedor, para a ferramenta de
	 * CI e para quem assistiu a subida.
	 */
	public static void conferirCredenciaisDeProducao(ConfigurableEnvironment ambiente) {
		if (!Arrays.asList(ambiente.getActiveProfiles()).contains("prod")) {
			return;
		}

		List<String> faltando = new ArrayList<>();
		for (String variavel : OBRIGATORIAS_EM_PRODUCAO) {
			String valor = ambiente.getProperty(variavel);
			if (valor == null || valor.isBlank()) {
				faltando.add(variavel);
			}
		}

		if (!faltando.isEmpty()) {
			// Falha com TODAS as que faltam, e nao so a primeira: descobrir uma
			// variavel esquecida por tentativa de deploy seria um ciclo lento e
			// desnecessario.
			throw new IllegalStateException(
					"Producao exige as seguintes variaveis de ambiente, e estas nao foram "
							+ "definidas: " + String.join(", ", faltando) + ". "
							+ "Defina todas e suba de novo. Os valores NAO aparecem aqui de "
							+ "proposito - confira no gerenciador de segredos do provedor ou "
							+ "no arquivo de ambiente do servidor (que nao deve estar "
							+ "versionado).");
		}
	}

}
