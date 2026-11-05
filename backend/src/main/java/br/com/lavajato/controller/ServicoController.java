package br.com.lavajato.controller;

import br.com.lavajato.dto.ServicoDto.ServicoEntrada;
import br.com.lavajato.dto.ServicoDto.ServicoSaida;
import br.com.lavajato.model.Servico;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.repository.ServicoRepository;
import br.com.lavajato.exception.RegraNegocioException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servicos")
public class ServicoController {

    private final ServicoRepository servicoRepository;
    private final AgendamentoRepository agendamentoRepository;

    public ServicoController(ServicoRepository servicoRepository, AgendamentoRepository agendamentoRepository) {
        this.servicoRepository = servicoRepository;
        this.agendamentoRepository = agendamentoRepository;
    }

    @GetMapping
    public List<ServicoSaida> listar() {
        return servicoRepository.findAll().stream().map(ServicoSaida::de).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServicoSaida> buscar(@PathVariable Long id) {
        return servicoRepository.findById(id)
                .map(ServicoSaida::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServicoSaida salvar(@Valid @RequestBody ServicoEntrada dados) {
        return ServicoSaida.de(servicoRepository.save(paraEntidade(dados)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServicoSaida> atualizar(@PathVariable Long id, @Valid @RequestBody ServicoEntrada dados) {
        return servicoRepository.findById(id)
                .map(servico -> {
                    servico.setNome(dados.nome());
                    servico.setPreco(dados.preco());
                    servico.setTempoEstimado(dados.tempoEstimado());
                    return ResponseEntity.ok(ServicoSaida.de(servicoRepository.save(servico)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        if (!servicoRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Sem isto o banco devolveria erro de chave estrangeira (500) sem explicacao.
        long agendamentos = agendamentoRepository.countByServicoId(id);
        if (agendamentos > 0) {
            throw new RegraNegocioException("Não é possível excluir: este serviço já foi usado em agendamentos.");
        }

        servicoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Monta a entidade a partir do DTO. O id fica de fora: quem escolhe e o banco. */
    private Servico paraEntidade(ServicoEntrada dados) {
        Servico servico = new Servico();
        servico.setNome(dados.nome());
        servico.setPreco(dados.preco());
        servico.setTempoEstimado(dados.tempoEstimado());
        return servico;
    }
}
