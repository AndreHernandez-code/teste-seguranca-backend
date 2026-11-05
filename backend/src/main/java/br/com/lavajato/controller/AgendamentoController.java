package br.com.lavajato.controller;

import br.com.lavajato.model.Agendamento;
import br.com.lavajato.repository.AgendamentoRepository;
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
@RequestMapping("/api/agendamentos")
public class AgendamentoController {

    private final AgendamentoRepository agendamentoRepository;

    public AgendamentoController(AgendamentoRepository agendamentoRepository) {
        this.agendamentoRepository = agendamentoRepository;
    }

    @GetMapping
    public List<Agendamento> listar() {
        return agendamentoRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agendamento> buscar(@PathVariable Long id) {
        return agendamentoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Agendamento salvar(@RequestBody Agendamento agendamento) {
        return agendamentoRepository.save(agendamento);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Agendamento> atualizar(@PathVariable Long id, @RequestBody Agendamento dados) {
        return agendamentoRepository.findById(id)
                .map(agendamento -> {
                    agendamento.setDataHora(dados.getDataHora());
                    agendamento.setStatus(dados.getStatus());
                    agendamento.setVeiculo(dados.getVeiculo());
                    agendamento.setServico(dados.getServico());
                    return ResponseEntity.ok(agendamentoRepository.save(agendamento));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        if (!agendamentoRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        agendamentoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
