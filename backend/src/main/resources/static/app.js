/* ============================================================
   Lava-Jato - Painel de agendamentos
   Consome a API do backend Spring Boot.
   ============================================================ */

'use strict';

/* ---------- Configuracao ---------- */

// O front e servido pelo PROPRIO Spring Boot, entao as chamadas vao sempre para
// a mesma origem. Nao ha URL de backend no codigo: isso evita ter endereco
// interno escrito no JS, e o navegador cuida do cookie de sessao sozinho.
// Para apontar para outro lugar, defina window.API_BASE antes de carregar app.js.
const API_BASE = window.API_BASE ?? '';

const ENDPOINTS = {
  agendamentos: '/api/agendamentos',
  clientes:     '/api/clientes',
  veiculos:     '/api/veiculos',
  servicos:     '/api/servicos',
  // Fora de CRUD_GENERICO: so ADMIN acessa, usa POST/PUT/DELETE proprios e a
  // exclusao aqui e DESATIVACAO (preserva historico), nao remocao.
  funcionarios: '/api/usuarios',
};

const AUTH = {
  login:  '/api/auth/login',
  logout: '/api/auth/logout',
  me:     '/api/auth/me',
};

/** Nome do cookie de sessao. Usado so para saber se ha sessao, nunca lido. */
const COOKIE_SESSAO = 'LAVAJATO_SESSION';
const COOKIE_CSRF = 'XSRF-TOKEN';

/** Inatividade tolerada antes de derrubar a sessao no cliente (30 min).
 *  O servidor expira em 2h; isto e uma rede de seguranca para estacao
 *  deixada aberta, nao a fonte da verdade. */
const LIMITE_INATIVIDADE_MS = 30 * 60 * 1000;

const STATUS_LABEL = {
  AGUARDANDO:   'Aguardando',
  EM_ANDAMENTO: 'Em andamento',
  CONCLUIDO:    'Concluído',
  CANCELADO:    'Cancelado',
};

const PERFIL_LABEL = {
  ADMIN:     'Administrador',
  ATENDENTE: 'Atendente',
};

/** Recursos que seguem o CRUD generico (carregar, editar e excluir iguais).
 *  `funcionarios` fica de fora: exige ser ADMIN e a exclusao la e desativacao,
 *  com confirmacao e mensagens proprias. */
const CRUD_GENERICO = ['agendamentos', 'clientes', 'veiculos', 'servicos'];

/* ---------- Estado ---------- */

const state = {
  agendamentos: [],
  clientes:     [],
  veiculos:     [],
  servicos:     [],
  funcionarios: [],
  filtroStatus: '',
  filtroPeriodo: '',
  usuario:      null,   // { login, perfis: ['ADMIN'|'ATENDENTE'] }
};

/* ---------- Helpers ---------- */

const $ = (sel) => document.querySelector(sel);

const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
};

const money = new Intl.NumberFormat('pt-BR', {
  style: 'currency',
  currency: 'BRL',
});

/** Formata "2026-11-05T09:30:00" para "05/11/2026" e "09:30". */
function parseDataHora(valor) {
  if (!valor) return { data: '—', hora: '—', ordenacao: 0 };

  const dt = new Date(valor);
  if (Number.isNaN(dt.getTime())) {
    return { data: String(valor), hora: '', ordenacao: 0 };
  }

  return {
    data: dt.toLocaleDateString('pt-BR'),
    hora: dt.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }),
    ordenacao: dt.getTime(),
  };
}

/* ---------- Autenticacao e CSRF ---------- */

/** Le um cookie pelo nome. Usado para o token CSRF e para saber se ha sessao.
 *  Devolve null se o cookie nao existir. */
function lerCookie(nome) {
  for (const parte of document.cookie.split('; ')) {
    const i = parte.indexOf('=');
    if (i > 0 && parte.slice(0, i) === nome) {
      return decodeURIComponent(parte.slice(i + 1));
    }
  }
  return null;
}

/** Ha sessao? Olhamos o cookie, que e HttpOnly, para NAO confiar apenas em estado
 *  do JS. Nao conseguimos ler o valor (e HttpOnly mesmo), so saber se existe. */
function temSessao() {
  return document.cookie.split('; ').some((p) => p.startsWith(COOKIE_SESSAO + '='));
}

/** Pede ao servidor para emitir o cookie XSRF-TOKEN, se ainda nao existir. */
async function garantirTokenCsrf() {
  if (lerCookie(COOKIE_CSRF)) return;
  try {
    await fetch(API_BASE + '/api/auth/csrf', { credentials: 'same-origin' });
  } catch {
    // Sem rede o login vai falhar adiante com mensagem melhor.
  }
}

/* ---------- Sessao: expiracao ---------- */

let temporizadorInatividade = null;

/** Encerra a sessao do lado do cliente e volta para a tela de login.
 *  `motivo` vira a mensagem mostrada ao usuario. */
function encerrarSessao(motivo) {
  state.usuario = null;
  limparEstado();
  clearTimeout(temporizadorInatividade);
  mostrarTelaLogin(motivo);
}

/** Zera os dados em memoria para nao vazar registro de um usuario para a tela
 *  do proximo que logar na mesma maquina. */
function limparEstado() {
  for (const chave of Object.keys(ENDPOINTS)) state[chave] = [];
}

/** Quais listas este usuario pode carregar. /api/usuarios exige ADMIN: busca-lo
 *  como ATENDENTE daria 403 e pintaria a barra de status de vermelho a toa. */
function chavesCarregaveis() {
  return ehAdmin() ? Object.keys(ENDPOINTS) : CRUD_GENERICO;
}

/** Reinicia a contagem de inatividade. Chamada a cada interacao do usuario. */
function renovarInatividade() {
  clearTimeout(temporizadorInatividade);
  if (!state.usuario) return;
  temporizadorInatividade = setTimeout(() => {
    // Nao ha "logout local": encerramos no servidor tambem, senao o cookie
    // de sessao continuaria valido e um F5 voltaria logado.
    sair('Sessão encerrada por inatividade.');
  }, LIMITE_INATIVIDADE_MS);
}

/* ---------- Camada HTTP ---------- */

async function api(path, options = {}) {
  const { method = 'GET', body } = options;

  // O token vai em TODA chamada que muda estado. GET/HEAD nao precisam: o
  // SecurityConfig isenta os metodos seguros do CSRF.
  const cabecalhos = {};
  if (body) cabecalhos['Content-Type'] = 'application/json';

  const token = lerCookie(COOKIE_CSRF);
  if (token && method !== 'GET' && method !== 'HEAD') {
    cabecalhos['X-XSRF-TOKEN'] = token;
  }

  let res;
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers: cabecalhos,
      body: body ? JSON.stringify(body) : undefined,
      // Sem isto o navegador nao envia o cookie de sessao e todo request vira anonimo.
      credentials: 'same-origin',
    });
  } catch {
    // fetch so rejeita por falha de rede - o backend provavelmente esta parado.
    // Nao revelamos a URL do backend: o usuario nao precisa dela para agir.
    throw new Error('Não foi possível falar com o servidor. Verifique sua conexão.');
  }

  if (!res.ok) {
    const corpo = await res.json().catch(() => null);
    const campos = corpo?.campos ? ' ' + Object.values(corpo.campos).join(' ') : '';
    const erro = new Error((corpo?.mensagem ?? mensagemPadrao(res.status)) + campos);
    erro.status = res.status;

    // 401 = sessao caiu ou expirou. Volta para o login, mas SEM tentar de novo:
    // a chamada original falhou e insistir so geraria loop.
    if (res.status === 401 && state.usuario) {
      encerrarSessao('Sua sessão expirou. Entre novamente.');
    }

    throw erro;
  }

  if (res.status === 204) return null;
  return res.json();
}

/** Mensagem generica por codigo, usada quando o corpo nao traz "mensagem".
 *  Sem detalhe tecnico: o usuario ve o que aconteceu, nao como o servidor falhou. */
function mensagemPadrao(status) {
  if (status === 400) return 'Dados inválidos. Confira os campos.';
  if (status === 401) return 'Sessão expirada. Entre novamente.';
  if (status === 403) return 'Você não tem permissão para esta ação.';
  if (status === 404) return 'Registro não encontrado.';
  if (status === 409) return 'Não é possível concluir: há registros ligados a este.';
  if (status >= 500) return 'O servidor teve um problema. Tente novamente.';
  return 'Não foi possível concluir a operação.';
}

/* ---------- Status da conexao ---------- */

function setStatus(kind, text) {
  const dot = $('#status-dot');
  dot.className = `dot dot--${kind}`;
  $('#status-text').textContent = text;
}

/* ---------- Toasts ---------- */

function toast(message, kind = 'info', timeout = 5000) {
  const box = el('div', `toast toast--${kind}`);

  const msg = el('span', 'toast__msg', message);
  const close = el('button', 'toast__close', '×');
  close.type = 'button';
  close.setAttribute('aria-label', 'Fechar aviso');
  close.addEventListener('click', () => dismiss());

  box.append(msg, close);
  $('#toasts').append(box);

  let timer = timeout ? setTimeout(dismiss, timeout) : null;

  function dismiss() {
    if (timer) clearTimeout(timer);
    timer = null;
    box.classList.add('is-leaving');
    box.addEventListener('animationend', () => box.remove(), { once: true });
    // Rede de seguranca caso a animacao nao dispare (reduced-motion).
    setTimeout(() => box.remove(), 400);
  }

  return dismiss;
}

/* ---------- Hint sob o formulario ---------- */

function showHint(id, message, kind) {
  const node = document.getElementById(id);
  if (!node) return;

  node.textContent = message;
  node.className = `hint${kind ? ` hint--${kind}` : ''}`;
  node.hidden = !message;
}

/** Marca/limpa o estado de erro dos campos de um form. */
function setInvalid(form, invalidIds = []) {
  form.querySelectorAll('[aria-invalid]').forEach((f) => f.removeAttribute('aria-invalid'));
  invalidIds.forEach((id) => document.getElementById(id)?.setAttribute('aria-invalid', 'true'));
}

/** Trava o botao durante o request, mostrando o spinner. */
async function withLoading(button, fn) {
  const original = button.textContent;
  button.disabled = true;
  button.classList.add('is-loading');

  try {
    return await fn();
  } finally {
    button.disabled = false;
    button.classList.remove('is-loading');
    button.textContent = original;
  }
}

/* ---------- Carregamento de dados ---------- */

async function carregarTudo() {
  setStatus('loading', 'Carregando…');

  const nomes = chavesCarregaveis();
  const resultados = await Promise.allSettled(
    nomes.map((nome) => api(ENDPOINTS[nome]))
  );

  const falhas = [];

  resultados.forEach((resultado, i) => {
    const nome = nomes[i];
    if (resultado.status === 'fulfilled') {
      state[nome] = Array.isArray(resultado.value) ? resultado.value : [];
    } else {
      state[nome] = [];
      falhas.push(resultado.reason);
    }
  });

  // Sessao expirada nao e "sem conexao": o api() ja mandou o usuario para o
  // login. Reportar falha de carregamento aqui seria mentira e ainda por cima
  // escreveria status na barra do topo depois dela ter sido trocada.
  if (falhas.some((f) => f.status === 401)) return;

  renderTudo();

  if (falhas.length === 0) {
    setStatus('ok', 'Conectado');
  } else if (falhas.length === nomes.length) {
    setStatus('error', 'Sem conexão');
    toast('Não foi possível carregar os dados. Verifique sua conexão.', 'error', 9000);
  } else {
    setStatus('error', 'Carga parcial');
    toast(`Alguns dados não carregaram. ${falhas[0].message}`, 'error', 9000);
  }
}

/* ---------- Render: selects ---------- */

function renderSelects() {
  const selects = [
    { node: $('#ag-veiculo'), placeholder: 'Selecione um veículo…',
      items: state.veiculos,
      label: (v) => `${v.placa} — ${v.modelo}${v.cliente?.nome ? ` (${v.cliente.nome})` : ''}` },
    { node: $('#ag-servico'), placeholder: 'Selecione um serviço…',
      items: state.servicos,
      label: (s) => `${s.nome} — ${money.format(s.preco ?? 0)} · ${s.tempoEstimado ?? '?'} min` },
    { node: $('#ve-cliente'), placeholder: 'Selecione um cliente…',
      items: state.clientes,
      label: (c) => `${c.nome}${c.telefone ? ` — ${c.telefone}` : ''}` },
  ];

  for (const { node, placeholder, items, label } of selects) {
    if (!node) continue;

    const anterior = node.value;
    node.replaceChildren();

    const first = el('option', null, items.length ? placeholder : 'Nenhum cadastro disponível');
    first.value = '';
    node.append(first);

    for (const item of items) {
      const opt = el('option', null, label(item));
      opt.value = String(item.id);
      node.append(opt);
    }

    // Mantem a escolha do usuario se o item ainda existir.
    if (anterior && items.some((i) => String(i.id) === anterior)) {
      node.value = anterior;
    }
  }
}

/* ---------- Render: tabelas ---------- */

function emptyRow(colspan, message) {
  const tr = el('tr');
  const td = el('td', 'empty', message);
  td.colSpan = colspan;
  tr.append(td);
  return tr;
}

/**
 * Celula de acoes (editar / excluir) de uma linha.
 * @param {string} recurso  chave em ENDPOINTS
 * @param {object} item     registro da linha
 * @param {string} descricao como o registro aparece na confirmacao
 */
function acoesCell(recurso, item, descricao, extra) {
  const td = el('td', 'acoes');

  const editar = el('button', 'icon-btn', 'Editar');
  editar.type = 'button';
  editar.setAttribute('aria-label', `Editar ${descricao}`);
  editar.addEventListener('click', () => abrirEdicao(recurso, item));

  if (extra) td.append(extra);
  td.append(editar);

  // Esconder "Excluir" de quem nao e ADMIN e so estetica: a regra de verdade
  // esta no SecurityConfig (DELETE /api/** exige ROLE_ADMIN) e o servidor
  // responde 403 se alguem chamar a API na mao.
  if (ehAdmin()) {
    const excluir = el('button', 'icon-btn icon-btn--danger', 'Excluir');
    excluir.type = 'button';
    excluir.setAttribute('aria-label', `Excluir ${descricao}`);
    excluir.addEventListener('click', () => abrirExclusao(recurso, item, descricao));
    td.append(excluir);
  }

  return td;
}

const PROXIMO = {
  AGUARDANDO:   ['EM_ANDAMENTO', 'Iniciar'],
  EM_ANDAMENTO: ['CONCLUIDO', 'Concluir'],
};

/** Botão "Iniciar" / "Concluir": avança o status sem abrir o diálogo de edição. */
function botaoAvancar(ag) {
  const prox = PROXIMO[ag.status || 'AGUARDANDO'];
  if (!prox) return null;

  const btn = el('button', 'icon-btn icon-btn--ok', prox[1]);
  btn.type = 'button';
  btn.addEventListener('click', async () => {
    try {
      await withLoading(btn, () => api(`${ENDPOINTS.agendamentos}/${ag.id}`, {
        method: 'PUT',
        body: {
          dataHora: ag.dataHora,
          status: prox[0],
          // So os ids: o backend resolve a entidade. Mandar o objeto inteiro
          // arriscava sobrescrever campos que nao sao desta tela.
          veiculo: { id: ag.veiculo?.id },
          servico: { id: ag.servico?.id },
        },
      }));
      toast(`Agendamento: ${STATUS_LABEL[prox[0]]}.`, 'ok');
      await carregarTudo();
    } catch (err) {
      toast(err.message, 'error', 8000);
    }
  });
  return btn;
}

function dentroDoPeriodo(a) {
  const t = parseDataHora(a.dataHora).ordenacao;
  const ini = new Date();
  ini.setHours(0, 0, 0, 0);
  const dias = state.filtroPeriodo === 'hoje' ? 1 : 7;
  return t >= ini.getTime() && t < ini.getTime() + dias * 86400000;
}

function renderAgendamentos() {
  const tbody = $('#tbody-agendamentos');
  tbody.replaceChildren();

  let lista = state.agendamentos.slice();

  // Mais recentes primeiro.
  lista.sort((a, b) => parseDataHora(b.dataHora).ordenacao - parseDataHora(a.dataHora).ordenacao);

  if (state.filtroStatus) {
    lista = lista.filter((a) => a.status === state.filtroStatus);
  }
  if (state.filtroPeriodo) {
    lista = lista.filter(dentroDoPeriodo);
  }

  if (lista.length === 0) {
    tbody.append(emptyRow(
      7,
      state.agendamentos.length === 0
        ? 'Nenhum agendamento cadastrado ainda.'
        : 'Nenhum agendamento com esse filtro.'
    ));
    return;
  }

  const frag = document.createDocumentFragment();

  for (const ag of lista) {
    const { data, hora } = parseDataHora(ag.dataHora);
    const status = ag.status || 'AGUARDANDO';

    const tr = el('tr');

    const tdData = el('td');
    tdData.append(
      el('span', 'cell-strong', data),
      el('span', 'cell-sub', hora)
    );

    const tdCliente = el('td', null, ag.veiculo?.cliente?.nome ?? '—');

    const tdVeiculo = el('td');
    tdVeiculo.append(
      el('span', 'cell-strong mono', ag.veiculo?.placa ?? '—'),
      el('span', 'cell-sub', ag.veiculo?.modelo ?? '')
    );

    const tdServico = el('td');
    tdServico.append(
      el('span', 'cell-strong', ag.servico?.nome ?? '—'),
      el('span', 'cell-sub', ag.servico?.tempoEstimado ? `${ag.servico.tempoEstimado} min` : '')
    );

    const tdValor = el('td', 'num', money.format(ag.precoCobrado ?? ag.servico?.preco ?? 0));

    const tdStatus = el('td');
    tdStatus.append(el(
      'span',
      `badge badge--${status.toLowerCase()}`,
      STATUS_LABEL[status] ?? status
    ));

    const descricaoAg = `${data} ${hora} — ${ag.servico?.nome ?? 'serviço'}`;
    tr.append(
      tdData, tdCliente, tdVeiculo, tdServico, tdValor, tdStatus,
      acoesCell('agendamentos', ag, descricaoAg, botaoAvancar(ag))
    );
    frag.append(tr);
  }

  tbody.append(frag);
}

function renderClientes() {
  const tbody = $('#tbody-clientes');
  tbody.replaceChildren();

  if (state.clientes.length === 0) {
    tbody.append(emptyRow(5, 'Nenhum cliente cadastrado ainda.'));
    return;
  }

  const frag = document.createDocumentFragment();

  for (const c of state.clientes) {
    const qtd = state.veiculos.filter((v) => v.cliente?.id === c.id).length;

    const tr = el('tr');
    tr.append(
      el('td', 'muted', String(c.id ?? '—')),
      el('td', 'cell-strong', c.nome ?? '—'),
      el('td', 'mono', c.telefone ?? '—'),
      el('td', 'num', String(qtd)),
      acoesCell('clientes', c, c.nome ?? 'cliente')
    );
    frag.append(tr);
  }

  tbody.append(frag);
}

function renderVeiculos() {
  const tbody = $('#tbody-veiculos');
  tbody.replaceChildren();

  if (state.veiculos.length === 0) {
    tbody.append(emptyRow(5, 'Nenhum veículo cadastrado ainda.'));
    return;
  }

  const frag = document.createDocumentFragment();

  for (const v of state.veiculos) {
    const tr = el('tr');
    tr.append(
      el('td', 'muted', String(v.id ?? '—')),
      el('td', 'cell-strong mono', v.placa ?? '—'),
      el('td', null, v.modelo ?? '—'),
      el('td', null, v.cliente?.nome ?? '—'),
      acoesCell('veiculos', v, `veículo ${v.placa ?? ''}`.trim())
    );
    frag.append(tr);
  }

  tbody.append(frag);
}

function renderServicos() {
  const tbody = $('#tbody-servicos');
  tbody.replaceChildren();

  if (state.servicos.length === 0) {
    tbody.append(emptyRow(5, 'Nenhum serviço cadastrado ainda.'));
    return;
  }

  const frag = document.createDocumentFragment();

  for (const s of state.servicos) {
    const tr = el('tr');
    tr.append(
      el('td', 'muted', String(s.id ?? '—')),
      el('td', 'cell-strong', s.nome ?? '—'),
      el('td', 'num', money.format(s.preco ?? 0)),
      el('td', 'num', s.tempoEstimado ? `${s.tempoEstimado} min` : '—'),
      acoesCell('servicos', s, s.nome ?? 'serviço')
    );
    frag.append(tr);
  }

  tbody.append(frag);
}

/** Acoes da linha de funcionario.
 *
 *  Nao usa acoesCell(): la o "Excluir" e uma remocao definitiva, e aqui a acao
 *  e DESATIVAR (o backend preserva o registro). Rotulo diferente para o usuario
 *  nao achar que vai apagar o historico de quem operou.
 *
 *  Editar fica disponivel mesmo para quem esta inativo - e assim que se
 *  reativa alguem; sem isso, um funcionario desativado por engano ficaria preso
 *  fora do sistema, sem tela para voltar. */
function acoesFuncionario(u) {
  const td = el('td', 'acoes');

  const editar = el('button', 'icon-btn', 'Editar');
  editar.type = 'button';
  editar.setAttribute('aria-label', `Editar ${u.nome ?? u.login}`);
  editar.addEventListener('click', () => abrirEdicao('funcionarios', u));
  td.append(editar);

  // Nao oferece desativar a propria conta: o backend recusa (ultimo admin) ou
  // deixaria o usuario se expulsando no meio do expediente sem perceber por que.
  const ehPropria = u.login === state.usuario?.login;

  if (u.ativo && !ehPropria && !ehUltimoAdmin(u)) {
    const desativar = el('button', 'icon-btn icon-btn--danger', 'Desativar');
    desativar.type = 'button';
    desativar.setAttribute('aria-label', `Desativar ${u.nome ?? u.login}`);
    desativar.addEventListener('click', () => abrirDesativacao(u));
    td.append(desativar);
  }

  return td;
}

/** Este e o unico ADMIN ativo? Espelha a regra do backend (protegerUltimoAdmin)
 *  para nao oferecer um botao que so pode terminar em 422. */
function ehUltimoAdmin(u) {
  if (u.perfil !== 'ADMIN' || !u.ativo) return false;
  return state.funcionarios.filter((f) => f.perfil === 'ADMIN' && f.ativo).length <= 1;
}

function renderFuncionarios() {
  const tbody = $('#tbody-funcionarios');
  tbody.replaceChildren();

  if (state.funcionarios.length === 0) {
    tbody.append(emptyRow(6, 'Nenhum funcionário cadastrado ainda.'));
    return;
  }

  // Ativos primeiro, depois por nome: quem esta trabalhando hoje interessa mais.
  const lista = state.funcionarios.slice().sort((a, b) => {
    if (a.ativo !== b.ativo) return a.ativo ? -1 : 1;
    return String(a.nome ?? '').localeCompare(String(b.nome ?? ''), 'pt-BR');
  });

  const frag = document.createDocumentFragment();

  for (const u of lista) {
    const ehPropria = u.login === state.usuario?.login;

    const tdNome = el('td');
    tdNome.append(el('span', 'cell-strong', u.nome ?? '—'));
    if (ehPropria) tdNome.append(el('span', 'cell-sub', 'você'));

    const tdPerfil = el('td');
    tdPerfil.append(el('span', 'badge', PERFIL_LABEL[u.perfil] ?? u.perfil ?? '—'));

    const tdSituacao = el('td');
    tdSituacao.append(el(
      'span',
      u.ativo ? 'badge badge--ativo' : 'badge badge--inativo',
      u.ativo ? 'Ativo' : 'Desativado'
    ));

    const tr = el('tr');
    if (!u.ativo) tr.className = 'row--inativo';

    tr.append(
      el('td', 'muted', String(u.id ?? '—')),
      tdNome,
      el('td', 'mono', u.login ?? '—'),
      tdPerfil,
      tdSituacao,
      acoesFuncionario(u)
    );
    frag.append(tr);
  }

  tbody.append(frag);
}

function renderStats() {
  const ativos = state.agendamentos.filter((a) => a.status !== 'CANCELADO');

  $('#stat-agendamentos-total').textContent = String(state.agendamentos.length);
  $('#stat-agendamentos-aguardando').textContent =
    String(state.agendamentos.filter((a) => (a.status || 'AGUARDANDO') === 'AGUARDANDO').length);

  const faturamento = ativos.reduce((soma, a) => soma + Number(a.precoCobrado ?? a.servico?.preco ?? 0), 0);
  $('#stat-agendamentos-faturamento').textContent = money.format(faturamento);
}

function renderTudo() {
  renderSelects();
  renderStats();
  renderAgendamentos();
  renderClientes();
  renderVeiculos();
  renderServicos();
  renderFuncionarios();
}

/* ---------- Editar / Excluir ---------- */

/**
 * Descreve, por recurso, como montar o formulario de edicao e como ler os
 * valores de volta. Manter tudo aqui evita quatro blocos quase iguais.
 */
const EDICAO = {
  clientes: {
    titulo: 'Editar cliente',
    campos: [
      { id: 'ed-cl-nome',     campo: 'nome',     label: 'Nome',     tipo: 'text', required: true },
      { id: 'ed-cl-telefone', campo: 'telefone', label: 'Telefone', tipo: 'tel',  required: true },
    ],
    corpo(item) {
      const c = { ...item };
      return {
        nome: c.nome ?? '',
        telefone: c.telefone ?? '',
      };
    },
    montar(item, dados) {
      return { id: item.id, nome: dados.nome.trim(), telefone: dados.telefone.trim() };
    },
  },

  servicos: {
    titulo: 'Editar serviço',
    campos: [
      { id: 'ed-se-nome',  campo: 'nome',          label: 'Nome',        tipo: 'text',   required: true },
      { id: 'ed-se-preco', campo: 'preco',         label: 'Preço (R$)',  tipo: 'number', required: true, min: 0, step: '0.01' },
      { id: 'ed-se-tempo', campo: 'tempoEstimado', label: 'Duração (min)', tipo: 'number', required: true, min: 1, step: '5' },
    ],
    corpo(item) {
      return {
        nome: item.nome ?? '',
        preco: item.preco ?? '',
        tempoEstimado: item.tempoEstimado ?? '',
      };
    },
    montar(item, dados) {
      return {
        id: item.id,
        nome: dados.nome.trim(),
        preco: Number(dados.preco),
        tempoEstimado: Number(dados.tempoEstimado),
      };
    },
  },

  veiculos: {
    titulo: 'Editar veículo',
    campos: [
      { id: 'ed-ve-cliente', campo: 'cliente', label: 'Cliente', tipo: 'select-clientes', required: true },
      { id: 'ed-ve-placa',   campo: 'placa',   label: 'Placa',   tipo: 'text', required: true, maxlength: 8, upper: true },
      { id: 'ed-ve-modelo',  campo: 'modelo',  label: 'Modelo',  tipo: 'text', required: true },
    ],
    corpo(item) {
      return {
        cliente: item.cliente?.id ?? '',
        placa: item.placa ?? '',
        modelo: item.modelo ?? '',
      };
    },
    montar(item, dados) {
      const clienteOk = state.clientes.some((c) => String(c.id) === String(dados.cliente));
      if (!clienteOk) throw new Error('Selecione um cliente válido.');
      return {
        id: item.id,
        placa: dados.placa.toUpperCase().replace(/[^A-Z0-9]/g, ''),
        modelo: dados.modelo.trim(),
        cliente: { id: Number(dados.cliente) },
      };
    },
  },

  agendamentos: {
    titulo: 'Editar agendamento',
    campos: [
      { id: 'ed-ag-veiculo', campo: 'veiculo', label: 'Veículo', tipo: 'select-veiculos', required: true },
      { id: 'ed-ag-servico', campo: 'servico', label: 'Serviço', tipo: 'select-servicos', required: true },
      { id: 'ed-ag-data',    campo: 'data',    label: 'Data',    tipo: 'date',   required: true },
      { id: 'ed-ag-hora',    campo: 'hora',    label: 'Hora',    tipo: 'time',   required: true },
      { id: 'ed-ag-status',  campo: 'status',  label: 'Status',  tipo: 'select-status' },
    ],
    corpo(item) {
      // O input type=date/time precisa de ISO, nao do formato pt-BR.
      const dt = item.dataHora ? new Date(item.dataHora) : null;
      const iso = dt && !Number.isNaN(dt.getTime());
      return {
        veiculo: item.veiculo?.id ?? '',
        servico: item.servico?.id ?? '',
        data: iso ? [
          dt.getFullYear(),
          String(dt.getMonth() + 1).padStart(2, '0'),
          String(dt.getDate()).padStart(2, '0'),
        ].join('-') : '',
        hora: iso ? [
          String(dt.getHours()).padStart(2, '0'),
          String(dt.getMinutes()).padStart(2, '0'),
        ].join(':') : '',
        status: item.status ?? 'AGUARDANDO',
      };
    },
    montar(item, dados) {
      const veiculoOk = state.veiculos.some((v) => String(v.id) === String(dados.veiculo));
      const servicoOk = state.servicos.some((s) => String(s.id) === String(dados.servico));
      if (!veiculoOk) throw new Error('Selecione um veículo válido.');
      if (!servicoOk) throw new Error('Selecione um serviço válido.');

      return {
        id: item.id,
        dataHora: `${dados.data}T${dados.hora}:00`,
        status: dados.status,
        // So os ids, como nos demais formularios.
        veiculo: { id: Number(dados.veiculo) },
        servico: { id: Number(dados.servico) },
      };
    },
  },

  funcionarios: {
    titulo: 'Editar funcionário',
    campos: [
      { id: 'ed-fu-nome',   campo: 'nome',   label: 'Nome',   tipo: 'text', required: true },
      { id: 'ed-fu-login',  campo: 'login',  label: 'Login',  tipo: 'text', required: true, lower: true },
      // Vazio = manter a senha atual. Por isso NAO e required: e o que permite
      // corrigir um nome sem trocar a senha de ninguem.
      { id: 'ed-fu-senha',  campo: 'senha',  label: 'Nova senha', tipo: 'password' },
      { id: 'ed-fu-perfil', campo: 'perfil', label: 'Perfil', tipo: 'select-perfis', required: true },
      { id: 'ed-fu-ativo',  campo: 'ativo',  label: 'Situação', tipo: 'select-ativo', required: true },
    ],
    corpo(item) {
      return {
        nome: item.nome ?? '',
        login: item.login ?? '',
        senha: '',                                  // nunca preenchemos senha existente
        perfil: item.perfil ?? 'ATENDENTE',
        ativo: item.ativo === false ? 'false' : 'true',
      };
    },
    montar(item, dados) {
      const login = dados.login.trim().toLowerCase();

      if (login.length < 3) {
        throw new Error('O login deve ter pelo menos 3 caracteres.');
      }

      // O backend trata senha em branco como "manter a atual" (o record
      // UsuarioEntrada converte "" em null). Mandar a string vazia e o que
      // expressa essa intencao - por isso ela vai como veio.
      const senha = dados.senha ?? '';
      if (senha && senha.length < 12) {
        throw new Error('A nova senha deve ter pelo menos 12 caracteres.');
      }

      return {
        login,
        nome: dados.nome.trim(),
        senha,
        perfil: dados.perfil,
        ativo: dados.ativo === 'true',
      };
    },
  },
};

let edicaoAtual = null; // { recurso, item }

function construirCampoEdicao(campo, valorAtual) {
  const wrap = el('div', 'field');
  const id = campo.id;

  const label = el('label', null, campo.label);
  label.htmlFor = id;
  if (campo.required) {
    const req = el('span', 'req', '*');
    req.setAttribute('aria-hidden', 'true');
    label.append(' ', req);
  }
  wrap.append(label);

  let input;

  if (campo.tipo.startsWith('select-')) {
    input = document.createElement('select');

    const origem = {
      'select-clientes': {
        itens: state.clientes,
        txt: (c) => `${c.nome}${c.telefone ? ` — ${c.telefone}` : ''}`,
      },
      'select-veiculos': {
        itens: state.veiculos,
        txt: (v) => `${v.placa} — ${v.modelo}${v.cliente?.nome ? ` (${v.cliente.nome})` : ''}`,
      },
      'select-servicos': {
        itens: state.servicos,
        txt: (s) => `${s.nome} — ${money.format(s.preco ?? 0)} · ${s.tempoEstimado ?? '?'} min`,
      },
      'select-status': {
        itens: Object.entries(STATUS_LABEL).map(([v, t]) => ({ id: v, _txt: t })),
        txt: (s) => s._txt,
      },
      'select-perfis': {
        itens: Object.entries(PERFIL_LABEL).map(([v, t]) => ({ id: v, _txt: t })),
        txt: (p) => p._txt,
      },
      // Sem placeholder: "Ativo"/"Desativado" nao tem estado neutro - deixar a
      // opcao vazia permitiria salvar sem querer mudar a situacao.
      'select-ativo': {
        itens: [{ id: 'true', _txt: 'Ativo' }, { id: 'false', _txt: 'Desativado' }],
        txt: (s) => s._txt,
        semVazio: true,
      },
    }[campo.tipo];

    if (!origem.semVazio) {
      const vazio = el('option', null, 'Selecione…');
      vazio.value = '';
      input.append(vazio);
    }

    for (const it of origem.itens) {
      const opt = el('option', null, origem.txt(it));
      opt.value = String(it.id);
      input.append(opt);
    }
  } else {
    input = document.createElement('input');
    input.type = campo.tipo;
    if (campo.min != null)      input.min = campo.min;
    if (campo.step != null)     input.step = campo.step;
    if (campo.maxlength != null) input.maxLength = campo.maxlength;
    if (campo.upper) {
      input.style.textTransform = 'uppercase';
      input.addEventListener('input', () => {
        const p = input.selectionStart;
        input.value = input.value.toUpperCase();
        input.setSelectionRange(p, p);
      });
    }

    // O login e gravado em minusculas pelo backend (UsuarioController faz
    // trim().toLowerCase()). Espelhar aqui evita o usuario digitar "Ana" e ver
    // depois "ana" na lista, achando que o sistema trocou o que ele escreveu.
    if (campo.lower) {
      input.addEventListener('input', () => {
        const p = input.selectionStart;
        input.value = input.value.toLowerCase();
        input.setSelectionRange(p, p);
      });
    }
  }

  input.id = id;
  input.name = campo.campo;
  if (campo.required) input.required = true;
  input.value = valorAtual ?? '';

  // Se o valor atual nao existir mais na lista, o select fica vazio em
  // silencio. Avisa para o usuario nao salvar apontando para o nada.
  if (campo.tipo.startsWith('select-') && valorAtual && input.value === '') {
    const aviso = el('option', null, '⚠ registro atual não encontrado — selecione outro');
    aviso.value = '';
    aviso.disabled = true;
    aviso.selected = true;
    input.prepend(aviso);
  }

  wrap.append(input);
  return wrap;
}

function abrirEdicao(recurso, item) {
  const cfg = EDICAO[recurso];
  if (!cfg) return;

  edicaoAtual = { recurso, item };

  $('#dialog-editar-titulo').textContent = cfg.titulo;
  showHint('hint-editar', '');

  const container = $('#dialog-editar-campos');
  container.replaceChildren();

  const valores = cfg.corpo(item);
  for (const campo of cfg.campos) {
    container.append(construirCampoEdicao(campo, valores[campo.campo]));
  }

  const dialog = $('#dialog-editar');
  dialog.showModal();

  // Foca o primeiro campo, como se espera de um dialogo de formulario.
  const primeiro = container.querySelector('input, select');
  if (primeiro) primeiro.focus();
}

/** Todos os <dialog> da tela. Manter numa lista so evita esquecer um deles ao
 *  adicionar outro - foi o que aconteceu quando o de desativacao entrou. */
const DIALOGOS = ['#dialog-editar', '#dialog-excluir', '#dialog-desativar'];

function fecharDialogos() {
  for (const id of DIALOGOS) {
    const d = $(id);
    if (d?.open) d.close();
  }
  edicaoAtual = null;
  desativacaoAtual = null;
}

async function initSalvarEdicao() {
  const form = $('#form-editar');

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();
    if (!edicaoAtual) return;

    const { recurso, item } = edicaoAtual;
    const cfg = EDICAO[recurso];

    const dados = {};
    const invalidos = [];

    for (const campo of cfg.campos) {
      const node = document.getElementById(campo.id);
      const valor = node ? node.value : '';
      dados[campo.campo] = valor;
      if (campo.required && !String(valor).trim()) invalidos.push(campo.id);
    }

    // Limpa estados de erro anteriores.
    for (const campo of cfg.campos) {
      document.getElementById(campo.id)?.removeAttribute('aria-invalid');
    }

    if (invalidos.length) {
      invalidos.forEach((id) => document.getElementById(id)?.setAttribute('aria-invalid', 'true'));
      showHint('hint-editar', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    let corpo;
    try {
      corpo = cfg.montar(item, dados);
    } catch (err) {
      showHint('hint-editar', err.message, 'error');
      return;
    }

    try {
      await withLoading($('#btn-salvar-edicao'), () =>
        api(`${ENDPOINTS[recurso]}/${item.id}`, { method: 'PUT', body: corpo }));

      fecharDialogos();
      toast('Alterações salvas.', 'ok');
      await carregarTudo();
    } catch (err) {
      showHint('hint-editar', err.message, 'error');
    }
  });
}

let exclusaoAtual = null; // { recurso, item }

function abrirExclusao(recurso, item, descricao) {
  exclusaoAtual = { recurso, item };

  $('#dialog-excluir-texto').textContent =
    `Tem certeza que deseja excluir ${descricao}? Esta ação não pode ser desfeita.`;

  $('#dialog-excluir').showModal();
  $('#btn-confirmar-exclusao').focus();
}

async function initConfirmarExclusao() {
  $('#btn-confirmar-exclusao').addEventListener('click', async () => {
    if (!exclusaoAtual) return;
    const { recurso, item } = exclusaoAtual;

    try {
      await withLoading($('#btn-confirmar-exclusao'), () =>
        api(`${ENDPOINTS[recurso]}/${item.id}`, { method: 'DELETE' }));

      fecharDialogos();
      toast('Registro excluído.', 'ok');
      await carregarTudo();
    } catch (err) {
      // 409 = o backend recusou porque existem registros dependentes.
      fecharDialogos();
      toast(err.message, 'error', err.status === 409 ? 9000 : 8000);
    }
  });
}

/* ---------- Desativar funcionario ---------- */

let desativacaoAtual = null;

function abrirDesativacao(usuario) {
  desativacaoAtual = usuario;

  $('#dialog-desativar-texto').textContent =
    `Desativar o acesso de ${usuario.nome ?? usuario.login} (${usuario.login})? `
    + 'O histórico dele é preservado, mas ele não conseguirá mais entrar, '
    + 'e a sessão aberta cai na hora. Dá para reativar editando o cadastro.';

  $('#dialog-desativar').showModal();
  $('#btn-confirmar-desativacao').focus();
}

async function initConfirmarDesativacao() {
  $('#btn-confirmar-desativacao').addEventListener('click', async () => {
    if (!desativacaoAtual) return;
    const usuario = desativacaoAtual;

    try {
      await withLoading($('#btn-confirmar-desativacao'), () =>
        api(`${ENDPOINTS.funcionarios}/${usuario.id}`, { method: 'DELETE' }));

      fecharDialogos();
      toast(`Acesso de ${usuario.nome ?? usuario.login} desativado.`, 'ok');
      await carregarTudo();
    } catch (err) {
      fecharDialogos();
      toast(err.message, 'error', 9000);
    }
  });
}

/* ---------- Formularios ---------- */

function initFormAgendamento() {
  const form = $('#form-agendamento');

  // Sugere hoje como data padrao.
  const hoje = new Date();
  $('#ag-data').value = [
    hoje.getFullYear(),
    String(hoje.getMonth() + 1).padStart(2, '0'),
    String(hoje.getDate()).padStart(2, '0'),
  ].join('-');

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();

    const veiculoId = $('#ag-veiculo').value;
    const servicoId = $('#ag-servico').value;
    const data = $('#ag-data').value;
    const hora = $('#ag-hora').value;

    const invalidos = [];
    if (!veiculoId) invalidos.push('ag-veiculo');
    if (!servicoId) invalidos.push('ag-servico');
    if (!data)      invalidos.push('ag-data');
    if (!hora)      invalidos.push('ag-hora');

    setInvalid(form, invalidos);

    if (invalidos.length) {
      showHint('hint-agendamento', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    // O backend espera LocalDateTime sem timezone: "2026-11-05T09:30:00".
    const dataHora = `${data}T${hora}:00`;

    // Confere que os ids escolhidos existem de fato nos dados carregados -
    // validacao de UX; o backend valida de verdade.
    const veiculoOk = state.veiculos.some((v) => String(v.id) === veiculoId);
    const servicoOk = state.servicos.some((s) => String(s.id) === servicoId);

    if (!veiculoOk || !servicoOk) {
      showHint('hint-agendamento', 'Veículo ou serviço inválido. Recarregue a página.', 'error');
      return;
    }

    showHint('hint-agendamento', '');

    try {
      await withLoading($('#btn-agendamento'), () => api(ENDPOINTS.agendamentos, {
        method: 'POST',
        body: {
          dataHora,
          status: $('#ag-status').value,
          // So os ids: o backend resolve a entidade e calcula o preco.
          veiculo: { id: Number(veiculoId) },
          servico: { id: Number(servicoId) },
        },
      }));

      toast('Agendamento criado com sucesso.', 'ok');
      form.reset();
      $('#ag-data').value = data;
      await carregarTudo();
    } catch (err) {
      showHint('hint-agendamento', err.message, 'error');
      toast(err.message, 'error', 8000);
    }
  });
}

function initFormCliente() {
  const form = $('#form-cliente');

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();

    const nome = $('#cl-nome').value.trim();
    const telefone = $('#cl-telefone').value.trim();

    const invalidos = [];
    if (!nome)     invalidos.push('cl-nome');
    if (!telefone) invalidos.push('cl-telefone');

    setInvalid(form, invalidos);

    if (invalidos.length) {
      showHint('hint-cliente', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    showHint('hint-cliente', '');

    try {
      await withLoading($('#btn-cliente'), () => api(ENDPOINTS.clientes, {
        method: 'POST',
        body: { nome, telefone },
      }));

      toast(`Cliente "${nome}" cadastrado.`, 'ok');
      form.reset();
      await carregarTudo();
    } catch (err) {
      showHint('hint-cliente', err.message, 'error');
      toast(err.message, 'error', 8000);
    }
  });
}

function initFormVeiculo() {
  const form = $('#form-veiculo');

  // Placa sempre em maiusculas.
  $('#ve-placa').addEventListener('input', (e) => {
    const pos = e.target.selectionStart;
    e.target.value = e.target.value.toUpperCase();
    e.target.setSelectionRange(pos, pos);
  });

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();

    const clienteId = $('#ve-cliente').value;
    const placa = $('#ve-placa').value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    const modelo = $('#ve-modelo').value.trim();

    const invalidos = [];
    if (!clienteId) invalidos.push('ve-cliente');
    if (!placa)     invalidos.push('ve-placa');
    if (!modelo)    invalidos.push('ve-modelo');

    setInvalid(form, invalidos);

    if (invalidos.length) {
      showHint('hint-veiculo', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    const clienteOk = state.clientes.some((c) => String(c.id) === clienteId);
    if (!clienteOk) {
      showHint('hint-veiculo', 'Cliente inválido. Recarregue a página.', 'error');
      return;
    }

    showHint('hint-veiculo', '');

    try {
      await withLoading($('#btn-veiculo'), () => api(ENDPOINTS.veiculos, {
        method: 'POST',
        body: { placa, modelo, cliente: { id: Number(clienteId) } },
      }));

      toast(`Veículo ${placa} cadastrado.`, 'ok');
      form.reset();
      await carregarTudo();
    } catch (err) {
      showHint('hint-veiculo', err.message, 'error');
      toast(err.message, 'error', 8000);
    }
  });
}

function initFormServico() {
  const form = $('#form-servico');

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();

    const nome = $('#se-nome').value.trim();
    const preco = $('#se-preco').value;
    const tempo = $('#se-tempo').value;

    const invalidos = [];
    if (!nome)  invalidos.push('se-nome');
    if (!preco) invalidos.push('se-preco');
    if (!tempo) invalidos.push('se-tempo');

    setInvalid(form, invalidos);

    if (invalidos.length) {
      showHint('hint-servico', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    const precoNum = Number(preco);
    const tempoNum = Number(tempo);

    if (!Number.isFinite(precoNum) || precoNum < 0) {
      setInvalid(form, ['se-preco']);
      showHint('hint-servico', 'Informe um preço válido (ex.: 49.90).', 'error');
      return;
    }

    if (!Number.isInteger(tempoNum) || tempoNum <= 0) {
      setInvalid(form, ['se-tempo']);
      showHint('hint-servico', 'Informe a duração em minutos (número inteiro).', 'error');
      return;
    }

    showHint('hint-servico', '');

    try {
      await withLoading($('#btn-servico'), () => api(ENDPOINTS.servicos, {
        method: 'POST',
        body: { nome, preco: precoNum, tempoEstimado: tempoNum },
      }));

      toast(`Serviço "${nome}" cadastrado.`, 'ok');
      form.reset();
      await carregarTudo();
    } catch (err) {
      showHint('hint-servico', err.message, 'error');
      toast(err.message, 'error', 8000);
    }
  });
}

/** Cadastro de funcionario.
 *
 *  O minimo de 12 caracteres e do backend (UsuarioEntrada.@Size) e do
 *  AdminInicialSeeder. Repetido aqui para avisar ANTES de enviar: o usuario nao
 *  deveria descobrir a regra por um 400.
 */
function initFormFuncionario() {
  const form = $('#form-funcionario');

  // Login sempre em minusculas, igual ao que o backend grava.
  $('#fu-login').addEventListener('input', (e) => {
    const pos = e.target.selectionStart;
    e.target.value = e.target.value.toLowerCase();
    e.target.setSelectionRange(pos, pos);
  });

  form.addEventListener('submit', async (evento) => {
    evento.preventDefault();

    const nome   = $('#fu-nome').value.trim();
    const login  = $('#fu-login').value.trim().toLowerCase();
    const senha  = $('#fu-senha').value;
    const perfil = $('#fu-perfil').value;

    const invalidos = [];
    if (!nome)   invalidos.push('fu-nome');
    if (!login)  invalidos.push('fu-login');
    if (!senha)  invalidos.push('fu-senha');

    setInvalid(form, invalidos);

    if (invalidos.length) {
      showHint('hint-funcionario', 'Preencha os campos obrigatórios (*).', 'error');
      return;
    }

    if (login.length < 3) {
      setInvalid(form, ['fu-login']);
      showHint('hint-funcionario', 'O login deve ter pelo menos 3 caracteres.', 'error');
      return;
    }

    if (senha.length < 12) {
      setInvalid(form, ['fu-senha']);
      showHint('hint-funcionario', 'A senha deve ter pelo menos 12 caracteres.', 'error');
      return;
    }

    showHint('hint-funcionario', '');

    try {
      await withLoading($('#btn-funcionario'), () => api(ENDPOINTS.funcionarios, {
        method: 'POST',
        body: { login, senha, nome, perfil },
      }));

      toast(`Funcionário "${nome}" cadastrado.`, 'ok');
      form.reset();
      await carregarTudo();
    } catch (err) {
      showHint('hint-funcionario', err.message, 'error');
      toast(err.message, 'error', 8000);
    }
  });
}

/* ---------- Tabs ---------- */

function initTabs() {
  const tabs = Array.from(document.querySelectorAll('.tab'));

  function ativar(tab, moverFoco = true) {
    for (const t of tabs) {
      const selecionada = t === tab;
      t.setAttribute('aria-selected', String(selecionada));
      t.tabIndex = selecionada ? 0 : -1;

      const painel = document.getElementById(t.getAttribute('aria-controls'));
      if (painel) painel.hidden = !selecionada;
    }

    if (moverFoco) tab.focus();
    history.replaceState(null, '', `#${tab.dataset.panel}`);
  }

  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => ativar(tab, false));

    // Navegacao por setas, conforme o padrao ARIA de tablist.
    tab.addEventListener('keydown', (e) => {
      let alvo = null;
      if (e.key === 'ArrowRight') alvo = tabs[(i + 1) % tabs.length];
      if (e.key === 'ArrowLeft')  alvo = tabs[(i - 1 + tabs.length) % tabs.length];
      if (e.key === 'Home')       alvo = tabs[0];
      if (e.key === 'End')        alvo = tabs[tabs.length - 1];
      if (!alvo) return;

      e.preventDefault();
      ativar(alvo);
    });
  });

  // Abre a aba indicada na URL (#clientes, etc).
  const inicial = tabs.find((t) => t.dataset.panel === location.hash.slice(1));
  if (inicial) ativar(inicial, false);
}

/* ---------- Perfis ---------- */

/** Extrai apenas os perfis de dominio do que a API devolve.
 *
 *  A API manda authorities do Spring Security ("ROLE_ADMIN"). Filtramos o
 *  prefixo ROLE_ e ignoramos qualquer outra authority interna (o Spring as
 *  vezes inclui marcadores como FACTOR_*), que nao representam perfil de
 *  usuario e nao devem influenciar o que a tela mostra. */
function perfisDe(usuario) {
  return (usuario?.perfis ?? [])
    .filter((p) => typeof p === 'string' && p.startsWith('ROLE_'))
    .map((p) => p.slice('ROLE_'.length));
}

/** O usuario logado e ADMIN? */
function ehAdmin() {
  return perfisDe(state.usuario).includes('ADMIN');
}

/* ---------- Tela de login x painel ---------- */

function mostrarTelaLogin(mensagem) {
  $('#tela-login').hidden = false;
  $('#app').hidden = true;
  $('#topbar-user').hidden = true;
  $('#btn-sair').hidden = true;
  $('#status-text').textContent = 'Aguardando login';

  const hint = $('#hint-login');
  if (mensagem) {
    hint.textContent = mensagem;
    hint.hidden = false;
  } else {
    hint.hidden = true;
  }

  $('#login-senha').value = '';
  $('#login-usuario').focus();
}

function mostrarPainel(usuario) {
  state.usuario = usuario;

  $('#tela-login').hidden = true;
  $('#app').hidden = false;
  $('#hint-login').hidden = true;

  const nome = usuario.login ?? '';
  $('#usuario-nome').textContent = nome;
  $('#usuario-perfil').textContent = perfisDe(usuario).join(' · ') || '—';
  $('#topbar-user').hidden = false;
  $('#btn-sair').hidden = false;

  ajustarAbasPorPerfil();
  renderTudo();
  renovarInatividade();
  carregarTudo();
}

/** Mostra a aba Funcionários apenas para ADMIN.
 *
 *  E so apresentacao: quem chamar /api/usuarios na mao recebe 403 do
 *  SecurityConfig. Mas esconder importa - um atendente clicando numa aba que
 *  responde 403 concluiria que o sistema esta quebrado.
 *
 *  Ao esconder, tambem devolve a aba ativa para Agendamentos se ela estava
 *  aberta: um painel visivel cuja aba sumiu deixaria a tela muda, sem jeito
 *  obvio de voltar. */
function ajustarAbasPorPerfil() {
  const tab = $('#tab-funcionarios');
  const painel = $('#panel-funcionarios');
  if (!tab || !painel) return;

  const admin = ehAdmin();
  tab.hidden = !admin;
  if (admin) return;

  if (!painel.hidden) {
    const agendamentos = $('#tab-agendamentos');
    if (agendamentos) agendamentos.click();
  }
  painel.hidden = true;
}

/** Preenche a tela conforme a sessao que o servidor reconhece.
 *  E o servidor quem decide: nao confiamos em nada guardado no navegador. */
async function iniciarSessao() {
  await garantirTokenCsrf();

  if (!temSessao()) {
    mostrarTelaLogin();
    return;
  }

  try {
    const usuario = await api(AUTH.me);
    mostrarPainel(usuario);
  } catch (err) {
    // 401 aqui e normal: cookie velho de uma sessao que o servidor ja esqueceu.
    if (err.status === 401 || err.status === 403) {
      mostrarTelaLogin();
    } else {
      mostrarTelaLogin('Não foi possível conectar. Tente novamente.');
    }
  }
}

/* ---------- Login / logout ---------- */

function initFormLogin() {
  $('#form-login').addEventListener('submit', async (e) => {
    e.preventDefault();

    const login = $('#login-usuario').value.trim();
    const senha = $('#login-senha').value;
    const hint = $('#hint-login');

    if (!login || !senha) {
      hint.textContent = 'Preencha usuário e senha.';
      hint.hidden = false;
      return;
    }

    hint.hidden = true;

    try {
      await withLoading($('#btn-entrar'), () => api(AUTH.login, {
        method: 'POST',
        // A senha vai so nesta chamada e nunca e guardada em lugar nenhum.
        body: { login, senha },
      }));

      $('#form-login').reset();

      // Novos cookies (sessao + CSRF) chegaram; recarrega para saber quem entrou.
      const usuario = await api(AUTH.me);
      mostrarPainel(usuario);
      toast(`Bem-vindo, ${usuario.login}.`, 'ok');
    } catch (err) {
      // 401 no login e credencial errada, nao sessao expirada: nao redireciona.
      hint.textContent = err.status === 401
        ? 'Usuário ou senha inválidos.'
        : err.message;
      hint.hidden = false;
      $('#login-senha').value = '';
      $('#login-senha').focus();
    }
  });
}

/** Sai da sessao. `motivo` (opcional) aparece na tela de login.
 *  Encerra no SERVIDOR primeiro: se so limpassemos a tela, o cookie continuaria
 *  valido e um F5 traria o usuario de volta logado. */
async function sair(motivo) {
  try {
    // O logout e POST e esta sob /api/**, entao o backend exige o token CSRF.
    // Sem o header o servidor responde 403 e a sessao continua viva.
    await api(AUTH.logout, { method: 'POST' });
  } catch {
    // Se falhou (rede caiu, sessao ja expirou), seguimos com a saida local:
    // o usuario pediu para sair, nao vamos prende-lo na tela.
  } finally {
    state.usuario = null;
    limparEstado();
    clearTimeout(temporizadorInatividade);
    $('#tela-login').hidden = false;
    $('#app').hidden = true;
    $('#topbar-user').hidden = true;
    $('#btn-sair').hidden = true;
    $('#form-login').reset();
    const hint = $('#hint-login');
    if (motivo) {
      hint.textContent = motivo;
      hint.hidden = false;
    }
    $('#login-usuario').focus();
  }
}

/* ---------- Init ---------- */

function initDialogos() {
  // Botoes "Cancelar" dos dois dialogos.
  document.querySelectorAll('dialog [data-fechar]').forEach((btn) => {
    btn.addEventListener('click', fecharDialogos);
  });

  // <dialog> ja fecha no Esc; so precisamos limpar o estado junto.
  for (const id of DIALOGOS) {
    const d = $(id);
    if (d) d.addEventListener('close', () => {
      edicaoAtual = null; exclusaoAtual = null; desativacaoAtual = null;
    });
  }

  // Clicar no backdrop (fora da caixa) fecha.
  for (const id of DIALOGOS) {
    const d = $(id);
    if (!d) continue;
    d.addEventListener('click', (e) => {
      if (e.target === d) fecharDialogos();
    });
  }
}

function initTema() {
  const raiz = document.documentElement;
  try { const salvo = localStorage.getItem('tema'); if (salvo) raiz.dataset.theme = salvo; } catch { /* sem storage */ }

  $('#theme-toggle').addEventListener('click', () => {
    const escuro = raiz.dataset.theme
      ? raiz.dataset.theme === 'dark'
      : matchMedia('(prefers-color-scheme: dark)').matches;
    raiz.dataset.theme = escuro ? 'light' : 'dark';
    try { localStorage.setItem('tema', raiz.dataset.theme); } catch { /* sem storage */ }
  });
}

function init() {
  initTema();
  initTabs();
  initFormLogin();
  initFormAgendamento();
  initFormCliente();
  initFormVeiculo();
  initFormServico();
  initFormFuncionario();
  initDialogos();
  initSalvarEdicao();
  initConfirmarExclusao();
  initConfirmarDesativacao();

  $('#btn-sair').addEventListener('click', () => sair());

  $('#filtro-status').addEventListener('change', (e) => {
    state.filtroStatus = e.target.value;
    renderAgendamentos();
  });

  $('#filtro-periodo').addEventListener('change', (e) => {
    state.filtroPeriodo = e.target.value;
    renderAgendamentos();
  });

  // Qualquer interacao conta como atividade e adia o logout por inatividade.
  for (const evento of ['click', 'keydown', 'pointerdown']) {
    document.addEventListener(evento, renovarInatividade, { passive: true });
  }

  // Revalida ao voltar para a aba (dados e sessao podem ter mudado).
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    renovarInatividade();
    if (state.usuario) carregarTudo();
  });

  iniciarSessao();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
