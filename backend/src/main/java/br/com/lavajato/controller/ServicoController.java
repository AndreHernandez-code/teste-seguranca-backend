package br.com.lavajato.controller;

import br.com.lavajato.model.Servico;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.repository.ServicoRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public List<Servico> listar() {
        return servicoRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Servico> buscar(@PathVariable Long id) {
        return servicoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Servico salvar(@RequestBody Servico servico) {
        return servicoRepository.save(servico);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Servico> atualizar(@PathVariable Long id, @RequestBody Servico dados) {
        return servicoRepository.findById(id)
                .map(servico -> {
                    servico.setNome(dados.getNome());
                    servico.setPreco(dados.getPreco());
                    servico.setTempoEstimado(dados.getTempoEstimado());
                    return ResponseEntity.ok(servicoRepository.save(servico));
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
            return ResponseEntity.status(409).build();
        }

        servicoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
