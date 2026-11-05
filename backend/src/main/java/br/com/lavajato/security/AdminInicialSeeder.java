package br.com.lavajato.security;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Cria o primeiro ADMIN a partir de variaveis de ambiente.
 *
 * Nao existe senha fixa no codigo: se ADMIN_LOGIN/ADMIN_SENHA nao estiverem
 * definidas, nada e criado e o aviso vai para o log. Assim o repositorio nunca
 * carrega uma credencial padrao conhecida.
 */
@Component
public class AdminInicialSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInicialSeeder.class);

    /**
     * Comprimento minimo da senha do admin. Em producao, abaixo disto a
     * aplicacao NAO SOBE; em dev e apenas um aviso.
     *
     * 12 e o numero que o resto do sistema ja exige (UsuarioController: o
     * cadastro de funcionario recusa senha menor que 12). Manter o admin com
     * regra mais frouxa que os funcionarios que ele cria seria incoerente.
     */
    private static final int TAMANHO_MINIMO_SENHA = 12;

    /**
     * Senhas que nao valem nem em desenvolvimento, porque sao as primeiras que
     * qualquer tentativa automatizada testa. A checagem e por igualdade exata
     * (sem diferenciar maiuscula), e nao por "conter": "admin12345678" contem
     * "admin" e nem por isso e uma senha ruim de verdade - o que importa e ela
     * NAO SER a senha inteira.
     */
    private static final Set<String> SENHAS_PROIBIDAS = Set.of(
            "admin", "admin123", "administrador", "password", "senha",
            "123456789012", "lavajato", "lavajato123", "mudar123456", "trocar123456");

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final Environment ambiente;
    private final String adminLogin;
    private final String adminSenha;

    public AdminInicialSeeder(UsuarioRepository usuarios,
                              PasswordEncoder encoder,
                              Environment ambiente,
                              @Value("${app.admin.login:}") String adminLogin,
                              @Value("${app.admin.senha:}") String adminSenha) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.ambiente = ambiente;
        this.adminLogin = adminLogin;
        this.adminSenha = adminSenha;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean producao = ehProducao();

        if (adminLogin.isBlank() || adminSenha.isBlank()) {
            // Em producao, o application-prod.properties JA exige as duas
            // variaveis (sem valor padrao), entao nao se chega aqui por
            // esquecimento: se chegou, alguem definiu a variavel VAZIA.
            // Subir um sistema onde ninguem consegue administrar nao e uma
            // opcao aceitavel, entao em producao isto e erro.
            if (producao) {
                throw new IllegalStateException(
                        "ADMIN_LOGIN e ADMIN_SENHA sao obrigatorios em producao. "
                                + "Defina as duas variaveis de ambiente e suba de novo.");
            }
            log.warn("ADMIN_LOGIN/ADMIN_SENHA nao definidos: nenhum usuario inicial foi criado. "
                    + "Defina as variaveis de ambiente para criar o admin.");
            return;
        }

        exigirSenhaAceitavel(producao);

        String login = adminLogin.trim().toLowerCase();
        if (usuarios.existsByLogin(login)) {
            log.info("Usuario admin '{}' ja existe: nada a fazer.", login);
            return;
        }

        Usuario admin = new Usuario();
        admin.setLogin(login);
        admin.setNome("Administrador");
        admin.setPerfil(Perfil.ADMIN);
        admin.setAtivo(true);
        admin.setSenha(encoder.encode(adminSenha));
        usuarios.save(admin);

        // Log sem a senha, obviamente.
        log.info("Usuario admin '{}' criado com perfil ADMIN.", login);
    }

    /**
     * Em producao, senha fraca impede a subida; em dev, apenas avisa.
     *
     * Por que a diferenca: em desenvolvimento, travar a subida por causa de uma
     * senha curta so faria o dev perder tempo com uma decisao que nao tem
     * consequencia ali (o banco e local, a porta e local). Em producao a conta
     * e alcancavel pela internet, e "admin" cai em minutos.
     *
     * Por que LANCA excecao em vez de apenas recusar criar: se apenas
     * recusasse, o resultado seria um sistema NO AR, acessivel, sem ninguem
     * capaz de administra-lo - e a falha so apareceria quando alguem tentasse
     * entrar e nao conseguisse. Falhar na subida mostra o problema na hora,
     * com o motivo escrito no log, antes de o sistema receber a primeira
     * requisicao.
     */
    private void exigirSenhaAceitavel(boolean producao) {
        String problema = avaliarSenha(adminSenha);

        if (problema == null) {
            return;
        }

        if (producao) {
            // A mensagem NAO inclui a senha nem o tamanho dela: o log de
            // producao pode ser lido por mais gente que o dono da credencial.
            throw new IllegalStateException(
                    "ADMIN_SENHA nao atende a politica minima em producao (" + problema + "). "
                            + "Use uma senha de pelo menos " + TAMANHO_MINIMO_SENHA
                            + " caracteres, diferente do login e que nao seja um padrao conhecido.");
        }

        log.warn("ADMIN_SENHA {} Em desenvolvimento isso e apenas um aviso; "
                + "em producao, a aplicacao recusa subir assim.", problema);
    }

    /**
     * Devolve o motivo da recusa, ou null se a senha passa.
     *
     * Sao tres checagens, e cada uma pega um erro diferente:
     *  - comprimento: senha curta e quebravel por forca bruta direta.
     *  - igual ao login: "admin"/"admin" e o primeiro par que se tenta.
     *  - padrao conhecido: senha que aparece em qualquer lista de senhas vazadas.
     */
    private String avaliarSenha(String senha) {
        if (senha.length() < TAMANHO_MINIMO_SENHA) {
            return "tem menos de " + TAMANHO_MINIMO_SENHA + " caracteres.";
        }
        if (senha.equalsIgnoreCase(adminLogin.trim())) {
            return "e igual ao proprio login.";
        }
        if (SENHAS_PROIBIDAS.contains(senha.trim().toLowerCase())) {
            return "esta na lista de senhas padrao conhecidas.";
        }
        return null;
    }

    /** A aplicacao esta subindo com o perfil 'prod' ativo? */
    private boolean ehProducao() {
        return Arrays.asList(ambiente.getActiveProfiles()).contains("prod");
    }
}
