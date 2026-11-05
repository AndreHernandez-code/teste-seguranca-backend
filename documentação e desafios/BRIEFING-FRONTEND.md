# Briefing de Front-end — Sistema de Lava-Jato

Você é responsável por criar a **interface visual** deste sistema. Este documento descreve **o que o sistema faz** — todas as funções, telas, campos e regras. Você tem liberdade total de design: cores, tipografia, layout, componentes, animações.

O backend já existe e é uma **API REST em JSON**. O front é uma página estática (HTML + CSS + JavaScript puro, sem framework) servida pelo mesmo servidor. Você não precisa criar backend — apenas consumir os endpoints descritos aqui.

---

## 1. O que é o sistema

Um painel de gestão para um lava-jato. Funcionários fazem login e gerenciam quatro tipos de cadastro:

| Cadastro | O que é |
|---|---|
| **Clientes** | Donos dos carros |
| **Veículos** | Carros, cada um ligado a um cliente |
| **Serviços** | Tipos de lavagem, com preço e duração |
| **Agendamentos** | Marcação de um serviço para um veículo em data/hora |

Hierarquia: **Cliente → Veículos → Agendamento ← Serviço**

---

## 2. Autenticação

### Tela de login
Dois campos: **Usuário** (texto) e **Senha** (password). Botão **Entrar**.

- Não existe "cadastro" nem "recuperar senha" — usuários são criados por um ADMIN dentro do sistema.
- Mensagem de erro genérica: `Usuário ou senha inválidos.`
- **Muito importante:** qualquer falha no login (usuário não existe, senha errada, bloqueio) mostra a **mesma** mensagem genérica. Não sugira ao usuário "verifique se o usuário existe".

### Perfis de acesso
Dois perfis, e eles mudam o que a tela mostra:

| Perfil | O que pode fazer |
|---|---|
| `ADMIN` | Tudo. Além disso: **excluir** registros, gerenciar usuários |
| `ATENDENTE` | Criar e editar tudo. **Não vê o botão Excluir.** Não acessa usuários |

**Regra de design:** quem não é ADMIN **não deve ver** o botão de excluir. Mas atenção — esconder o botão é só estética. O servidor bloqueia com 403 de qualquer forma. Não trate isso como proteção.

### Sessão
- Expira por inatividade: **30 minutos** no cliente, 2 horas no servidor.
- Ao expirar, volta para o login com a mensagem `Sua sessão expirou. Entre novamente.`
- Existem **3 sessões simultâneas** por usuário. A quarta derruba a mais antiga.
- Botão **Sair** na barra superior.

---

## 3. Estrutura da tela principal

Barra superior com:
- Logo/nome do sistema
- **Indicador de conexão** (3 estados): `Carregando…` / `Conectado` / `Sem conexão` ou `Carga parcial`
- Nome do usuário logado + perfil
- Botão **Sair**
- Botão de alternar **tema claro/escuro**

Abaixo, **4 abas**: Agendamentos, Clientes, Veículos, Serviços.

O sistema abre na aba **Agendamentos**. A aba ativa fica refletida na URL (`#clientes`, `#veiculos`…) — quem abrir o link cai direto na aba.

---

## 4. Aba AGENDAMENTOS (a principal)

### Indicadores no topo (3 números)
| Indicador | Cálculo |
|---|---|
| **Total** | Todos os agendamentos |
| **Aguardando** | Só os com status `AGUARDANDO` |
| **Faturamento previsto** | Soma do valor de todos **exceto** os `CANCELADO` |

Formato de dinheiro: `R$ 1.234,56` (pt-BR).

### Formulário de novo agendamento
| Campo | Tipo | Obrigatório | Observação |
|---|---|---|---|
| Veículo | select | Sim | Lista os veículos como `ABC1D23 — Modelo (Nome do Cliente)` |
| Serviço | select | Sim | Lista como `Lavagem Simples — R$ 49,90 · 30 min` |
| Data | date | Sim | **Já vem preenchido com a data de hoje** |
| Hora | time | Sim | |
| Status | select | Não | Vem `AGUARDANDO` |

Botão **Agendar**. Ao criar: mensagem de sucesso, limpa o formulário, **mantém a data** preenchida e recarrega a lista.

> O preço é calculado pelo servidor a partir do serviço escolhido. O front **não** envia preço.

### Lista de agendamentos
Colunas: **Data/Hora · Cliente · Veículo · Serviço · Valor · Status · Ações**

- Data em `05/11/2026` com a hora como linha secundária (`09:30`)
- Cliente, Veículo (placa + modelo) e Serviço (nome + duração) vêm do registro ligado
- Valor: o `precoCobrado` se existir, senão o preço do serviço
- **Ordenação: mais recentes primeiro**
- Vazio: `Nenhum agendamento cadastrado ainda.`

### Status — as 4 cores e a máquina de estados
| Status | Rótulo |
|---|---|
| `AGUARDANDO` | Aguardando |
| `EM_ANDAMENTO` | Em andamento |
| `CONCLUIDO` | Concluído |
| `CANCELADO` | Cancelado |

**Cada status tem uma cor própria** (badge/pílula). Sugestão de semântica: aguardando = neutro/amarelo, em andamento = azul, concluído = verde, cancelado = vermelho/cinza.

**Fluxo com botão de avanço rápido:** na coluna de ações existe um botão que avança o status sem abrir formulário:
- `AGUARDANDO` → botão **"Iniciar"** → vira `EM_ANDAMENTO`
- `EM_ANDAMENTO` → botão **"Concluir"** → vira `CONCLUIDO`
- `CONCLUIDO` e `CANCELADO` → **sem botão** (fim da linha)

### Filtros
Dois selects acima da lista:
- **Período:** `Hoje` (próximas 24h) / `Próximos 7 dias`
- **Status:** filtra por um dos quatro

Vazio com filtro ativo: `Nenhum agendamento com esse filtro.`

---

## 5. Aba CLIENTES

### Formulário
| Campo | Tipo | Obrigatório |
|---|---|---|
| Nome | texto | Sim |
| Telefone | tel | Sim |

### Lista
Colunas: **# (id) · Nome · Telefone · Veículos · Ações**

A coluna **Veículos** é uma **contagem** — quantos veículos aquele cliente tem. Não é uma lista.

Vazio: `Nenhum cliente cadastrado ainda.`

---

## 6. Aba VEÍCULOS

### Formulário
| Campo | Tipo | Obrigatório | Observação |
|---|---|---|---|
| Cliente | select | Sim | O dono do veículo |
| Placa | texto | Sim | **Força maiúsculas automaticamente.** Máx. 8 caracteres |
| Modelo | texto | Sim | |

**Formato de placa aceito pelo servidor:** `ABC1D23` (padrão Mercosul) ou `ABC1234` (padrão antigo). Regex: `^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$`

O front limpa a placa tirando tudo que não for letra ou número, e converte para maiúsculas.

### Lista
Colunas: **# · Placa · Modelo · Cliente · Ações**

Placa em fonte monoespaçada (facilita leitura).

Vazio: `Nenhum veículo cadastrado ainda.`

---

## 7. Aba SERVIÇOS

### Formulário
| Campo | Tipo | Obrigatório | Regra |
|---|---|---|---|
| Nome | texto | Sim | |
| Preço (R$) | number | Sim | ≥ 0, passo 0.01. Ex.: `49.90` |
| Duração (min) | number | Sim | **Inteiro > 0**, passo 5 |

### Lista
Colunas: **# · Nome · Preço · Duração · Ações**

Preço formatado `R$ 49,90`, duração como `30 min`.

Vazio: `Nenhum serviço cadastrado ainda.`

---

## 8. Ações em cada linha (todas as abas)

Toda linha tem botões de ação:

- **Editar** — abre um **diálogo modal** com os campos daquele registro (mesmos campos do formulário)
- **Excluir** — abre um diálogo de confirmação. **Só aparece para ADMIN**

### Diálogo de exclusão
Texto: `Tem certeza que deseja excluir {descrição}? Esta ação não pode ser desfeita.`
Botões: **Cancelar** / **Excluir** (destrutivo). O foco inicial fica no botão de excluir.

**Conflito de exclusão:** se o registro tiver outros ligados a ele (ex.: excluir um cliente que tem veículos), o servidor recusa e devolve a mensagem de conflito. **Mostre essa mensagem** — ela explica o motivo.

### Diálogo de edição
- Título muda conforme o recurso: `Editar cliente`, `Editar veículo`, `Editar serviço`, `Editar agendamento`
- **Editar agendamento** tem também o campo **Status** (os 4 valores)
- Se o registro atual aponta para algo que não existe mais na lista (ex.: veículo cujo cliente foi removido), o select avisa: `⚠ registro atual não encontrado — selecione outro`

---

## 9. Feedback ao usuário

O sistema comunica o resultado de cada ação. Você escolhe a forma visual.

### Mensagens temporárias ("toasts")
Aparecem no canto, **fecham sozinhas em 5 segundos**, têm botão de fechar.
Três tipos: **sucesso** / **erro** / **informação**.

Exemplos de texto:
- `Bem-vindo, admin.`
- `Agendamento criado com sucesso.`
- `Cliente "João" cadastrado.`
- `Veículo ABC1D23 cadastrado.`
- `Serviço "Lavagem Simples" cadastrado.`
- `Alterações salvas.`
- `Registro excluído.`
- `Agendamento: Em andamento.`

### Mensagens fixas sob o formulário
Ficam visíveis até a próxima ação. Usadas para erro de validação:
- `Preencha os campos obrigatórios (*).`
- `Informe um preço válido (ex.: 49.90).`
- `Informe a duração em minutos (número inteiro).`
- `Selecione um veículo válido.`
- `Cliente inválido. Recarregue a página.`

### Estado do botão durante o envio
Ao enviar, o botão fica **desabilitado com indicador de carregamento** até a resposta chegar. Evita clique duplo.

### Marcação de campo inválido
Campos com erro recebem destaque visual (o front usa `aria-invalid`).

---

## 10. Tabela de erros do servidor

O backend responde com `{ status, mensagem, campos? }`. **Mostre sempre a `mensagem` que vier.** Se não vier, use estas genéricas:

| HTTP | Mensagem padrão |
|---|---|
| 400 | `Dados inválidos. Confira os campos.` |
| 401 | `Sessão expirada. Entre novamente.` |
| 403 | `Você não tem permissão para esta ação.` |
| 404 | `Registro não encontrado.` |
| 409 | `Não é possível concluir: há registros ligados a este.` |
| 429 | `Muitas tentativas de login. Tente novamente em alguns minutos.` |
| 500+ | `O servidor teve um problema. Tente novamente.` |

**Dois cuidados:**
1. **401** significa sessão expirada → mande o usuário para a tela de login. **Exceção:** 401 na própria tela de login é senha errada, não expiração — não redirecione.
2. **429** é **bloqueio por tentativas de login** (5 por usuário, 20 por IP, em 15 min). Mostre a mensagem; ela já diz para esperar.

---

## 11. Detalhes de comportamento

- **Logout encerra no servidor primeiro.** Não basta limpar a tela.
- **Ao voltar para a aba do navegador** (trocar de aba e voltar), os dados são recarregados.
- **Qualquer clique ou tecla** reinicia o contador de 30 min de inatividade.
- **Navegação por teclado nas abas:** setas ←/→, Home, End (padrão ARIA de tablist).
- **Formatação de dinheiro:** `Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })`
- **Formatação de data:** `05/11/2026` e `09:30`
- **Tema claro/escuro**: a preferência é salva e lembrada.

---

## 12. API — endpoints disponíveis

Base: mesma origem (URL relativa). Todas as respostas em JSON.

### Autenticação
| Método | Rota | O que faz |
|---|---|---|
| POST | `/api/auth/login` | Body `{login, senha}`. **Sem CSRF.** |
| POST | `/api/auth/logout` | Encerra a sessão |
| GET | `/api/auth/me` | `{login, perfis}` do usuário logado |
| GET | `/api/auth/csrf` | Força a emissão do cookie CSRF |

### Cadastros (CRUD completo)
```
/api/clientes     /api/veiculos     /api/servicos     /api/agendamentos
GET    /api/clientes         → lista
POST   /api/clientes         → cria
PUT    /api/clientes/{id}    → edita
DELETE /api/clientes/{id}    → exclui (só ADMIN)
```

### CSRF — leia com atenção
O backend exige um token CSRF em **POST, PUT, PATCH e DELETE**.

- O token vem no cookie `XSRF-TOKEN` (legível por JS)
- Deve ser enviado no header **`X-XSRF-TOKEN`**
- O cookie é emitido por `GET /api/auth/csrf`

Código de referência:
```js
function lerCookie(nome) {
  for (const parte of document.cookie.split('; ')) {
    const i = parte.indexOf('=');
    if (i > 0 && parte.slice(0, i) === nome) return decodeURIComponent(parte.slice(i + 1));
  }
  return null;
}

const token = lerCookie('XSRF-TOKEN');
const headers = {};
if (body) headers['Content-Type'] = 'application/json';
if (token && method !== 'GET' && method !== 'HEAD') headers['X-XSRF-TOKEN'] = token;

fetch(path, {
  method,
  headers,
  body: body ? JSON.stringify(body) : undefined,
  credentials: 'same-origin',   // ← OBRIGATÓRIO, senão a sessão não vai
});
```

### Formatos JSON

**Cliente**
```json
{ "id": 1, "nome": "João Silva", "telefone": "(11) 91234-5678" }
```

**Serviço**
```json
{ "id": 1, "nome": "Lavagem Simples", "preco": 49.90, "tempoEstimado": 30 }
```

**Veículo** (cliente aninhado)
```json
{
  "id": 1,
  "placa": "ABC1D23",
  "modelo": "Fiat Argo",
  "cliente": { "id": 1, "nome": "João Silva", "telefone": "(11) 91234-5678" }
}
```

**Agendamento** (veículo e serviço aninhados)
```json
{
  "id": 1,
  "dataHora": "2026-11-05T09:30:00",
  "status": "AGUARDANDO",
  "precoCobrado": 49.90,
  "veiculo": { "id": 1, "placa": "ABC1D23", "modelo": "Fiat Argo",
               "cliente": { "id": 1, "nome": "João Silva" } },
  "servico": { "id": 1, "nome": "Lavagem Simples", "preco": 49.90, "tempoEstimado": 30 }
}
```

**Ao criar/editar, envie apenas os IDs das relações:**
```json
// POST /api/veiculos
{ "placa": "ABC1D23", "modelo": "Fiat Argo", "cliente": { "id": 1 } }

// POST /api/agendamentos  (preço NÃO é enviado — o servidor calcula)
{ "dataHora": "2026-11-05T09:30:00", "status": "AGUARDANDO",
  "veiculo": { "id": 1 }, "servico": { "id": 1 } }
```

`dataHora` é **sem timezone**: `YYYY-MM-DDTHH:mm:ss`.

### Erro
```json
{ "status": 400, "mensagem": "Informe o nome do cliente." }
```
Com detalhes por campo:
```json
{ "status": 400, "mensagem": "Dados inválidos.",
  "campos": { "nome": "Informe o nome do cliente." } }
```

---

## 13. O que você deve entregar

1. **HTML + CSS + JS** funcionando contra esta API (sem framework; JS puro)
2. **Todas as funções** descritas aqui — nenhuma pode faltar
3. **Design livre**: você decide cores, tipografia, espaçamento, componentes, ícones, animações
4. **Responsivo** — deve funcionar em desktop e tablet. O sistema é usado numa recepção de lava-jato
5. **Acessível** — contraste adequado, foco visível, navegação por teclado, labels nos campos
6. **Tema claro e escuro**

### Armadilha de CSS que você precisa conhecer
O HTML usa o atributo `hidden` para mostrar/esconder telas. **O atributo `hidden` só funciona se nenhuma regra de classe declarar `display` naquele elemento** — porque `[hidden] { display: none }` é uma regra fraca do navegador, e qualquer `.minha-classe { display: grid }` a vence.

Isso já causou um bug real neste projeto: a tela de login não sumia depois de autenticar, porque `.login { display: grid }` anulava o `hidden`.

**Solução:** inclua no topo do seu CSS
```css
[hidden] { display: none !important; }
```

---

## 14. O que NÃO fazer

- Não criar tela de cadastro de usuário público
- Não mostrar mensagens diferentes para "usuário não existe" vs "senha errada"
- Não confiar no front para segurança — o servidor valida tudo de novo
- Não usar `<form>` sem `novalidate` se for controlar a validação em JS
- Não enviar objetos aninhados completos nas criações — apenas `{ id }`
