package br.com.lavajato.controller;

import br.com.lavajato.model.Veiculo;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.repository.VeiculoRepository;
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
@RequestMapping("/api/veiculos")
public class VeiculoController {

    private final VeiculoRepository veiculoRepository;
    private final AgendamentoRepository agendamentoRepository;

    public VeiculoController(VeiculoRepository veiculoRepository, AgendamentoRepository agendamentoRepository) {
        this.veiculoRepository = veiculoRepository;
        this.agendamentoRepository = agendamentoRepository;
    }

    @GetMapping
    public List<Veiculo> listar() {
        return veiculoRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Veiculo> buscar(@PathVariable Long id) {
        return veiculoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Veiculo salvar(@RequestBody Veiculo veiculo) {
        return veiculoRepository.save(veiculo);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Veiculo> atualizar(@PathVariable Long id, @RequestBody Veiculo dados) {
        return veiculoRepository.findById(id)
                .map(veiculo -> {
                    veiculo.setPlaca(dados.getPlaca());
                    veiculo.setModelo(dados.getModelo());
                    veiculo.setCliente(dados.getCliente());
                    return ResponseEntity.ok(veiculoRepository.save(veiculo));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        if (!veiculoRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Sem isto o banco devolveria erro de chave estrangeira (500) sem explicacao.
        long agendamentos = agendamentoRepository.countByVeiculoId(id);
        if (agendamentos > 0) {
            return ResponseEntity.status(409).build();
        }

        veiculoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
