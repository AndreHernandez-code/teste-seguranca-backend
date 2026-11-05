# O que testar

Este sistema foi construído com várias proteções. **Sua missão é ver se alguma
delas falha.**

Não existe resposta certa aqui. O valor do teste está em **quebrar algo que
deveria estar protegido** — não em adivinhar senha.

---

## Regra do jogo

1. Você tem a senha do `admin`. **Isso não é trapaça, é o ponto de partida:**
   você é um funcionário legítimo tentando fazer o que não deveria.
2. Marque tudo que conseguir. **Vale anotar como você fez** — um furo que
   ninguém consegue reproduzir não serve para consertar.
3. Se algo parecer suspeito mas você não tiver certeza, escreva assim mesmo.
   Meia-descoberta ainda é uma pista.
4. Se não achar nada, anote o que tentou. "Tentei X e Y e nenhum passou" também
   é resultado.

**Você pode quebrar o banco.** Ele é descartável. Não quebra o computador.

---

## O terreno

O sistema tem uma API. A tela é só uma forma bonita de chamá-la. **A maior
parte dos furos aparece quando você chama a API direto**, sem passar pela tela.

Endereço base: `http://localhost:8080`

As rotas:

```
/api/auth/login      entrar
/api/auth/me         quem sou eu
/api/auth/csrf       pegar o token anti-CSRF

/api/clientes        GET / POST / PUT / DELETE
/api/veiculos        GET / POST / PUT / DELETE
/api/servicos        GET / POST / PUT / DELETE
/api/agendamentos    GET / POST / PUT / DELETE
/api/usuarios        GET / POST / PUT / DELETE  (só ADMIN)
```

### Como chamar a API na mão

**Opção 1 — o navegador.** Aperte `F12`, vá em **Console** e use:

```js
await fetch('/api/usuarios').then(r => r.status)
```

**Opção 2 — um programa.** Baixe o [Postman](https://www.postman.com/downloads/)
ou use o `curl` (já vem no Windows 10+).

**IMPORTANTE:** as chamadas que **mudam** algo (POST, PUT, DELETE) exigem um
token anti-CSRF. Sem ele, o sistema recusa. O próprio sistema te diz como
pegar — veja a seção "CSRF" mais abaixo.

---

## 10 coisas para tentar

### A. Login e sessão

**1. Força bruta**
Tente adivinhar a senha do `admin` errando muitas vezes seguidas, rápido.
O sistema tem um limite. **Quantas tentativas ele aguenta antes de te barrar?
O que acontece quando ele barra? Dá para escapar esperando? E se você trocar
de usuário — o limite é por conta ou por máquina?**

**2. A sessão morre mesmo?**
Entre como `admin`. Em outra aba, desative essa conta. **A aba antiga ainda
funciona?** Dá para continuar operando com uma sessão que deveria ter morrido?

**3. Cookie na mão**
Depois de entrar, aperte `F12` → **Application** → **Cookies**.
Olhe o `LAVAJATO_SESSION`. **Ele é assinado? Você consegue forjar um?** Tente
trocar o valor por outro e ver se o sistema aceita.

**4. Sair é sair mesmo?**
Faça logout. **A sessão antiga morreu, ou só sumiu da tela?**
Guarde o cookie antes do logout e teste de novo depois.

### B. Permissões

**5. Atendente tentando ser chefe**
Crie um funcionário com perfil **Atendente** (aba *Funcionários*).
Entre com ele e tente:
- Listar os funcionários (`GET /api/usuarios`)
- Criar outro usuário
- **Apagar qualquer coisa** (`DELETE` em qualquer rota)

**Deve dar 403. Dá?**

**6. Atendente virando ADMIN sozinho**
Entre como Atendente. Tente **mudar o próprio perfil** para ADMIN — pelo `PUT`
na API, direto, sem passar pela tela. **Você consegue se promover?**

**7. O último admin**
Tente desativar, rebaixar ou apagar o último ADMIN ativo.
O sistema deve recusar. **Consegue dar a volta nisso?** E se você criar um
segundo admin, desativar o primeiro e depois apagar o segundo?

### C. Dados

**8. IDs dos outros**
O sistema usa `/api/clientes/1`, `/api/clientes/2`...
**Você consegue ler o cliente 3 se só deveria ver o 1?** Funcionário deveria
ver todos — mas veja se existe algum recurso onde o número do ID importa.
Tente IDs negativos, `0`, `999999999999999999`, texto no lugar do número.

**9. Validação**
Mande dados absurdos e veja se entram:
- Um cliente com **nome vazio** ou de 10.000 caracteres
- Um **preço negativo** em serviço (`"preco": -500`)
- Uma **data no passado** num agendamento
- Caracteres esquisitos: `'; DROP TABLE cliente; --`

**10. Injeção de SQL**
O último item acima é a tentativa clássica. Tente variações nos campos de
texto. **O sistema usa consultas parametrizadas — mas tente provar que não.**

### D. Extras

**11. Mensagens de erro**
Provoque erros e **leia o que o sistema responde**.
Ele conta demais? Devolve *stack trace*, nome de tabela, caminho de arquivo?

**12. O que a tela esconde**
O arquivo `frontend/app.js` esconde a aba *Funcionários* para não-admin.
**Esconder não é proteger.** Tente forçar a aba a aparecer pelo `F12`
(remova o atributo `hidden`) e usar a tela. **O servidor obedece ou recusa?**

---

## As proteções que existem (para você saber o que derrubar)

Isto é o que o sistema **tenta** fazer. Não leia como "é seguro" — leia como
"é isto que você precisa furar":

| Proteção | O que ela promete |
|---|---|
| Senha com BCrypt | Senha não é guardada legível nem reversível |
| Limite de tentativas | Errar demais bloqueia, por conta e por máquina |
| CSRF | Não dá para outra página agir em seu nome |
| Cookie `HttpOnly` + `SameSite` | JavaScript não lê o cookie; outra origem não o envia |
| Sessão que morre | Desativar/trocar senha derruba as sessões abertas |
| Permissões por perfil | `DELETE` e `/api/usuarios` exigem ADMIN |
| Validação nos DTOs | Campo fora das regras é recusado |
| Erro sem detalhe | Não devolve *stack trace* nem mensagem interna |
| Consultas parametrizadas | Injeção de SQL não cola |
| CSP | Script injetado não executa |
| Limite de tamanho do corpo | Corpo gigante é cortado antes de processar |
| Perfil prod | Em produção, cookie só viaja por HTTPS |

**Onde eu apostaria que tem furo:** nas regras de negócio, não na
infraestrutura. Proteção genérica (cookie, CSRF, permissão de rota) costuma
estar certa. **Regra de negócio específica** — quem pode ver o quê, quando um
número pode ser negativo, o que acontece numa sequência estranha de operações —
é onde quase sempre sobra buraco.

---

## CSRF: como passar

Toda chamada que **muda** algo precisa do token. Pegue assim:

```js
// 1) Pegue o token (o cookie XSRF-TOKEN é legível pelo JS de propósito)
const t = document.cookie.split('; ')
  .find(c => c.startsWith('XSRF-TOKEN=')).split('=')[1];

// 2) Use no cabeçalho
await fetch('/api/usuarios', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': t },
  body: JSON.stringify({ login:'teste', senha:'SenhaLonga123', nome:'Teste', perfil:'ADMIN' })
});
```

Pelo `curl`, o caminho é o mesmo: pegue o cookie `XSRF-TOKEN` e mande no
cabeçalho `X-XSRF-TOKEN`.

---

## O que **não** faz parte deste teste

Coisas que são problema do **servidor de verdade**, não do código — não perca
tempo tentando:

- **HTTPS / certificado** — aqui roda em HTTP de propósito, na sua máquina
- **Firewall** — o banco está aberto localmente, e é assim mesmo
- **Backup** — não existe, e não é para existir num teste
- **Derrubar o serviço** (DoS) — não é o objetivo
- **Ataques à sua própria máquina** — limite-se ao sistema

---

## Como entregar o resultado

Escreva, para cada coisa que achou:

```
O QUE:    acesso os dados do cliente 2 sem permissão
COMO:     GET /api/clientes/2 com a sessão de atendente → 200 (esperado 403)
IMPACTO:  qualquer funcionário lê a base inteira de clientes
```

O **COMO** é a parte que importa. Sem ele, não dá para consertar nem confirmar.

**Boa caçada.**
