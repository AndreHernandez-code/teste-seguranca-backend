package br.com.lavajato.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Confere que o perfil ativo e coerente com o ambiente em que a aplicacao esta
 * subindo, e recusa subir no caso perigoso (Etapa 7).
 *
 * O PROBLEMA QUE ISTO RESOLVE
 *
 * O perfil 'prod' e o que liga cookie Secure, exige as credenciais do banco e
 * aplica a politica de senha do admin. E ele so vale se for ATIVADO - e ativar
 * depende de uma variavel de ambiente (SPRING_PROFILES_ACTIVE=prod) que mora
 * FORA do projeto, no script de deploy, no painel do provedor, no arquivo do
 * systemd.
 *
 * Esquecer essa variavel e um erro silencioso e perigoso: a aplicacao sobe
 * normalmente, com as configuracoes de DESENVOLVIMENTO - cookie sem Secure, o
 * datasource caindo no padrao local, mensagens de erro mais verbosas. Nada
 * quebra, nada avisa, e o sistema fica no ar em estado enfraquecido.
 *
 * COMO ISTO DETECTA
 *
 * Pela propria marca de producao: quem vai rodar em producao define
 * DB_PASSWORD (e as demais variaveis) no ambiente, porque o
 * application-prod.properties as exige. Entao a regra e:
 *
 *   Se as variaveis que SO existem em producao estao presentes, mas o perfil
 *   'prod' NAO esta ativo, alguma coisa esta errada - e a mais provavel e que
 *   a variavel do perfil foi esquecida.
 *
 * Nesse caso a aplicacao NAO SOBE. E o certo: um deploy que para e visivel e
 * corrigivel em segundos; um deploy que sobe sem Secure em cookie de sessao
 * expoe a sessao de todo mundo e nao aparece em log nenhum.
 *
 * DESLIGADO NO TESTE E NO DESENVOLVIMENTO
 *
 * A conferencia so roda quando app.security.conferir-ambiente=true, que e o
 * padrao. O arquivo de teste desliga (src/test/resources/application.properties).
 *
 * Por que a chave existe em vez de a checagem ser sempre ligada: em
 * desenvolvimento e em CI e NORMAL ter DB_PASSWORD definida no ambiente - quem
 * roda o build costuma exportar o ambiente de deploy inteiro. Sem a chave, a
 * suite de testes quebraria em qualquer maquina assim, e um teste que quebra
 * por causa de uma variavel de ambiente alheia ao teste e um teste que ninguem
 * confia.
 *
 * O QUE ISTO NAO E
 *
 * Nao e um detector de "estou em producao?" - isso seria adivinhacao, e
 * adivinhacao erra. E uma conferencia de COERENCIA entre duas coisas que o
 * operador declarou: as credenciais e o perfil. Se as duas apontam para lados
 * diferentes, quem esta errado e o operador, e o aviso e para ele.
 *
 * Tambem nao substitui o painel do provedor nem o firewall: e a ultima linha,
 * dentro do processo, para o caso de a primeira ter sido esquecida.
 */
@Component
public class VerificadorDeAmbiente implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VerificadorDeAmbiente.class);

    private final Environment ambiente;
    private final String dbPassword;
    private final String adminSenha;
    private final boolean conferir;

    public VerificadorDeAmbiente(Environment ambiente,
                                 @Value("${spring.datasource.password:}") String dbPassword,
                                 @Value("${app.admin.senha:}") String adminSenha,
                                 @Value("${app.security.conferir-ambiente:true}") boolean conferir) {
        this.ambiente = ambiente;
        this.dbPassword = dbPassword;
        this.adminSenha = adminSenha;
        this.conferir = conferir;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!conferir || temPerfilProd()) {
            return;
        }

        if (credenciaisDeProducaoPresentes()) {
            throw new IllegalStateException(
                    "Credenciais de producao foram definidas (DB_PASSWORD e ADMIN_SENHA), "
                            + "mas o perfil 'prod' NAO esta ativo. Suba com "
                            + "SPRING_PROFILES_ACTIVE=prod (ou --spring.profiles.active=prod). "
                            + "Sem o perfil, a aplicacao roda com cookie de sessao SEM Secure, "
                            + "credenciais padrao do banco de desenvolvimento e politica de "
                            + "senha frouxa - configuracao de desenvolvimento exposta em producao.");
        }

        // Sem perfil prod e sem credenciais de producao: e o caso normal de
        // desenvolvimento local. O perfil dev nao existe como arquivo proprio
        // (o application.properties ja e o padrao de dev), entao nao ha mais
        // nada a conferir aqui.
        log.info("Perfil ativo: {}. Ambiente de desenvolvimento.",
                Arrays.toString(ambiente.getActiveProfiles()));
    }

    private boolean temPerfilProd() {
        return Arrays.asList(ambiente.getActiveProfiles()).contains("prod");
    }

    /**
     * DB_PASSWORD e ADMIN_SENHA sao exigidas SEM valor padrao em
     * application-prod.properties: quem as define no ambiente esta declarando
     * que vai rodar em producao.
     */
    private boolean credenciaisDeProducaoPresentes() {
        return !dbPassword.isBlank() && !adminSenha.isBlank();
    }
}
