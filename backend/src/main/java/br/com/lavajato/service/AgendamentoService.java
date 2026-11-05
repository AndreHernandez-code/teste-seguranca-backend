package br.com.lavajato.service;

import br.com.lavajato.dto.AgendamentoDto.AgendamentoEntrada;
import br.com.lavajato.exception.RegraNegocioException;
import br.com.lavajato.model.Agendamento;
import br.com.lavajato.model.Servico;
import br.com.lavajato.model.StatusAgendamento;
import br.com.lavajato.model.Veiculo;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.repository.ServicoRepository;
import br.com.lavajato.repository.VeiculoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgendamentoService {

    private final AgendamentoRepository agendamentos;
    private final VeiculoRepository veiculos;
    private final ServicoRepository servicos;

    public AgendamentoService(AgendamentoRepository agendamentos, VeiculoRepository veiculos,
                              ServicoRepository servicos) {
        this.agendamentos = agendamentos;
        this.veiculos = veiculos;
        this.servicos = servicos;
    }

    @Transactional
    public Agendamento criar(AgendamentoEntrada dados) {
        return gravar(new Agendamento(), dados);
    }

    @Transactional
    public Agendamento atualizar(Long id, AgendamentoEntrada dados) {
        Agendamento atual = agendamentos.findById(id)
                .orElseThrow(() -> new RegraNegocioException(HttpStatus.NOT_FOUND, "Agendamento não encontrado."));
        return gravar(atual, dados);
    }

    private Agendamento gravar(Agendamento alvo, AgendamentoEntrada dados) {
        // Buscamos pelo id no banco em vez de confiar no objeto aninhado vindo
        // do cliente: aceitar o objeto deixaria o cliente escolher os campos
        // da associacao. O @Valid do DTO ja garante que a referencia e o id
        // existem, mas os getters sao chamados aqui de novo - por isso a
        // checagem defensiva, para um JSON sem "veiculo" virar 422 e nao 500.
        if (dados.veiculo() == null || dados.veiculo().id() == null) {
            throw new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Veículo inválido.");
        }
        if (dados.servico() == null || dados.servico().id() == null) {
            throw new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Serviço inválido.");
        }

        Veiculo veiculo = buscarVeiculo(dados.veiculo().id());
        Servico servico = buscarServico(dados.servico().id());
        StatusAgendamento status = dados.status() != null ? dados.status() : StatusAgendamento.AGUARDANDO;

        if (status != StatusAgendamento.CANCELADO) {
            Long ignorar = alvo.getId() != null ? alvo.getId() : -1L;
            if (agendamentos.existeConflito(veiculo.getId(), dados.dataHora(), StatusAgendamento.CANCELADO, ignorar)) {
                throw new RegraNegocioException("Este veículo já tem um agendamento nesse horário.");
            }
        }

        // O preco NUNCA vem do cliente: e sempre o do servico, ou o que ja
        // estava gravado quando o servico nao muda. Era o furo do mass
        // assignment - a entidade aceitava "precoCobrado" no corpo do PUT.
        boolean mudouServico = alvo.getServico() == null || !alvo.getServico().getId().equals(servico.getId());
        if (alvo.getPrecoCobrado() == null || mudouServico) {
            alvo.setPrecoCobrado(servico.getPreco());
        }

        alvo.setDataHora(dados.dataHora());
        alvo.setStatus(status);
        alvo.setVeiculo(veiculo);
        alvo.setServico(servico);
        return agendamentos.save(alvo);
    }

    private Veiculo buscarVeiculo(Long id) {
        if (id == null) throw new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Veículo inválido.");
        return veiculos.findById(id)
                .orElseThrow(() -> new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Veículo não encontrado."));
    }

    private Servico buscarServico(Long id) {
        if (id == null) throw new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Serviço inválido.");
        return servicos.findById(id)
                .orElseThrow(() -> new RegraNegocioException(HttpStatus.UNPROCESSABLE_ENTITY, "Serviço não encontrado."));
    }
}
