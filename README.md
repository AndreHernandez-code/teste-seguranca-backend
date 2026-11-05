# Lava-Jato

> **Este projeto é um estudo de segurança, não um produto.**
>
> O lava-jato é o **pretexto**, não o objetivo. Ele existe porque uma aplicação
> de verdade — com login, perfis, dados de terceiros e dinheiro envolvido —
> dá o que proteger. Um sistema de exemplo com duas telas não dá.
>
> O que eu queria aprender foi **defender uma aplicação web**: autenticação,
> sessão, autorização, CSRF, XSS, injeção, força bruta, vazamento de
> informação por mensagem de erro, gestão de segredos. O lava-jato é só o
> assunto escolhido para isso ter consequência.
>
> Por isso o código tem mais comentário que o normal, e quase todo comentário
> explica **um risco** — o que aconteceria se aquela linha não estivesse ali.
> Cada proteção tem um teste que prova que ela funciona, e vários testes que
> provam que ela **não** funciona quando deveria bloquear.
>
> Se você veio procurar um sistema de lava-jato pronto para usar: as funções
> estão todas lá e funcionam, mas não é esse o ponto do repositório.
> Se veio ver como se protege uma aplicação: é exatamente isso.

---

Sistema de gestão para lava-jato: clientes, veículos, serviços e agendamentos,
com controle de acesso por perfil.

Aplicação full-stack em um único processo — o backend Spring Boot serve a API
REST e a interface. Não há build de front nem servidor separado.

Aqui não tem nada de especial: são as funções que um lava-jato precisa, e todas
funcionam. É o palco. Se você quer ver o que eu realmente fui atrás neste
projeto, vá direto para **[Segurança](#segurança)**.

---

## O que o sistema faz

| Recurso | Descrição |
|---|---|
| **Agendamentos** | Marcação de um serviço para um veículo em data/hora, com status (aguardando, em andamento, concluído, cancelado) e faturamento previsto |
| **Clientes** | Cadastro com nome e telefone |
| **Veículos** | Placa (validada), modelo e o cliente dono |
| **Serviços** | Catálogo com preço e duração estimada |
| **Funcionários** | Gestão de acessos: criar, editar, desativar. Exclusivo de administradores |

Hierarquia dos dados: **Cliente → Veículos → Agendamento ← Serviço**

### Perfis de acesso

| Perfil | O que pode fazer |
|---|---|
| `ADMIN` | Tudo. Além disso: **excluir** registros e gerenciar funcionários |
| `ATENDENTE` | Criar e editar tudo. Não vê o botão Excluir nem a aba Funcionários |

---

## Como rodar

### Opção 1 — o atalho (Windows)

Dê dois cliques em **`INICIAR.bat`**. Ele confere o que falta, cria um banco
novo e vazio, sobe o sistema e abre o navegador.

Na primeira vez demora alguns minutos (baixa as dependências do Maven). Depois
sobe em segundos.

### Opção 2 — manual

Precisa de **Java 25** e **PostgreSQL** rodando.

```bash
cd backend

# sem as variáveis, o app sobe com os padrões de desenvolvimento
# (postgres/admin em localhost:5432/lavajato) e SEM criar nenhum admin
./mvnw spring-boot:run
```

Para definir credenciais e criar o administrador inicial:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/lavajato
export DB_USER=postgres
export DB_PASSWORD=<sua-senha>
export ADMIN_LOGIN=admin
export ADMIN_SENHA=<senha-com-12-ou-mais-caracteres>

./mvnw spring-boot:run
```

Abra **http://localhost:8080**.

> As tabelas são criadas pelo Flyway na primeira subida. O **banco** em si
> precisa existir — ou use `INICIAR.bat`, que o cria para você.

### Testes

```bash
cd backend && ./mvnw test
```

126 testes, sem dependência de banco externo (usam H2 em memória).

---

## Tecnologias

### Backend

| | |
|---|---|
| Linguagem | Java 25 |
| Framework | Spring Boot 4.1.2 |
| Segurança | Spring Security 7.1.2 |
| Persistência | Spring Data JPA / Hibernate |
| Banco | PostgreSQL 18 |
| Migrações | Flyway |
| Build | Maven 3.10 (wrapper `mvnw` — não precisa instalar) |
| Testes | JUnit 5 + MockMvc + H2 |

### Frontend

| | |
|---|---|
| Linguagem | JavaScript puro (ES2022+), sem framework |
| Marcação / estilo | HTML5 + CSS3 (variáveis, `dialog`, grid) |
| Dependências | **Nenhuma.** Sem React, sem jQuery, sem CDN |
| Build | **Nenhum.** Os arquivos são servidos como estão |

---

## Segurança

**Esta é a parte que importa neste repositório.** O resto é o palco.

Detalhamento das decisões — com os porquês e as trocas — em
[`documentação e desafios/SEGURANCA.md`](documentação%20e%20desafios/SEGURANCA.md).

**Autenticação** — BCrypt com custo 12. Login contra a tabela `usuario`. Sem
senha fixa no código: o admin inicial vem de `ADMIN_LOGIN`/`ADMIN_SENHA`.
Política de senha de 12+ caracteres, com recusa das senhas padronizadas mais
comuns.

**Força bruta** — limite por usuário (5 tentativas) e por IP (20), em janela
deslizante de 15 minutos. `X-Forwarded-For` é ignorado por padrão: sem um proxy
confiável na frente, confiar nele deixaria o próprio cliente escolher o IP e
escapar do limite.

**Sessão** — cookie `HttpOnly` + `SameSite=Lax`, expiração em 2 horas, no máximo
3 sessões simultâneas. Desativar alguém ou trocar sua senha **derruba as sessões
abertas** — sem isso, um funcionário demitido seguiria operando até a sessão
expirar.

**CSRF** — token por cookie, exigido em todo método que altera dados.

**Autorização** — `DELETE` em qualquer rota e todo `/api/usuarios` exigem
`ADMIN`. O sistema **impede ficar sem administrador**: não dá para desativar,
rebaixar nem apagar o último ADMIN ativo (três travas independentes).

**Cabeçalhos** — CSP sem `unsafe-inline` e sem `unsafe-eval` (o front não tem
script inline, então a política é real e não decorativa), `X-Frame-Options:
DENY`, `nosniff`, `Referrer-Policy`, `Permissions-Policy` e HSTS.

**Erros** — nunca devolvem stack trace nem mensagem interna; só
`{status, mensagem, campos?}`.

**Banco** — consultas parametrizadas via JPA, migrações versionadas com Flyway,
`ddl-auto=validate` (o Hibernate só confere o schema, nunca o altera sozinho) e
desenho de privilégio mínimo em `db/usuario-minimo-privilegio.sql`.

**Limites** — corpo de requisição de 256 KB, cortado antes de desserializar, e
timeout de conexão de 30s.

### Frontend

Sem `innerHTML` com dado do usuário — todo texto entra por `textContent`, o que
fecha XSS por construção e não por sanitização. Sem `eval`, sem script inline,
sem nenhuma dependência de CDN. `credentials: 'same-origin'` em todas as
chamadas.

A aba *Funcionários* some para `ATENDENTE`, mas isso é conveniência: o
gating de verdade está no servidor. Forçar a aba pelo DevTools não dá acesso.

### O que este projeto não prova

Um estudo de segurança que só lista acertos não ensina nada. O que ele **não**
cobre:

**Ninguém tentou invadir.** As proteções foram escritas com cuidado e testadas
uma a uma — mas "testei que funciona" e "alguém tentou quebrar e não conseguiu"
são afirmações diferentes. A segunda é a que vale. É por isso que o
[desafio de segurança](documentação%20e%20desafios/desafio-seguranca/) existe:
para virar a primeira na segunda.

**Não há análise automatizada.** Sem SAST, sem teste de dependência vulnerável,
sem fuzzing. O que foi feito é revisão manual e teste de comportamento.

**O difícil não está aqui.** Este sistema não tem upload de arquivo, não tem
integração com terceiros, não tem pagamento, não tem recuperação de senha por
e-mail, não roda em múltiplas máquinas. Cada uma dessas coisas traz uma família
inteira de problemas que **não aparecem** neste código — e é nelas que os
sistemas reais costumam cair.

**Autenticação é a fácil.** Login com usuário e senha é o caso simples. Sem
MFA, sem OAuth, sem SSO, sem "entrar com Google" — todos eles com armadilhas
próprias que aqui não foram enfrentadas.

**Os itens de infraestrutura não são código.** HTTPS, firewall e backup estão
listados no fim deste README. Sem eles, nenhuma linha do que está aqui protege
nada: uma sessão que viaja em HTTP claro é uma sessão que qualquer um na rede
lê, não importa quão bom seja o BCrypt.

### O que valeu a pena aprender

Três coisas que só ficaram claras fazendo:

**Segurança não se prova lendo o código.** Várias vezes o código parecia certo
e não estava. O limite de sessões simultâneas, por exemplo, estava configurado
e **nunca era consultado** — porque o login deste projeto é manual, e o Spring
só aplica aquela configuração quando ele mesmo conduz o login. Configuração
correta e sem efeito é pior que configuração ausente: parece resolvido.

**O furo costuma estar na regra de negócio, não na infraestrutura.** Cookie,
CSRF e permissão de rota são bem servidos pelas bibliotecas — difícil errar.
O erro estava no `precoCobrado`: a entidade aceitava o preço vindo do cliente,
e o serviço só recalculava quando o *serviço* mudava. Um `PUT` mantendo o mesmo
serviço gravava o preço que o atacante quisesse. Nenhuma biblioteca protege
contra isso: só entender o próprio domínio.

**Teste que passa não é teste que testa.** Um teste verde que nunca viu a
proteção falhar não prova nada. Os testes que valem são os que tentam o ataque
e **esperam a recusa** — e os que quebram quando alguém afrouxa a regra.

---

## Estrutura

```
backend/                           servidor (Spring Boot)
  src/main/java/br/com/lavajato/
    controller/                    endpoints da API
    dto/                           entrada e saída, com validação
    model/                         entidades
    repository/                    acesso a dados
    security/                      autenticação, sessão, cabeçalhos
    service/                       regras de negócio
    validation/                    grupos de validação
  src/main/resources/
    application.properties         configuração (segredos por variável de ambiente)
    application-prod.properties    perfil de produção
    db/migration/                  migrações Flyway
    static/                        cópia servida do frontend
  src/test/java/                   126 testes

frontend/                          a interface (fonte)
documentação e desafios/           documentação
  SEGURANCA.md                     as decisões de segurança, uma a uma
  desafio-seguranca/               material do exercício de invasão

INICIAR.bat                        atalho para subir tudo (Windows)
```

> **O front é copiado.** O servidor serve `backend/src/main/resources/static/`,
> que é uma cópia de `frontend/`. Edite `frontend/` e sincronize:
>
> ```bash
> cp frontend/index.html frontend/app.js frontend/styles.css \
>    backend/src/main/resources/static/
> ```

---

## Configuração

Todas as chaves têm padrão de desenvolvimento; **nenhum segredo é versionado**.

| Variável | Padrão | Descrição |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/lavajato` | Conexão |
| `DB_USER` | `postgres` | Usuário |
| `DB_PASSWORD` | `admin` | Senha |
| `ADMIN_LOGIN` | *(vazio)* | Login do admin inicial — sem ele, nenhum admin é criado |
| `ADMIN_SENHA` | *(vazio)* | Senha do admin inicial (mínimo 12 caracteres) |
| `COOKIE_SECURE` | `false` | `true` exige HTTPS |

### Produção

O perfil `prod` **não tem valor padrão para credencial nenhuma**: se faltar uma
variável, a aplicação não sobe. Um deploy que para é melhor que um deploy no ar
com credencial padrão.

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=... DB_USER=... DB_PASSWORD=...
export DB_MIGRATION_USER=... DB_MIGRATION_PASSWORD=...
export ADMIN_LOGIN=... ADMIN_SENHA=...

java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Três itens **não são código** e ficam para quem for colocar no ar:

1. **HTTPS com certificado válido** — obrigatório. O perfil `prod` liga o cookie
   `Secure`, e sem HTTPS a sessão não funciona no navegador.
2. **Firewall** — o PostgreSQL não deve ficar exposto na rede.
3. **Backup testado** — backup que nunca foi restaurado não é backup.

---

## Desafio de segurança

O repositório inclui material para um exercício de revisão de segurança:
[`documentação e desafios/desafio-seguranca/`](documentação%20e%20desafios/desafio-seguranca/).

O `INICIAR.bat` cria um banco **novo e vazio** para isso, com credenciais
descartáveis — nada de dado real. O guia lista o que faz sentido tentar atacar
e por quê.

---

## Licença

Sem licença definida.
