package br.com.lavajato.controller;

import br.com.lavajato.dto.VeiculoDto.VeiculoEntrada;
import br.com.lavajato.dto.VeiculoDto.VeiculoSaida;
import br.com.lavajato.model.Cliente;
import br.com.lavajato.model.Veiculo;
import br.com.lavajato.repository.AgendamentoRepository;
import br.com.lavajato.repository.ClienteRepository;
import br.com.lavajato.repository.VeiculoRepository;
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
@RequestMapping("/api/veiculos")
public class VeiculoController {

    private final VeiculoRepository veiculoRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final ClienteRepository clienteRepository;

    public VeiculoController(VeiculoRepository veiculoRepository, AgendamentoRepository agendamentoRepository,
                             ClienteRepository clienteRepository) {
        this.veiculoRepository = veiculoRepository;
        this.agendamentoRepository = agendamentoRepository;
        this.clienteRepository = clienteRepository;
    }

    @GetMapping
    public List<VeiculoSaida> listar() {
        return veiculoRepository.findAll().stream().map(VeiculoSaida::de).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VeiculoSaida> buscar(@PathVariable Long id) {
        return veiculoRepository.findById(id)
                .map(VeiculoSaida::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VeiculoSaida salvar(@Valid @RequestBody VeiculoEntrada dados) {
        return VeiculoSaida.de(veiculoRepository.save(paraEntidade(dados)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VeiculoSaida> atualizar(@PathVariable Long id, @Valid @RequestBody VeiculoEntrada dados) {
        return veiculoRepository.findById(id)
                .map(veiculo -> {
                    veiculo.setPlaca(dados.placa());
                    veiculo.setModelo(dados.modelo());
                    veiculo.setCliente(buscarCliente(dados.cliente().id()));
                    return ResponseEntity.ok(VeiculoSaida.de(veiculoRepository.save(veiculo)));
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
            throw new RegraNegocioException("Não é possível excluir: este veículo ainda tem agendamentos.");
        }

        veiculoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Monta a entidade a partir do DTO. O id fica de fora: quem escolhe e o banco. */
    private Veiculo paraEntidade(VeiculoEntrada dados) {
        Veiculo veiculo = new Veiculo();
        veiculo.setPlaca(dados.placa());
        veiculo.setModelo(dados.modelo());
        veiculo.setCliente(buscarCliente(dados.cliente().id()));
        return veiculo;
    }

    /**
     * Resolve a referencia pelo id NO BANCO, em vez de confiar no objeto que
     * veio no JSON. Aceitar o objeto aninhado deixaria o cliente mandar um
     * cliente com id falso e nome inventado, e a associacao apontaria para o
     * nada. 422 (e nao 404) porque o veiculo em si existe - o que nao existe
     * e a referencia dentro dele.
     */
    private Cliente buscarCliente(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new RegraNegocioException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Cliente não encontrado."));
    }
}
