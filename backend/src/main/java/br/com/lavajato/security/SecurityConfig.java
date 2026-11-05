package br.com.lavajato.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Sessoes simultaneas por usuario. Fica aqui, e nao solto em dois lugares,
     * porque o filterChain e o sessionAuthenticationStrategy precisam do MESMO
     * numero - e um deles nao le o outro.
     */
    private static final int MAXIMO_DE_SESSOES = 3;

    /**
     * Chave sob a qual o Spring Security guarda o contexto na sessao. Usada para
     * saber se a sessao pertence a alguem autenticado, sem depender do nome do
     * cookie de sessao.
     */
    private static final String SPRING_SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

    /** Metodos que alteram estado e por isso exigem token CSRF. */
    private static final Set<String> METODOS_COM_ESTADO = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Custo 12: mais caro de quebrar que o padrao 10, ainda aceitavel no login.
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Repositorio do contexto de seguranca. Precisa ser o MESMO bean usado pelo
     * filtro e pelo AuthController: se cada um tiver a sua instancia, o login
     * grava a sessao num lugar e o filtro procura em outro, e o usuario
     * continua anonimo na requisicao seguinte.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Repositorio do token CSRF. Bean porque o AuthController precisa da MESMA
     * instancia para rotacionar o token no login - se cada um criasse a sua, o
     * cookie gravado nao seria o que o filtro valida depois.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * Registro das sessoes abertas, por usuario. E o que permite ENCERRAR a
     * sessao de alguem quando o perfil dele muda ou quando ele e desativado.
     *
     * Ver GerenciadorDeSessoes para o problema completo. Em resumo: o Spring
     * Security guarda o SecurityContext na sessao HTTP e o le de volta a cada
     * requisicao, sem NUNCA reconsultar o banco. Entao desativar ou rebaixar um
     * funcionario nao alcanca a sessao que ele ja tem aberta - ela continua
     * valendo com as permissoes do momento do login. Confirmado na fonte
     * (HttpSessionSecurityContextRepository.loadContext) e medido em
     * SessaoETrocaDeSenhaTest.
     *
     * O registro e mantido em memoria, e isso e suficiente aqui: este sistema
     * roda numa instancia so. Se um dia houver duas instancias atras de um
     * balanceador, um registro em memoria por instancia deixaria as sessoes da
     * outra instancia fora do alcance - nesse dia ele precisaria ir para um
     * armazenamento compartilhado (Spring Session + JDBC/Redis).
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publica no SessionRegistry os eventos de criacao e destruicao de sessao
     * que o CONTAINER emite. E o que mantem o registro em sincronia com a
     * realidade quando a sessao morre por fora do Spring: expiracao por
     * inatividade, invalidacao pelo Tomcat, ou o container reiniciando.
     *
     * Sem ele, o registro acumularia sessoes que ja nao existem - e o erro
     * apareceria do lado oposto, tentando expirar sessao ja morta e mantendo
     * vivo um objeto por sessao ate o processo reiniciar.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * A estrategia de sessao COMPLETA - limite de sessoes, troca do ID no login
     * e registro de cada sessao nova. Existe como bean por um motivo
     * especifico: o login deste sistema e MANUAL (ver AuthController), e um
     * login manual nao passa pela cadeia de filtros onde o Spring chamaria
     * esta estrategia sozinho.
     *
     * O PROBLEMA QUE ISSO FECHOU (pos-Etapa 8)
     *
     * O AuthController chama authManager.authenticate() direto e grava o
     * SecurityContext a mao. Nenhuma SessionAuthenticationStrategy roda nesse
     * caminho - entao a RegisterSessionAuthenticationStrategy, que e quem
     * registra a sessao, NUNCA era chamada. O SessionRegistry ficava
     * permanentemente vazio, e o GerenciadorDeSessoes nao encontrava sessao
     * nenhuma para encerrar: a correcao rodava, logava "nenhuma sessao aberta"
     * e nao derrubava ninguem. E a mesma armadilha da Etapa 8 (um bean faltando
     * desligando o registro em silencio), agora pelo outro lado.
     *
     * POR QUE ESTA MONTADA A MAO, E NAO PEGA DO HttpSecurity
     *
     * getSharedObject(SessionAuthenticationStrategy.class) seria a fonte
     * "oficial" do composto, mas o HttpSecurity so tem esse objeto DEPOIS que
     * o filterChain e construido - e este bean entra como parametro do
     * proprio filterChain. O resultado, medido, foi um ciclo que so aparecia
     * em tempo de execucao: "No qualifying bean of type
     * SessionAuthenticationStrategy". Um ObjectProvider adia a falha, nao a
     * resolve, e ainda deixaria o erro para o primeiro login em vez de para a
     * subida.
     *
     * A montagem explicita tem uma vantagem que o getSharedObject nao teria:
     * o numero de sessoes aparece UMA vez, em MAXIMO_DE_SESSOES, e o
     * filterChain usa a mesma constante. Antes dela o "3" vivia solto no
     * filterChain e no javadoc, que e exatamente como um valor desses sai de
     * sincronia com a configuracao real.
     *
     * A ORDEM NAO E ARBITRARIA, e o CompositeSessionAuthenticationStrategy
     * executa na ordem da lista:
     *
     *   1. ConcurrentSessionControlAuthenticationStrategy - aplica o limite e
     *      expira a sessao mais antiga se estourar. Precisa vir ANTES da troca
     *      de ID porque ela raciocina sobre o ID da sessao da requisicao; depois
     *      da troca, o ID que ela veria nao e o que o cliente tem.
     *   2. SessionFixationProtectionStrategy - invalida a sessao que existia e
     *      cria outra. Isto SUBSTITUI a invalidacao manual que havia no
     *      AuthController: fazer as duas coisas invalidaria duas vezes e ainda
     *      deixaria o securityContextRepository gravando o contexto numa
     *      referencia de request ja trocada.
     *   3. RegisterSessionAuthenticationStrategy - registra a sessao NOVA no
     *      registro. Tem que ser a ultima, senao registraria o ID antigo e o
     *      registro apontaria para uma sessao ja invalidada.
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry) {
        ConcurrentSessionControlAuthenticationStrategy concorrencia =
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concorrencia.setMaximumSessions(SessionLimit.of(MAXIMO_DE_SESSOES));
        // false e o padrao e esta explicito para a decisao ficar visivel: o login
        // NOVO ganha e a sessao mais antiga cai. Com true, quem tentasse entrar no
        // 4o dispositivo levaria erro de login - e num balcao com computador,
        // tablet e celular o operador concluiria que "o sistema nao deixa entrar",
        // sem entender o motivo.
        concorrencia.setExceptionIfMaximumExceeded(false);

        return new CompositeSessionAuthenticationStrategy(List.of(
                concorrencia,
                new SessionFixationProtectionStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository,
                                           CsrfTokenRepository csrfTokenRepository,
                                           SessionRegistry sessionRegistry,
                                           @Value("${app.security.csp.politica}") String cspPolitica,
                                           @Value("${app.security.hsts.max-age-segundos}") long hstsMaxAge,
                                           @Value("${app.security.hsts.include-subdominios}") boolean hstsSubdominios)
            throws Exception {
        http
            // ---- Etapa 4: cabecalhos de seguranca ----
            .headers(headers -> headers
                // Cache-Control do Spring Security DESLIGADO, e isto NAO e um
                // afrouxamento - e uma correcao.
                //
                // Por padrao o Spring Security escreve
                //   Cache-Control: no-cache, no-store, max-age=0, must-revalidate
                // em TODA resposta que passa pela cadeia dele. Isso faz sentido
                // para resposta de API autenticada (nao quero um proxy guardando
                // dados de cliente), mas ele nao distingue: o /app.js e o
                // /styles.css tambem recebiam esse cabecalho.
                //
                // O custo era real e silencioso: o front tem ~43 KB de app.js e
                // ~21 KB de styles.css que eram rebaixados do servidor a cada
                // navegacao, e um F5 refazia o download inteiro. Nada quebrava,
                // so ficava lento.
                //
                // Este disable e a correcao COMPLETA, e isso foi medido, nao
                // deduzido: com ele, os assets saem sem Cache-Control nenhum e o
                // navegador revalida por ETag/Last-Modified (o corpo nao viaja de
                // novo); sem ele, o cabecalho acima volta na hora.
                //
                // O que NAO funciona, e fica registrado para ninguem repetir:
                // tentar apagar o cabecalho depois, num filtro. Quando o filtro
                // roda, o Security ja o escreveu, e setHeader(nome, null) nao
                // remove cabecalho no Tomcat 11. Um filtro que envolve a resposta
                // num wrapper consegue, mas e complexidade sem motivo - o lugar
                // certo de decidir isto e aqui, antes de nascer.
                .cacheControl(cache -> cache.disable())
                // CSP. O valor vem da configuracao, em vez de fixo aqui, por dois
                // motivos: o front evolui e a politica precisa acompanhar sem
                // recompilar, e o teste de fumaça (HeadersSegurancaTest) precisa
                // conferir que a linha configurada e mesmo a que sai no header.
                .contentSecurityPolicy(csp -> csp.policyDirectives(cspPolitica))

                // HSTS. So o HstsHeaderWriter padrao ja tem a protecao que
                // interessa: ele escreve o cabecalho apenas quando
                // request.isSecure() e true, ou seja, sob HTTPS. Em
                // http://localhost ele simplesmente nao emite nada - nao ha
                // risco de prender o navegador de desenvolvimento em HTTPS.
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(hstsMaxAge)
                    .includeSubDomains(hstsSubdominios)
                    // preload fica FORA de proposito. Entrar na lista de preload
                    // do navegador e praticamente irreversivel (meses para sair) e
                    // exige que TODOS os subdominios ja sejam HTTPS. Nao e uma
                    // decisao que se toma por padrao num sistema recem-implantado.
                    .preload(false))

                // X-Frame-Options: DENY. E o padrao do Spring, mas fica escrito
                // aqui para nao depender do padrao de uma versao futura.
                .frameOptions(frame -> frame.deny())

                // X-Content-Type-Options: nosniff. Sem ele o navegador pode
                // "adivinhar" o tipo de uma resposta e executar como script um
                // arquivo que deveria ser texto.
                .contentTypeOptions(opcoes -> { })

                // X-XSS-Protection: 0. Parece contraintuitivo desligar, mas e o
                // que o OWASP recomenda: o filtro XSS dos navegadores antigos
                // tinha falhas proprias e podia INTRODUZIR XSS. Quem protege
                // aqui e a CSP acima. Este e o padrao do Spring Security 7 e
                // esta explicito para deixar a decisao registrada.
                .xssProtection(xss -> xss.disable())

                // Politica de referencia: nao vaza a URL interna (que pode conter
                // ids) para sites de terceiros.
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))

                // O front nao usa camera, microfone, geolocalizacao nem nada do
                // genero. Negar por padrao custa nada e fecha um caminho caso um
                // script de terceiro entre na pagina algum dia.
                .permissionsPolicy(permissoes -> permissoes
                    .policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
            )
            // O filtro le/grava o contexto exatamente neste repositorio.
            .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository))
            .csrf(csrf -> csrf
                // O front le o cookie XSRF-TOKEN e devolve no header X-XSRF-TOKEN.
                // httpOnly=false de proposito: o JS PRECISA ler este cookie.
                .csrfTokenRepository(csrfTokenRepository)
                // Handler que aceita o token cru do header (sem XOR), como o front envia.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {{
                    setCsrfRequestAttributeName(null);
                }})
                // Isenta o login (ainda nao ha sessao nem cookie nesse momento).
                //
                // ignoringRequestMatchers ACRESCENTA isencoes a regra padrao do Spring,
                // que ja ignora GET/HEAD/TRACE/OPTIONS. Nao usar
                // requireCsrfProtectionMatcher aqui: ele SUBSTITUI a regra padrao, e
                // entao GET passaria a exigir token tambem (era o bug corrigido).
                //
                // Nao da para isentar tambem "requisicoes anonimas" por este caminho:
                // precisamos do matcher customizado para isso. Ver csrfProtegido().
                .ignoringRequestMatchers("/api/auth/login")
                .requireCsrfProtectionMatcher(csrfProtegido())
            )
            .sessionManagement(sessao -> sessao
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                // Troca o ID da sessao no login: impede session fixation.
                .sessionFixation(fixacao -> fixacao.changeSessionId())
                // No maximo MAXIMO_DE_SESSOES sessoes simultaneas por usuario.
                //
                // ATENCAO - DUAS CORRECOES AQUI (pos-Etapa 8)
                //
                // 1) Sem sessionRegistry(), o .maximumSessions() era DECORATIVO: o
                //    numero era aceito pela API e nao fazia nada, porque a
                //    ConcurrentSessionControlAuthenticationStrategy precisa de um
                //    registro para saber quais sessoes existem. O limite nunca
                //    esteve em vigor.
                //
                // 2) A RegisterSessionAuthenticationStrategy, que e quem REGISTRA
                //    cada sessao nova, tambem so e adicionada pelo Spring quando o
                //    registro existe. Sem ela, o registro ficaria vazio mesmo
                //    estando configurado.
                //
                // Ou seja: um unico bean faltando desligava, ao mesmo tempo, o
                // limite de sessoes e qualquer possibilidade de encerrar a sessao
                // de alguem.
                //
                // Aqui a configuracao DECLARA o que o filterChain faz com os
                // logins que ele mesmo conduz. O login manual (AuthController) usa
                // o bean sessionAuthenticationStrategy, montado com a mesma
                // constante e as mesmas estrategias - ver o javadoc dele.
                // O QUE ACONTECE QUANDO A SESSAO JA FOI ENCERRADA
                //
                // Encerrar a sessao e uma coisa; RESPONDER direito a requisicao
                // que chega depois e outra, e o padrao do Spring erra a segunda.
                //
                // Sem uma estrategia propria, quem responde e a
                // ResponseBodySessionInformationExpiredStrategy interna do
                // ConcurrentSessionFilter, cujo corpo inteiro e:
                //
                //     response.getWriter().print("This session has been expired
                //         (possibly due to multiple concurrent logins being
                //         attempted as the same user).");
                //     response.flushBuffer();
                //
                // Nao ha setStatus() nenhum. A resposta sai 200 OK com uma frase
                // em ingles no corpo - medido, nao deduzido: o GET depois do
                // expireNow voltava 200, enquanto a sessao ja estava invalidada.
                //
                // O front nao distingue isso de sucesso. Ele ve 200, tenta
                // desserializar o corpo como JSON e estoura no parse, num caminho
                // de erro que ninguem previu - e o operador fica numa tela que nao
                // diz que a sessao acabou nem oferece o login de novo.
                //
                // A resposta abaixo repete o contrato que o resto da API ja usa
                // em 401 (ver o catch de AuthenticationException no AuthController
                // e o tratamento de sessao expirada do front): JSON com "status" e
                // "mensagem", e o status HTTP 401 de verdade, que e o gatilho para
                // o front voltar para a tela de login.
                //
                // A ordem importa: expiredSessionStrategy() pertence ao
                // ConcurrencyControlConfigurer, que so existe DEPOIS de
                // maximumSessions(). Chama-la antes nao compila.
                .maximumSessions(MAXIMO_DE_SESSOES)
                    .maxSessionsPreventsLogin(false)
                    .sessionRegistry(sessionRegistry)
                    .expiredSessionStrategy(evento -> {
                        var resposta = evento.getResponse();
                        resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        resposta.setContentType("application/json");
                        resposta.setCharacterEncoding("UTF-8");
                        resposta.getWriter().write(
                                "{\"status\":401,\"mensagem\":\"Sua sessão foi encerrada. "
                                + "Entre novamente.\"}");
                    })
            )
            .authorizeHttpRequests(autorizacao -> autorizacao
                // ---- Etapa 6: superficies administrativas NEGADAS ----
                //
                // Estas rotas nao existem no sistema hoje: nao ha Actuator no
                // classpath, nem H2 console, nem a pagina de erro do Boot
                // exposta. O anyRequest().authenticated() do fim ja bastaria
                // para barra-las.
                //
                // A regra esta aqui, explicita e ANTES de qualquer permitAll,
                // por outro motivo: e uma trava contra o futuro. Se alguem
                // acrescentar spring-boot-starter-actuator para monitorar, ou
                // ligar o h2.console para depurar um problema, /actuator/env
                // (que mostra o valor de TODA propriedade, inclusive
                // DB_PASSWORD) e /h2-console (que da acesso SQL direto)
                // passariam a responder - e o anyRequest do fim nao os
                // distingue de uma rota legitima de usuario logado.
                //
                // Com a regra escrita, a adicao dessas dependencias leva 401 em
                // vez de expor. denied() e nao authenticated(): nem o ADMIN
                // precisa dessa superficie servida pelo proprio sistema.
                //
                // O /error NAO entra aqui de proposito: e o caminho que o
                // proprio Spring usa para renderizar erro, e bloquea-lo faria
                // um erro virar outro erro.
                .requestMatchers("/actuator/**", "/h2-console/**").denyAll()
                // Login e emissao do token CSRF sao publicos.
                .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                // Assets do front. Precisam ser publicos: o navegador pede o
                // app.js ANTES de existir login, e e o proprio app.js que
                // desenha o formulario. Fechando-os, a pagina abre em branco e
                // nao ha como autenticar (o campo de login nunca aparece).
                //
                // Os caminhos sao listados um a um de proposito: os arquivos
                // estao na raiz de static/, nao em /css e /js. Um "/**" aqui
                // abriria a API inteira se a ordem das regras mudasse.
                .requestMatchers("/", "/index.html", "/app.js", "/styles.css",
                        "/css/**", "/js/**", "/favicon.ico").permitAll()
                // Exclusao e gestao de usuarios: somente ADMIN.
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                .requestMatchers("/api/usuarios/**").hasRole("ADMIN")
                // Negar por padrao: todo o resto exige login.
                .anyRequest().authenticated()
            )
            .exceptionHandling(erros -> erros
                // Sem login: 401 em JSON, em vez de redirect para pagina de login.
                .authenticationEntryPoint((req, res, ex) -> {
                    // Log de nivel INFO e nao WARN: requisicao anonima a rota
                    // protegida e o caso NORMAL de quem ainda nao entrou (o front
                    // pede /api/clientes antes do login). Em WARN isso viraria
                    // ruido e esconderia o WARN que importa - a falha de login
                    // (que ja e logada no AuthController). A URI entra no log
                    // porque e o que permite ver uma varredura de rotas.
                    log.info("Acesso sem autenticacao em {} {}.",
                            req.getMethod(), req.getRequestURI());
                    escreverErro(res, HttpServletResponse.SC_UNAUTHORIZED, "Autenticação necessária.");
                })
                // Logado sem permissao: 403 em JSON.
                .accessDeniedHandler((req, res, ex) -> {
                    // Este sim e WARN: o usuario ESTA autenticado e tentou algo
                    // que o perfil dele nao permite. Pode ser engano de interface
                    // ou alguem testando os limites do que alcanca - nos dois
                    // casos, vale o registro. O login do usuario vai junto (nao a
                    // senha, obviamente): sem ele o log nao diz QUEM tentou.
                    log.warn("Acesso negado para '{}' em {} {}.",
                            req.getUserPrincipal() != null ? req.getUserPrincipal().getName() : "anonimo",
                            req.getMethod(), req.getRequestURI());
                    escreverErro(res, HttpServletResponse.SC_FORBIDDEN, "Acesso negado.");
                })
            )
            // Sem formulario e sem Basic: o login e o nosso JSON em /api/auth/login.
            .formLogin(formulario -> formulario.disable())
            .httpBasic(basico -> basico.disable())
            .logout(sair -> sair
                .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"))
                .logoutSuccessHandler((req, res, auth) ->
                    escreverErro(res, HttpServletResponse.SC_OK, "Sessão encerrada."))
                .deleteCookies("LAVAJATO_SESSION", "XSRF-TOKEN")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
            );

        return http.build();
    }

    /**
     * Quando o CSRF e exigido: apenas em /api/**, apenas em metodos que mudam
     * estado, e apenas quando ha sessao autenticada na requisicao.
     *
     * O padrao "/api/**" vem de PathPatternRequestMatcher, o mesmo mecanismo que
     * o Spring usa em requestMatchers() - em vez de startsWith() na URI crua, que
     * ignora context path e normalizacao de path.
     *
     * Duas razoes para o filtro por metodo:
     *  - GET/HEAD/OPTIONS/TRACE sao "safe methods": nao alteram nada no servidor,
     *    entao exigir token neles so quebra leitura legitima sem JS.
     *  - Sobrescrever o matcher SUBSTITUI a regra padrao do Spring, por isso a
     *    lista de metodos seguros precisa ser repetida aqui.
     *
     * Racao para o filtro de sessao: o CsrfFilter roda ANTES do filtro de
     * autenticacao anonima, entao getPrincipal() ainda e null aqui mesmo para
     * quem esta logado - nao da para distinguir pelo SecurityContext. Sem esta
     * checagem, um POST anonimo sem token receberia 403 "Acesso negado" em vez do
     * 401 que o front precisa ver para redirecionar ao login.
     *
     * A checagem e por PRESENCA DE SESSAO, nao por cookie LAVAJATO_SESSION: o
     * nome do cookie e configuravel, o container pode resolver a sessao por URL
     * rewriting (;jsessionid=), e um proxy pode repassa-la por outro meio. Decidir
     * por cookie deixaria essas rotas sem CSRF - foi o que estes testes pegaram.
     *
     * /api/auth/login fica de fora: nesse momento ainda nao existe sessao.
     */
    private RequestMatcher csrfProtegido() {
        RequestMatcher areaApi = PathPatternRequestMatcher.pathPattern("/api/**");
        RequestMatcher naoEhLogin = request -> !"/api/auth/login".equals(request.getRequestURI());
        RequestMatcher metodoComEstado = request -> METODOS_COM_ESTADO.contains(request.getMethod());

        return new AndRequestMatcher(areaApi, naoEhLogin, metodoComEstado, this::temSessaoAutenticada);
    }

    /**
     * Ha sessao de usuario nesta requisicao? A sessao pode ter sido criada
     * agora, nesta mesma requisicao (getSession(false) devolveria null), por
     * isso o contexto tambem e procurado na propria requisicao.
     */
    private boolean temSessaoAutenticada(HttpServletRequest request) {
        HttpSession sessao = request.getSession(false);
        if (sessao != null && sessao.getAttribute(SPRING_SECURITY_CONTEXT) != null) {
            return true;
        }
        return request.getAttribute(SPRING_SECURITY_CONTEXT) != null;
    }

    /**
     * Escreve a resposta de erro do Security no MESMO formato do resto da API:
     * {status, mensagem}. O front trata os dois do mesmo jeito.
     *
     * A mensagem e escapada antes de entrar no JSON. Hoje as duas que existem
     * sao constantes, mas isto aqui nao pode depender disso: uma mensagem que
     * venha a ter aspas ou barra inverteria a estrutura do corpo, e quem
     * consome receberia JSON invalido em vez de uma mensagem de erro.
     */
    private void escreverErro(HttpServletResponse res, int status, String mensagem) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"mensagem\":\"" + escaparJson(mensagem) + "\"}");
    }

    /** Escapa o minimo que quebra uma string JSON. */
    private String escaparJson(String texto) {
        return texto.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
