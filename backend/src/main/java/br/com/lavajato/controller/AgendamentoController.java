package br.com.lavajato.controller;

import br.com.lavajato.dto.AgendamentoDto.AgendamentoEntrada;
import br.com.lavajato.dto.AgendamentoDto.AgendamentoSaida;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.service.AgendamentoService;
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
@RequestMapping("/api/agendamentos")
public class AgendamentoController {

    private final AgendamentoRepository agendamentoRepository;
    private final AgendamentoService agendamentoService;

    public AgendamentoController(AgendamentoRepository agendamentoRepository, AgendamentoService agendamentoService) {
        this.agendamentoRepository = agendamentoRepository;
        this.agendamentoService = agendamentoService;
    }

    @GetMapping
    public List<AgendamentoSaida> listar() {
        return agendamentoRepository.findAll().stream().map(AgendamentoSaida::de).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgendamentoSaida> buscar(@PathVariable Long id) {
        return agendamentoRepository.findById(id)
                .map(AgendamentoSaida::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgendamentoSaida salvar(@Valid @RequestBody AgendamentoEntrada dados) {
        return AgendamentoSaida.de(agendamentoService.criar(dados));
    }

    @PutMapping("/{id}")
    public AgendamentoSaida atualizar(@PathVariable Long id, @Valid @RequestBody AgendamentoEntrada dados) {
        return AgendamentoSaida.de(agendamentoService.atualizar(id, dados));
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
