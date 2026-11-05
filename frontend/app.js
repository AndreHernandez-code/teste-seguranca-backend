/* ============================================================
   Lava-Jato - Painel de agendamentos
   Consome a API do backend Spring Boot.
   ============================================================ */

'use strict';

/* ---------- Configuracao ---------- */

// Se o frontend for servido pelo proprio Spring Boot (http://localhost:8080),
// API_BASE fica vazio e as chamadas vao para a mesma origem.
// Se estiver no Live Server (porta 5500), aponta para o backend.
const API_BASE = (location.port === '8080' || location.protocol === 'file:')
  ? ''
  : 'http://localhost:8080';

const ENDPOINTS = {
  agendamentos: '/api/agendamentos',
  clientes:     '/api/clientes',
  veiculos:     '/api/veiculos',
  servicos:     '/api/servicos',
};

const STATUS_LABEL = {
  AGUARDANDO:   'Aguardando',
  EM_ANDAMENTO: 'Em andamento',
  CONCLUIDO:    'Concluído',
  CANCELADO:    'Cancelado',
};

/* ---------- Estado ---------- */

const state = {
  agendamentos: [],
  clientes:     [],
  veiculos:     [],
  servicos:     [],
  filtroStatus: '',
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

/* ---------- Camada HTTP ---------- */

async function api(path, options = {}) {
  const { method = 'GET', body } = options;

  let res;
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (err) {
    // fetch so rejeita por falha de rede - o backend provavelmente esta parado.
    throw new Error(
      `Não foi possível falar com o servidor (${API_BASE || location.origin}). ` +
      'Verifique se o backend está rodando.'
    );
  }

  if (!res.ok) {
    const detalhe = await res.text().catch(() => '');
    throw new Error(`Erro ${res.status} em ${method} ${path}${detalhe ? ` — ${detalhe.slice(0, 200)}` : ''}`);
  }

  if (res.status === 204) return null;
  return res.json();
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

  const nomes = Object.keys(ENDPOINTS);
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
      falhas.push(`${nome}: ${resultado.reason.message}`);
    }
  });

  renderTudo();

  if (falhas.length === 0) {
    setStatus('ok', 'Conectado');
  } else if (falhas.length === nomes.length) {
    setStatus('error', 'Sem conexão');
    toast('Não foi possível carregar os dados. O backend está rodando em ' +
          `${API_BASE || location.origin}?`, 'error', 9000);
  } else {
    setStatus('error', 'Carga parcial');
    toast(`Alguns dados não carregaram — ${falhas.join('; ')}`, 'error', 9000);
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
function acoesCell(recurso, item, descricao) {
  const td = el('td', 'acoes');

  const editar = el('button', 'icon-btn', 'Editar');
  editar.type = 'button';
  editar.setAttribute('aria-label', `Editar ${descricao}`);
  editar.addEventListener('click', () => abrirEdicao(recurso, item));

  const excluir = el('button', 'icon-btn icon-btn--danger', 'Excluir');
  excluir.type = 'button';
  excluir.setAttribute('aria-label', `Excluir ${descricao}`);
  excluir.addEventListener('click', () => abrirExclusao(recurso, item, descricao));

  td.append(editar, excluir);
  return td;
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

  if (lista.length === 0) {
    tbody.append(emptyRow(
      7,
      state.agendamentos.length === 0
        ? 'Nenhum agendamento cadastrado ainda.'
        : 'Nenhum agendamento com esse status.'
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

    const tdValor = el('td', 'num', money.format(ag.servico?.preco ?? 0));

    const tdStatus = el('td');
    tdStatus.append(el(
      'span',
      `badge badge--${status.toLowerCase()}`,
      STATUS_LABEL[status] ?? status
    ));

    const descricaoAg = `${data} ${hora} — ${ag.servico?.nome ?? 'serviço'}`;
    tr.append(
      tdData, tdCliente, tdVeiculo, tdServico, tdValor, tdStatus,
      acoesCell('agendamentos', ag, descricaoAg)
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

function renderStats() {
  const ativos = state.agendamentos.filter((a) => a.status !== 'CANCELADO');

  $('#stat-agendamentos-total').textContent = String(state.agendamentos.length);
  $('#stat-agendamentos-aguardando').textContent =
    String(state.agendamentos.filter((a) => (a.status || 'AGUARDANDO') === 'AGUARDANDO').length);

  const faturamento = ativos.reduce((soma, a) => soma + Number(a.servico?.preco ?? 0), 0);
  $('#stat-agendamentos-faturamento').textContent = money.format(faturamento);
}

function renderTudo() {
  renderSelects();
  renderStats();
  renderAgendamentos();
  renderClientes();
  renderVeiculos();
  renderServicos();
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
      const cliente = state.clientes.find((c) => String(c.id) === String(dados.cliente));
      if (!cliente) throw new Error('Selecione um cliente válido.');
      return {
        id: item.id,
        placa: dados.placa.trim().toUpperCase(),
        modelo: dados.modelo.trim(),
        cliente,
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
      const veiculo = state.veiculos.find((v) => String(v.id) === String(dados.veiculo));
      const servico = state.servicos.find((s) => String(s.id) === String(dados.servico));
      if (!veiculo) throw new Error('Selecione um veículo válido.');
      if (!servico) throw new Error('Selecione um serviço válido.');

      return {
        id: item.id,
        dataHora: `${dados.data}T${dados.hora}:00`,
        status: dados.status,
        veiculo,
        servico,
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
    }[campo.tipo];

    const vazio = el('option', null, 'Selecione…');
    vazio.value = '';
    input.append(vazio);

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

function fecharDialogos() {
  for (const id of ['#dialog-editar', '#dialog-excluir']) {
    const d = $(id);
    if (d?.open) d.close();
  }
  edicaoAtual = null;
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
      if (/409/.test(err.message)) {
        toast(
          'Não é possível excluir: existem registros ligados a este item. ' +
          'Exclua primeiro os que dependem dele.',
          'error', 9000
        );
      } else {
        toast(err.message, 'error', 8000);
      }
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

    const veiculo = state.veiculos.find((v) => String(v.id) === veiculoId);
    const servico = state.servicos.find((s) => String(s.id) === servicoId);

    if (!veiculo || !servico) {
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
          // Enviar o objeto completo evita erro de desserializacao no @ManyToOne.
          veiculo,
          servico,
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
    const placa = $('#ve-placa').value.trim().toUpperCase();
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

    const cliente = state.clientes.find((c) => String(c.id) === clienteId);
    if (!cliente) {
      showHint('hint-veiculo', 'Cliente inválido. Recarregue a página.', 'error');
      return;
    }

    showHint('hint-veiculo', '');

    try {
      await withLoading($('#btn-veiculo'), () => api(ENDPOINTS.veiculos, {
        method: 'POST',
        body: { placa, modelo, cliente },
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

/* ---------- Init ---------- */

function initDialogos() {
  // Botoes "Cancelar" dos dois dialogos.
  document.querySelectorAll('dialog [data-fechar]').forEach((btn) => {
    btn.addEventListener('click', fecharDialogos);
  });

  // <dialog> ja fecha no Esc; so precisamos limpar o estado junto.
  for (const id of ['#dialog-editar', '#dialog-excluir']) {
    const d = $(id);
    if (d) d.addEventListener('close', () => { edicaoAtual = null; exclusaoAtual = null; });
  }

  // Clicar no backdrop (fora da caixa) fecha.
  for (const id of ['#dialog-editar', '#dialog-excluir']) {
    const d = $(id);
    if (!d) continue;
    d.addEventListener('click', (e) => {
      if (e.target === d) fecharDialogos();
    });
  }
}

function init() {
  initTabs();
  initFormAgendamento();
  initFormCliente();
  initFormVeiculo();
  initFormServico();
  initDialogos();
  initSalvarEdicao();
  initConfirmarExclusao();

  $('#filtro-status').addEventListener('change', (e) => {
    state.filtroStatus = e.target.value;
    renderAgendamentos();
  });

  carregarTudo();

  // Revalida ao voltar para a aba (dados podem ter mudado no banco).
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) carregarTudo();
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
