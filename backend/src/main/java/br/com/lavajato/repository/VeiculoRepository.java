package br.com.lavajato.repository;

import br.com.lavajato.model.Veiculo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VeiculoRepository extends JpaRepository<Veiculo, Long> {

    /** Usado para impedir excluir um cliente que ainda tem veiculos. */
    long countByClienteId(Long clienteId);
}
