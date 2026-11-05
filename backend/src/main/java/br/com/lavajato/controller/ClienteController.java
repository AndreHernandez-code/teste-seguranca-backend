package br.com.lavajato.controller;

import br.com.lavajato.dto.ClienteDto.ClienteEntrada;
import br.com.lavajato.dto.ClienteDto.ClienteSaida;
import br.com.lavajato.model.Cliente;
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
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteRepository clienteRepository;
    private final VeiculoRepository veiculoRepository;

    public ClienteController(ClienteRepository clienteRepository, VeiculoRepository veiculoRepository) {
        this.clienteRepository = clienteRepository;
        this.veiculoRepository = veiculoRepository;
    }

    @GetMapping
    public List<ClienteSaida> listar() {
        return clienteRepository.findAll().stream().map(ClienteSaida::de).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClienteSaida> buscar(@PathVariable Long id) {
        return clienteRepository.findById(id)
                .map(ClienteSaida::de)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClienteSaida salvar(@Valid @RequestBody ClienteEntrada dados) {
        return ClienteSaida.de(clienteRepository.save(paraEntidade(dados)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ClienteSaida> atualizar(@PathVariable Long id, @Valid @RequestBody ClienteEntrada dados) {
        return clienteRepository.findById(id)
                .map(cliente -> {
                    cliente.setNome(dados.nome());
                    cliente.setTelefone(dados.telefone());
                    return ResponseEntity.ok(ClienteSaida.de(clienteRepository.save(cliente)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        if (!clienteRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Sem isto o banco devolveria erro de chave estrangeira (500) sem explicacao.
        long veiculos = veiculoRepository.countByClienteId(id);
        if (veiculos > 0) {
            throw new RegraNegocioException("Não é possível excluir: este cliente ainda tem veículos cadastrados.");
        }

        clienteRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Monta a entidade a partir do DTO. O id fica de fora de proposito: quem
     * cria um registro nao escolhe o proprio id - quem escolhe e o banco.
     */
    private Cliente paraEntidade(ClienteEntrada dados) {
        Cliente cliente = new Cliente();
        cliente.setNome(dados.nome());
        cliente.setTelefone(dados.telefone());
        return cliente;
    }
}
