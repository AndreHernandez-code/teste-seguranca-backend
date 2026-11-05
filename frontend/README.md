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

Copia esta pasta para `backend/src/main/resources/static/` e sobe o backend.
Acesse **http://localhost:8080** — frontend e API na mesma origem, então
não há CORS envolvido.

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

Acesse **http://localhost:5500**. O `app.js` detecta a porta e aponta
automaticamente para `http://localhost:8080`.

## CORS

O backend já libera as portas mais comuns (5500, 5173, 8080) através de
`backend/src/main/java/br/com/lavajato/config/CorsConfig.java`.
Para ajustar, edite a propriedade em `application.properties`:

```properties
app.cors.allowed-origins=http://localhost:5500,http://localhost:5173
```

Se você servir o frontend por outra porta, adicione-a nessa lista —
caso contrário o navegador vai bloquear as requisições.

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
