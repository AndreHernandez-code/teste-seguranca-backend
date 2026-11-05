# Frontend — Lava-Jato

Painel para gerenciar clientes, veículos, serviços e agendamentos, consumindo a API
Spring Boot que está em `../backend`.

## Arquivos

| Arquivo      | Conteúdo                                   |
|--------------|--------------------------------------------|
| `index.html` | Estrutura e textos                         |
| `styles.css` | Estilos (tema claro/escuro, responsivo)     |
| `app.js`     | Chamadas à API e comportamento da interface |

Sem build, sem dependências: é só HTML, CSS e JS puros.

## Como rodar

### Opção 1 — pelo próprio Spring Boot (recomendado)

Copia esta pasta para `backend/src/main/resources/static/` e sobe o backend
(ou use o `INICIAR.bat` na raiz do projeto, que já faz isso).
Acesse **http://localhost:8080** — frontend e API na mesma origem.

> ℹ️ As duas cópias (`frontend/` e `backend/src/main/resources/static/`) devem
> ficar idênticas. Ao editar o frontend, sincronize as duas.

### Opção 2 — com um servidor estático

Suba o backend (porta 8080) e sirva esta pasta em outra porta.

> ⚠️ Abrir o `index.html` com duplo clique (`file://`) **não funciona**:
> o navegador bloqueia as chamadas à API nesse modo.

Com Python:

```bash
cd frontend
python -m http.server 5500
```

Com VS Code, use a extensão **Live Server** (porta 5500).

Acesse **http://localhost:5500** e aponte o `app.js` para a API definindo
`window.API_BASE` **antes** de carregar o `app.js`:

```html
<script>window.API_BASE = 'http://localhost:8080';</script>
<script src="app.js"></script>
```

Sem isso, o `app.js` assume a mesma origem (`''`) e as requisições irão
para a porta do servidor estático — que não tem a API.

## CORS

**O backend NÃO habilita CORS.** Não existe `CorsConfig.java` nem a
propriedade `app.cors.allowed-origins`; o Spring Security também não abre
CORS. Isso é intencional: o app é *same-origin* (o frontend é servido pelo
próprio backend na porta 8080).

**Consequência prática:** se você servir o frontend em **outra origem/porta**
(Opção 2), o navegador vai **bloquear** as chamadas à API por CORS e o
`window.API_BASE` sozinho **não resolve** — o backend precisa permitir a origem.

Para usar a Opção 2 de verdade, é preciso adicionar configuração de CORS no
backend (não existe hoje). Enquanto isso, prefira a **Opção 1**.

## O que a interface faz

- **Agendamentos** — lista com filtro por status, mais um resumo no topo
  (total, quantos aguardando e faturamento previsto).
- **Clientes / Veículos / Serviços** — formulário de cadastro e tabela.
- **Editar e excluir** — cada linha tem os botões *Editar* e *Excluir*.
  Editar abre um diálogo com os campos preenchidos; excluir pede confirmação.
- Os selects de veículo/serviço/cliente são preenchidos com os dados já
  cadastrados.
- Os dados são recarregados ao voltar para a aba do navegador.

### Regra de exclusão

Um registro que tem outros dependendo dele **não pode** ser excluído — o
backend responde `409` e a interface explica o motivo. Vale para:

| Registro | Não pode excluir se…            |
|----------|----------------------------------|
| Cliente  | tiver veículos cadastrados       |
| Veículo  | tiver agendamentos               |
| Serviço  | tiver agendamentos               |

Exclua primeiro os registros dependentes.

## API

Todos os recursos têm CRUD completo:

| Método   | Rota                  |
|----------|-----------------------|
| `GET`    | `/api/<recurso>`      |
| `GET`    | `/api/<recurso>/{id}` |
| `POST`   | `/api/<recurso>`      |
| `PUT`    | `/api/<recurso>/{id}` |
| `DELETE` | `/api/<recurso>/{id}` |

Recursos: `clientes`, `veiculos`, `servicos`, `agendamentos`.

Os campos `dataHora` de agendamento são enviados como `"2026-11-05T09:30:00"`
(sem fuso), no formato que o `LocalDateTime` do Spring espera.
