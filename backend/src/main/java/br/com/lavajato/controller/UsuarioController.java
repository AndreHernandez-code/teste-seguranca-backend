package br.com.lavajato.controller;

import br.com.lavajato.exception.RegraNegocioException;
import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import br.com.lavajato.security.GerenciadorDeSessoes;
import br.com.lavajato.security.SessaoAtual;
import br.com.lavajato.validation.GruposValidacao;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestao de funcionarios. O SecurityConfig restringe tudo aqui a ADMIN. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private static final Logger log = LoggerFactory.getLogger(UsuarioController.class);

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final GerenciadorDeSessoes sessoes;

    public UsuarioController(UsuarioRepository usuarios, PasswordEncoder encoder,
                             GerenciadorDeSessoes sessoes) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.sessoes = sessoes;
    }

    /** Saida sem a senha: a entidade nunca vai inteira para o JSON. */
    public record UsuarioSaida(Long id, String login, String nome, Perfil perfil, boolean ativo) {
        static UsuarioSaida de(Usuario u) {
            return new UsuarioSaida(u.getId(), u.getLogin(), u.getNome(), u.getPerfil(), u.isAtivo());
        }
    }

    /**
     * Senha: obrigatoria na criacao (grupo Criacao). Na edicao pode vir vazia,
     * que significa "manter a senha atual" - por isso o @NotBlank so vale no
     * grupo Criacao.
     *
     * O @Size precisa declarar os DOIS grupos explicitamente: com groups
     * declarados, a constraint deixa de pertencer ao grupo Default, e as rotas
     * validam apenas Criacao ou Edicao - sem isso, a senha curta passaria.
     * Como @Size em branco tambem falha (min 12), na edicao o campo vazio e
     * normalizado para null antes de validar: @Size aceita null.
     */
    public record UsuarioEntrada(
            @NotBlank(message = "Informe o login.")
            @Size(min = 3, max = 60, message = "O login deve ter entre 3 e 60 caracteres.")
            String login,
            @NotBlank(groups = GruposValidacao.Criacao.class, message = "Informe a senha.")
            @Size(groups = {GruposValidacao.Criacao.class, GruposValidacao.Edicao.class},
                    min = 12, max = 100, message = "A senha deve ter entre 12 e 100 caracteres.")
            String senha,
            @NotBlank(message = "Informe o nome do funcionario.")
            @Size(max = 120, message = "O nome deve ter no maximo 120 caracteres.")
            String nome,
            @NotNull(message = "Selecione o perfil.") Perfil perfil,
            /**
             * Situacao da conta. Nulo significa "nao mexer" - e o que mantem
             * compativel quem ja envia o JSON sem este campo (o front antigo, e
             * os testes existentes). Sem o nulo-como-ausente, um PUT que nao
             * mencionasse o campo reativaria quem estava desativado, porque o
             * boolean do record cairia em false e nao haveria como distinguir
             * "desative" de "nao falei nada".
             *
             * Nao ha @NotNull: numa criacao o campo e simplesmente ignorado
             * (todo funcionario nasce ativo), como antes.
             */
            Boolean ativo) {

        /**
         * Normaliza a senha em branco para null ANTES da validacao (construtor
         * compacto dos records roda na desserializacao, antes dos validadores).
         * Assim "senha":"" na edicao significa "manter a atual" e passa pelo
         * @Size, que ignora null. Na criacao o @NotBlank continua barrando null.
         */
        public UsuarioEntrada {
            if (senha != null && senha.isBlank()) {
                senha = null;
            }
        }
    }

    @GetMapping
    public List<UsuarioSaida> listar() {
        return usuarios.findAll().stream().map(UsuarioSaida::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioSaida criar(@Validated(GruposValidacao.Criacao.class) @RequestBody UsuarioEntrada dados) {
        String login = dados.login().trim().toLowerCase();
        if (usuarios.existsByLogin(login)) {
            throw new RegraNegocioException("Ja existe um funcionario com este login.");
        }

        Usuario novo = new Usuario();
        novo.setLogin(login);
        novo.setNome(dados.nome());
        novo.setPerfil(dados.perfil());
        novo.setAtivo(true);
        novo.setSenha(encoder.encode(dados.senha()));

        log.info("Usuario '{}' criado com perfil {}.", login, dados.perfil());
        return UsuarioSaida.de(usuarios.save(novo));
    }

    /**
     * Edicao de funcionario.
     *
     * ATENCAO ao grupo de validacao (corrigido em auditoria): o @Validated
     * precisa incluir Default.class ALEM de Edicao.class.
     *
     * Motivo: as constraints de LOGIN, NOME e PERFIL nao declaram "groups" -
     * logo pertencem ao grupo Default. Ao validar SOMENTE Edicao.class (como
     * estava antes), o Bean Validation NAO inclui Default automaticamente
     * (Edicao e uma interface vazia, nao estende Default), e essas constraints
     * deixavam de ser avaliadas no PUT. Consequencia real: "perfil": null
     * passava pela validacao, e o metodo seguiria com um perfil nulo -
     * disparando ora uma violacao de NOT NULL no banco (409/500), ora um
     * tratamento incorreto na checagem do ultimo admin.
     *
     * O que dispensamos na edicao e apenas o @NotBlank da SENHA (grupo Criacao):
     * isso continua fora porque Criacao nao esta na lista.
     */
    @PutMapping("/{id}")
    public UsuarioSaida atualizar(@PathVariable Long id,
                                  @Validated({Default.class, GruposValidacao.Edicao.class}) @RequestBody UsuarioEntrada dados) {
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new RegraNegocioException(HttpStatus.NOT_FOUND, "Funcionario nao encontrado."));

        boolean ativoAnterior = usuario.isAtivo();
        boolean seraDesativado = Boolean.FALSE.equals(dados.ativo());

        protegerUltimoAdmin(usuario, dados.perfil());
        protegerUltimoAdminAtivo(usuario, ativoAnterior, seraDesativado);

        // O que MUDOU, capturado ANTES de gravar - e o que decide se as sessoes
        // precisam cair. Ver o javadoc de encerrarSessoesSeNecessario.
        Perfil perfilAnterior = usuario.getPerfil();
        boolean senhaTrocada = dados.senha() != null;

        usuario.setNome(dados.nome());
        usuario.setPerfil(dados.perfil());
        // Senha so muda se veio preenchida: permite editar o nome sem trocar a
        // senha. O record ja converteu branco em null, entao basta testar null.
        if (senhaTrocada) {
            usuario.setSenha(encoder.encode(dados.senha()));
        }
        // null = nao mexer na situacao. E assim que um PUT que so corrige o
        // nome nao reativa (nem desativa) ninguem por acidente.
        if (dados.ativo() != null) {
            usuario.setAtivo(dados.ativo());
        }

        Usuario salvo = usuarios.save(usuario);
        encerrarSessoesSeNecessario(salvo, perfilAnterior, senhaTrocada, ativoAnterior);
        return UsuarioSaida.de(salvo);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desativar(@PathVariable Long id) {
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new RegraNegocioException(HttpStatus.NOT_FOUND, "Funcionario nao encontrado."));

        if (usuario.isAtivo() && usuario.getPerfil() == Perfil.ADMIN && ehUltimoAdminAtivo()) {
            throw new RegraNegocioException(
                    "Nao e possivel desativar: este e o ultimo administrador ativo do sistema.");
        }

        // Desativa em vez de apagar: preserva o historico de quem operou.
        usuario.setAtivo(false);
        usuarios.save(usuario);

        // A sessao aberta nao morre sozinha: o Spring Security le o contexto
        // gravado na sessao e nao reconsulta o banco. Sem isto, um funcionario
        // demitido continua operando ate as 2 horas de sessao expirarem - e o
        // computador do balcao e onde a sessao fica aberta o dia inteiro.
        sessoes.encerrarSessoesDe(usuario);

        log.info("Usuario '{}' desativado.", usuario.getLogin());
    }

    /**
     * Derruba as sessoes abertas quando a mudanca altera PODERES ou CREDENCIAL.
     *
     * Por que so nesses casos, e nao em toda edicao: encerrar sessoes a cada
     * edicao deslogaria o funcionario por causa de uma correcao de nome - um
     * efeito que ninguem ligaria a causa, e que faria o ADMIN ter medo de mexer
     * no cadastro. Nome nao concede poder nenhum.
     *
     * O que encerra sessao:
     *  - mudanca de PERFIL: o SecurityContext da sessao antiga tem a authority
     *    do perfil anterior. Rebaixar um ADMIN e nao encerrar deixa ele
     *    excluindo registros como ADMIN - foi o que o teste mediu antes desta
     *    correcao, com a sessao de um rebaixado apagando um cliente.
     *  - troca de SENHA: quem tinha a senha antiga em outro dispositivo precisa
     *    ser expulso. Senao a troca de senha nao cumpre o que promete - se o
     *    motivo da troca for "acho que alguem sabe minha senha", nao trocar
     *    nada seria equivalente.
     *
     * A sessao que FAZ a troca e preservada quando o alvo e o proprio usuario
     * logado: derrubar a propria sessao no instante em que se confirma a
     * alteracao faria o usuario concluir que o sistema quebrou.
     */
    private void encerrarSessoesSeNecessario(Usuario usuario, Perfil perfilAnterior,
                                             boolean senhaTrocada, boolean ativoAnterior) {
        boolean perfilMudou = perfilAnterior != usuario.getPerfil();
        // Desativar pela EDICAO precisa ter o mesmo efeito de desativar pelo
        // DELETE. Sem isto, a correcao de sessao (Etapa 8) teria uma porta dos
        // fundos: bastava desmarcar "Ativo" no formulario para o funcionario
        // demitido seguir operando com a sessao ja aberta ate expirarem as 2h.
        boolean foiDesativado = ativoAnterior && !usuario.isAtivo();

        if (!perfilMudou && !senhaTrocada && !foiDesativado) {
            return;
        }

        if (usuario.getLogin().equals(loginLogado())) {
            // Quem opera e o proprio dono da conta: preserva a sessao que fez a
            // alteracao e derruba as outras. Trocar a propria senha expulsa quem
            // estivesse logado com a senha antiga em outro dispositivo - e nao
            // expulsa quem acabou de trocar.
            sessoes.encerrarOutrasSessoesDe(usuario.getLogin(), SessaoAtual.idSessao());
        } else {
            // Um ADMIN alterando a conta de outra pessoa: nao ha sessao a
            // preservar aqui, porque as sessoes do alvo nao sao as de quem opera.
            sessoes.encerrarSessoesDe(usuario);
        }

        log.info("Sessoes de '{}' encerradas (perfil {} -> {}, senha trocada: {}).",
                usuario.getLogin(), perfilAnterior, usuario.getPerfil(), senhaTrocada);
    }

    /** Login de quem esta fazendo a requisicao, ou null se for anonima. */
    private static String loginLogado() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    /**
     * Impede desativar o ultimo ADMIN ativo PELO FORMULARIO.
     *
     * O DELETE ja tinha esta trava; a edicao nao tinha, e passou a precisar
     * dela quando o campo "ativo" entrou no PUT - antes o valor era ignorado e
     * nao havia como desativar por aqui. Sem esta checagem, o sistema poderia
     * ficar sem ninguem capaz de cadastrar funcionario ou excluir registro, e a
     * unica saida seria mexer no banco.
     */
    private void protegerUltimoAdminAtivo(Usuario usuario, boolean ativoAnterior, boolean seraDesativado) {
        boolean estaDesativando = ativoAnterior && seraDesativado;
        if (estaDesativando && usuario.getPerfil() == Perfil.ADMIN && ehUltimoAdminAtivo()) {
            throw new RegraNegocioException(
                    "Nao e possivel desativar: este e o ultimo administrador ativo do sistema.");
        }
    }

    /**
     * Impede que o sistema fique sem administrador: rebaixar o ultimo ADMIN
     * ativo para ATENDENTE travaria o cadastro de usuarios e as exclusoes.
     */
    private void protegerUltimoAdmin(Usuario usuario, Perfil novoPerfil) {
        boolean estaRebaixando = usuario.getPerfil() == Perfil.ADMIN && novoPerfil != Perfil.ADMIN;
        if (estaRebaixando && usuario.isAtivo() && ehUltimoAdminAtivo()) {
            throw new RegraNegocioException(
                    "Nao e possivel rebaixar: este e o ultimo administrador ativo do sistema.");
        }
    }

    private boolean ehUltimoAdminAtivo() {
        return usuarios.countByPerfilAndAtivoTrue(Perfil.ADMIN) <= 1;
    }
}
