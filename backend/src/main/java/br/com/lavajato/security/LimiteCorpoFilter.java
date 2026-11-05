package br.com.lavajato.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Recusa corpos de requisicao maiores que o limite, com 413.
 *
 * Por que um filtro e nao uma propriedade: o Tomcat tem
 * "server.tomcat.max-http-form-post-size", mas ele so vale para
 * application/x-www-form-urlencoded. A nossa API recebe JSON, que passa por
 * fora dessa checagem - confiar nela daria a impressao de protecao sem
 * proteger nada. Nao existe propriedade do Tomcat embarcado no Boot 4 que
 * limite um corpo JSON arbitrario; a checagem tem que ser da aplicacao.
 *
 * Duas barreiras, porque uma so nao basta:
 *
 *  1. Content-Length, quando o cliente o informa. Barra antes de ler nada.
 *
 *  2. Contagem durante a leitura, quando NAO informa (transfer-encoding
 *     chunked). Sem esta barreira, bastava mandar o corpo em chunks para o
 *     cabecalho nao existir e o limite ser ignorado por completo.
 *
 * A BARREIRA 2 SO FUNCIONA PORQUE A LEITURA ACONTECE AQUI DENTRO.
 * O contador nao pode apenas "lancar" no meio do DispatcherServlet: quando a
 * leitura estoura la dentro, o Spring MVC embrulha a falha em
 * HttpMessageNotReadableException, o GlobalExceptionHandler responde 400 e a
 * resposta JA FOI ENVIADA quando o controle volta para este filtro - nao ha
 * mais o que reescrever (response.isCommitted() == true).
 *
 * Por isso o corpo e lido ATE O LIMITE + 1 byte antes de entregar a requisicao
 * a cadeia. Se couber, a cadeia recebe uma requisicao com o corpo em memoria e
 * segue normal. Se nao couber, nada foi processado ainda e este filtro escreve
 * o 413 diretamente.
 *
 * CUSTO DE MEMORIA, medido e nao suposto: o corpo e mantido em memoria, e o
 * pico por requisicao NAO e o limite. Com o buffer dimensionado no proprio
 * teto (ver lerLimitado), o pico fica em ~2x o limite: um array do tamanho do
 * teto mais a copia devolvida por ele. Com os 256 KB configurados, algo em
 * torno de 512 KB por requisicao em voo. E um teto FECHADO - o cliente nao
 * consegue fazer alocar mais do que isso -, mas nao e "o limite e o teto de
 * memoria", como se poderia supor.
 *
 * O limite e defesa em profundidade, nao a validacao principal: os @Size dos
 * DTOs ja barram texto grande campo a campo e devolvem 400 com a mensagem
 * certa. Aqui o objetivo e cortar o corpo ABSURDO antes de ele ser lido e
 * desserializado, gastando memoria e CPU a toa.
 *
 * ORDEM, e o que ela custa: roda em HIGHEST_PRECEDENCE + 10, ou seja ANTES
 * do Spring Security (que fica em -100). Isso e deliberado - este filtro
 * precisa ser o PRIMEIRO a ler o corpo, senao o enquadramento que ele combate
 * ja teria sido consumido -, mas tem um preco que vale registrar com precisao:
 *
 *   Um cliente ANONIMO, sem credencial nenhuma, faz este filtro ler o corpo
 *   antes de levar o 401. O teto de 256 KB limita a MEMORIA por requisicao,
 *   mas nao limita o TEMPO: basta anunciar Content-Length de 262143 e mandar
 *   os bytes devagar. O filtro fica bloqueado no read() esperando, segurando
 *   uma thread do Tomcat ANTES de a autenticacao rodar. Com as 200 threads
 *   padrao, sao ate 200 conexoes anonimas ocupando thread de uma vez, e
 *   renovaveis. Nao e exaustao de memoria - e ocupacao de thread por quem nem
 *   se autenticou, e essa e a parte que importa.
 *
 *   Hoje quem limita isso e o server.tomcat.connection-timeout, fixado
 *   explicitamente na configuracao para nao depender do padrao implicito.
 *   Se o sistema passar a ficar exposto na internet, o lugar certo para tratar
 *   ataque de temporizacao e a camada de frente (proxy), com um timeout de
 *   leitura do corpo - nao a aplicacao.
 *
 *   POR QUE NAO REORDENAR (colocar o filtro depois da seguranca): resolveria a
 *   ocupacao anonima, mas (a) um corpo gigante para endpoint protegido
 *   passaria a devolver 401 em vez de 413, mudando o contrato que o front ve,
 *   e (b) a correcao passaria a depender de onde o Boot encaixa a cadeia de
 *   seguranca em relacao ao DispatcherServlet - acoplamento que quebra em
 *   silencio numa atualizacao de versao. A posicao atual nao tem esse
 *   acoplamento, entao a ordem fica como esta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LimiteCorpoFilter extends OncePerRequestFilter {

    private final long limiteBytes;

    public LimiteCorpoFilter(@Value("${app.security.corpo-max-bytes:262144}") long limiteBytes) {
        this.limiteBytes = limiteBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Barreira 1: o cliente informou o tamanho e ele ja passa do limite.
        // Rejeitamos sem ler um byte do corpo.
        long informado = request.getContentLengthLong();
        if (informado > limiteBytes) {
            recusar(response);
            return;
        }

        // Sem corpo nao ha o que medir. A decisao e por ENQUADRAMENTO
        // (Content-Length / Transfer-Encoding), nunca pelo metodo HTTP.
        //
        // Antes isto checava o metodo ("so POST/PUT/PATCH tem corpo") e o furo
        // era real: um DELETE com corpo em chunked passava direto pelo filtro,
        // sem contagem nenhuma. Hoje nenhum handler le corpo em DELETE/GET,
        // entao nao havia exploracao - mas a protecao se desligaria sozinha no
        // dia em que alguem acrescentasse @RequestBody a um deles, sem aviso.
        // Enquadramento e o que o HTTP realmente define como "tem corpo".
        if (informado == 0 && !temEnquadramentoComCorpo(request)) {
            chain.doFilter(request, response);
            return;
        }

        // Barreira 2: le o corpo ate o limite. O +1 e o que distingue
        // "coube exatamente" de "passou": um corpo de exatamente limiteBytes
        // e legitimo, e o byte extra seria o que sobra.
        byte[] corpo;
        try {
            corpo = lerLimitado(request, limiteBytes + 1);
        } catch (IOException e) {
            // Falha de transporte no meio da leitura (cliente abortou, timeout).
            //
            // NAO repassamos a requisicao original para a cadeia: o stream dela
            // ja foi consumido e FECHADO pelo try-with-resources do
            // lerLimitado, mas o getContentLength() continua anunciando o
            // tamanho cheio. O @RequestBody leria um stream esgotado, o Spring
            // responderia um 400 "corpo invalido" enganoso - o erro real foi de
            // rede, nao de conteudo - e a divergencia entre o Content-Length
            // anunciado e o que ainda da para ler pode travar o converter.
            //
            // O certo e dizer o que aconteceu: nao deu para ler o corpo.
            throw new ServletException("Falha ao ler o corpo da requisição.", e);
        }

        if (corpo.length > limiteBytes) {
            recusar(response);
            return;
        }

        // Coube: a cadeia le exatamente os bytes ja lidos, sem tocar no socket
        // de novo. Envolver (em vez de consumir o stream original) e necessario
        // porque o corpo ja foi lido e o stream original nao rebobina.
        chain.doFilter(new RequisicaoComCorpo(request, corpo), response);
    }

    /**
     * O cliente declarou um corpo por enquadramento HTTP?
     *
     * Nao olhamos o metodo de proposito - ver o comentario no ponto de chamada.
     * Duas formas de um corpo se anunciar:
     *
     *  - Content-Length > 0 (o caso comum);
     *  - Transfer-Encoding: chunked, que e justamente o caso SEM
     *    Content-Length e portanto o que mais precisa ser contado.
     *
     * Em HTTP/2 nao existe Transfer-Encoding e o corpo e delimitado pelo
     * proprio protocolo, com getContentLengthLong() podendo devolver -1. Este
     * servidor nao fala h2 hoje (nao ha server.http2.enabled nem TLS), entao
     * nao ha esse caminho; se um dia houver, esta checagem precisa ser
     * revista, porque um corpo h2 sem Content-Length cairia aqui como
     * "sem corpo" e escaparia do limite.
     */
    private boolean temEnquadramentoComCorpo(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) {
            return true;
        }
        String transferEncoding = request.getHeader("Transfer-Encoding");
        return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
    }

    /**
     * Le no maximo {@code maximo} bytes do corpo.
     *
     * O buffer ja nasce no tamanho maximo, em vez de crescer sozinho. Duas
     * razoes, as duas de memoria:
     *
     *  - ByteArrayOutputStream cresce DOBRANDO. Um corpo de 262 KB chegaria a
     *    ter um array de 512 KB por tras. Com o tamanho fixado no teto, isso
     *    nao acontece.
     *  - O crescimento momentaneo mantem o array velho e o novo vivos ao mesmo
     *    tempo, dobrando o pico justamente no pior instante.
     *
     * Assim o pico por requisicao fica em ~2x o limite (o buffer no teto, mais
     * a copia que o toByteArray devolve) em vez do que a doubling daria.
     *
     * O corte em maximo + um bloco de leitura e o que garante que um corpo
     * gigante NAO e copiado inteiro: paramos na primeira leitura que passa do
     * teto.
     */
    private byte[] lerLimitado(HttpServletRequest request, long maximo) throws IOException {
        try (ServletInputStream entrada = request.getInputStream()) {
            java.io.ByteArrayOutputStream buffer =
                    new java.io.ByteArrayOutputStream((int) Math.min(maximo, Integer.MAX_VALUE - 8));
            byte[] bloco = new byte[8192];
            int lidos;
            while ((lidos = entrada.read(bloco, 0, bloco.length)) != -1) {
                buffer.write(bloco, 0, lidos);
                if (buffer.size() > maximo) {
                    break;
                }
            }
            return buffer.toByteArray();
        }
    }

    private void recusar(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":413,\"mensagem\":\""
                + "O conteúdo enviado é grande demais (máximo de "
                + (limiteBytes / 1024) + " KB).\"}");
    }

    /**
     * Devolve a cadeia o corpo ja lido, em vez do stream original.
     *
     * O corpo foi consumido do socket por este filtro; sem este wrapper o
     * controller leria um stream vazio e veria "JSON vazio" em toda requisicao
     * com corpo.
     */
    private static class RequisicaoComCorpo extends HttpServletRequestWrapper {

        private final byte[] corpo;

        RequisicaoComCorpo(HttpServletRequest request, byte[] corpo) {
            super(request);
            this.corpo = corpo;
        }

        @Override
        public int getContentLength() {
            return corpo.length;
        }

        @Override
        public long getContentLengthLong() {
            return corpo.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new InputStreamDeBytes(corpo);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** ServletInputStream sobre um array de bytes ja lido. */
    private static class InputStreamDeBytes extends ServletInputStream {

        private final byte[] dados;
        private int posicao;

        InputStreamDeBytes(byte[] dados) {
            this.dados = dados;
        }

        @Override
        public int read() {
            return posicao >= dados.length ? -1 : dados[posicao++] & 0xFF;
        }

        @Override
        public int read(byte[] destino, int deslocamento, int tamanho) {
            if (posicao >= dados.length) {
                return -1;
            }
            int disponivel = Math.min(tamanho, dados.length - posicao);
            System.arraycopy(dados, posicao, destino, deslocamento, disponivel);
            posicao += disponivel;
            return disponivel;
        }

        @Override
        public int available() {
            return dados.length - posicao;
        }

        @Override
        public boolean isFinished() {
            return posicao >= dados.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // O contrato do ServletInputStream diz que isReady()/setReadListener
            // so fazem sentido no modo assincrono, e que um stream que recusa o
            // listener nao deveria se anunciar como pronto - por isso o
            // isReady() acima e sempre true: o corpo JA esta inteiro em
            // memoria, entao a leitura e sempre "pronta".
            //
            // Nao ha caminho assincrono neste app (nenhum controller async,
            // DeferredResult ou SSE), e o Spring MVC le @RequestBody de forma
            // sincrona. Se algum dia houver, isto vira um erro alto e claro em
            // vez de um retorno silenciosamente errado.
            throw new UnsupportedOperationException(
                    "LimiteCorpoFilter le o corpo de forma sincrona; leitura assincrona nao e suportada.");
        }
    }
}
