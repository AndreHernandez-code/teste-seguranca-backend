# Segurança

Detalhamento das decisões. O resumo está no [README](../README.md).

Cada item aqui tem uma razão — não é uma lista de tecnologia aplicada por
hábito. Onde a decisão tem um custo ou uma troca, ele está escrito.

---

## Autenticação

**BCrypt com custo 12.** A senha nunca é guardada legível nem é reversível.
Custo 12 é o dobro do trabalho do padrão 10; o login fica alguns milissegundos
mais lento e o ataque de dicionário, muito mais.

**Login contra o banco** (`UsuarioDetailsService`), não em memória. Um usuário
desativado não entra, porque o `UserDetails` sai do banco com
`.disabled(!usuario.isAtivo())`.

**Sem senha fixa no código.** O admin inicial vem de `ADMIN_LOGIN` e
`ADMIN_SENHA`. Sem essas variáveis, nenhum admin é criado e o log avisa. Se o
login já existir, o seeder não faz nada — subir a aplicação de novo não
sobrescreve a senha de ninguém.

**Política de senha** (`AdminInicialSeeder`): mínimo de 12 caracteres, recusa
senha igual ao login e recusa uma lista de senhas padronizadas (`admin`,
`admin123`, `password`, `123456789012`, `lavajato123` e outras). A checagem é
por igualdade exata, não por "contém": `admin12345678` contém `admin` e ainda
assim não é uma senha ruim de verdade — o que importa é ela não **ser** a senha
inteira.

> Em produção, senha fora da política **impede a subida**. Recusar apenas o
> cadastro deixaria um sistema no ar, acessível e sem ninguém capaz de
> administrá-lo — e o problema só apareceria quando alguém tentasse entrar.

---

## Força bruta

Limite por **usuário** (5 tentativas) e por **IP** (20), em janela deslizante de
15 minutos. Os números são diferentes porque os ataques são diferentes: poucas
tentativas bastam para quebrar uma conta específica, mas um escritório inteiro
atrás do mesmo NAT precisa de folga.

O contador do IP é limpo ao acertar a senha; o do usuário, não — senão o ataque
poderia limpar o próprio bloqueio acertando a senha de outra conta que ele
controla.

**`X-Forwarded-For` é ignorado por padrão.** Sem um proxy confiável na frente
que **sobrescreva** o cabeçalho, confiar nele deixaria o próprio cliente
escolher o IP e escapar do limite. Ligue só atrás de um proxy que o reescreva.

---

## Sessão

| Item | Valor |
|---|---|
| Cookie | `LAVAJATO_SESSION`, `HttpOnly`, `SameSite=Lax`, `Path=/` |
| Expiração | 2 horas |
| Sessões simultâneas | 3 por usuário |
| `Secure` | `true` no perfil `prod` (só trafega por HTTPS) |

**Desativar alguém derruba as sessões abertas dele.** O Spring Security lê o
contexto gravado na sessão e não reconsulta o banco a cada requisição. Sem isso,
um funcionário demitido continuaria operando até a sessão expirar — e o
computador do balcão é justamente onde a sessão fica aberta o dia inteiro.

**Mudança de perfil e troca de senha também encerram.** O mesmo motivo: a sessão
antiga tem a `authority` do perfil anterior gravada dentro dela. Rebaixar um
ADMIN e não encerrar deixaria ele excluindo registros como ADMIN.

Sessões só caem quando muda **poderes ou credencial** — não em toda edição.
Encerrar a cada edição deslogaria alguém por causa de uma correção de nome: um
efeito que ninguém ligaria à causa.

Quem faz a troca e é o próprio dono da conta **preserva a própria sessão** e
derruba as outras. Trocar a própria senha expulsa quem estava logado com a senha
antiga em outro dispositivo, sem derrubar quem acabou de trocar.

---

## CSRF

Token por cookie (`XSRF-TOKEN`), exigido em POST, PUT, PATCH e DELETE. Só
métodos seguros (GET, HEAD, OPTIONS) são isentos.

O cookie é `HttpOnly = false` de propósito: o front precisa lê-lo para colocar o
valor no cabeçalho `X-XSRF-TOKEN`. Isso não abre CSRF — o token continua
imprevisível, e o atacante que consegue ler o cookie já teria execução na página
(ou seja, já teria vencido por outro caminho).

---

## Autorização

Perfis `ADMIN` e `ATENDENTE`.

```
DELETE /api/**        → exige ADMIN
/api/usuarios/**      → exige ADMIN
qualquer outra rota   → exige estar autenticado
```

`/actuator/**` e `/h2-console/**` são `denyAll()` — nem o ADMIN acessa. Não há
motivo para o console do H2 existir em produção, e o `denyAll` fecha a família
inteira mesmo que alguém adicione a dependência depois.

**Não dá para o sistema ficar sem administrador.** Três travas independentes
recusam com 409: desativar o último ADMIN ativo, rebaixá-lo para ATENDENTE, e
apagá-lo. Sem elas, a única saída seria mexer no banco direto.

A tela também esconde a aba *Funcionários* de quem não é ADMIN — mas isso é
conveniência, não segurança: o gating de verdade é o 403 do servidor.

---

## Cabeçalhos HTTP

| Cabeçalho | Valor |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` |
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `same-origin` |
| `Permissions-Policy` | câmera, microfone, geolocalização, pagamento e USB negados |
| `Strict-Transport-Security` | `max-age` de 180 dias, `includeSubDomains` |

**A CSP não tem `unsafe-inline` nem `unsafe-eval`.** Isso só é possível porque o
front tem um único script externo, nenhum `<script>` inline, nenhum `on*=`. Um
XSS que consiga injetar HTML não consegue executar script. Os dois `unsafe-*` são
justamente o que torna uma CSP decorativa.

`form-action 'self'` e `base-uri 'self'` fecham dois desvios: um formulário
injetado não posta dados para fora, e uma injeção de `<base>` não redireciona
todos os caminhos relativos da página.

**HSTS com 180 dias, e não o ano cheio do padrão** — é um número que dá para
recuar sem prender os usuários por 12 meses caso apareça um problema de TLS.
O cabeçalho só é emitido sob HTTPS, então em desenvolvimento não atrapalha.

> `includeSubDomains=true` só deve ficar ligado se **todos** os subdomínios já
> tiverem HTTPS. Um subdomínio esquecido em HTTP fica inacessível para quem já
> recebeu o cabeçalho.

---

## Erros e logs

Formato de erro: `{status, mensagem, campos?}`. O front depende deste contrato.

**Nunca devolve stack trace nem mensagem de exceção ao cliente.** Quem investiga
lê o **log**, não a resposta HTTP. As chaves `include-stacktrace`,
`include-message` e `include-binding-errors` estão fixadas em `never` mesmo já
sendo o padrão do Boot — é a garantia que precisa sobreviver a uma atualização
de versão ou a alguém que "sobe o nível de log para investigar".

Mensagens de validação são genéricas por campo e não revelam existência de
registro: um login que não existe e um login com senha errada dão a **mesma**
resposta.

**Senha, token e cookie nunca aparecem em log.** O seeder registra que criou o
admin, sem a senha. SQL (`SHOW_SQL`) fica desligado por padrão justamente para
não vazar consulta e dado.

---

## Banco de dados

**Consultas parametrizadas** via JPA. Não há concatenação de SQL em lugar
nenhum.

**Flyway** versiona o schema. Migração já aplicada nunca é editada — o Flyway
guarda um checksum e a edição quebra a subida. `clean-disabled=true` impede que
um comando apague o banco por engano.

**`ddl-auto=validate`.** O Hibernate apenas **confere** que o banco bate com as
entidades, e recusa subir se não bater. Com `update`, ele adicionaria coluna que
falta mas nunca removeria nem alteraria tipo — o banco iria divergindo em
silêncio do que as entidades dizem, e a diferença só apareceria quando algo
quebrasse.

**Privilégio mínimo**, documentado em `db/usuario-minimo-privilegio.sql`: o
usuário de execução tem só CRUD, sem poder criar ou apagar tabela. O Flyway usa
uma credencial separada, que precisa de DDL. Migrar para duas credenciais é uma
decisão de instalação — sem elas, o Flyway cai na credencial do datasource e
nada quebra.

---

## Limites de recurso

**Corpo de requisição: 256 KB**, cortado **antes** da desserialização. Os
`@Size` dos DTOs barram texto grande campo a campo; isto aqui corta o corpo
absurdo antes de existir como objeto.

**Timeout de conexão: 30s.** O limite de corpo sozinho não limita *tempo*: o
filtro roda antes do Spring Security, então um cliente anônimo pode anunciar um
`Content-Length` grande e mandar os bytes devagar, segurando uma thread do
Tomcat sem estar autenticado. O teto de 256 KB limita a memória; o timeout
limita o tempo. Ele está fixado no `application.properties` de propósito, em vez
de herdar o padrão do Boot — um limite que ninguém escolheu não é um limite.

**Log com rotação**: 10 MB por arquivo, 30 arquivos, teto total de 500 MB. Sem o
teto total, 30 arquivos de 10 MB ainda dariam 300 MB.

---

## Validação

Validação por campo nos DTOs, com grupos `Criacao` e `Edicao` — a senha é
obrigatória ao criar e opcional ao editar (vazio significa "manter a atual").

| Campo | Regra |
|---|---|
| Nome | Não vazio, até 120 caracteres |
| Telefone | Não vazio, até 20 caracteres |
| Placa | Padrão Mercosul e antigo (`^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$`) |
| Preço | Não negativo |
| Duração | Maior que zero, até 1440 min (24 h) |
| Login | 3 a 60 caracteres, normalizado para minúsculas |
| Senha | 12 a 100 caracteres |

A validação de entrada é a primeira linha, mas **não é a única**: as regras que
dependem do estado do banco (último admin, existência de referência) são
checadas no serviço, dentro da transação.

---

## Frontend

**Sem `innerHTML` com dado do usuário.** Todo texto entra por `textContent`, o
que fecha XSS **por construção**: não há o que escapar, porque o navegador nunca
interpreta o dado como HTML. Um sanitizador pode ter furo; não usar a API
insegura, não.

**Sem `eval`, sem `new Function`, sem script inline** — e por isso a CSP pode
ser estrita.

**Nenhuma dependência externa.** Nada carregado de CDN. Não há cadeia de
fornecimento de terceiros para comprometer.

**`credentials: 'same-origin'`** em todas as chamadas — o cookie de sessão não
viaja para outra origem.

**Token CSRF** lido do cookie e enviado em `X-XSRF-TOKEN` nas chamadas que
alteram dados.

---

## Verificação

126 testes automatizados, cobrindo as oito etapas de segurança do
desenvolvimento: autenticação, força bruta, limite de corpo, cabeçalhos,
erros e logs, banco endurecido, coerência de ambiente e sessões.

O JSON da API é travado por **teste de contrato** — o front depende dele, e o
teste existe para que uma mudança no backend não quebre a tela em silêncio.

```bash
cd backend && ./mvnw test
```
