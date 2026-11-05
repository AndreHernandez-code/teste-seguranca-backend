package br.com.lavajato.repository;

import br.com.lavajato.model.Agendamento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {

    /** Usado para impedir excluir um veiculo que ainda tem agendamentos. */
    long countByVeiculoId(Long veiculoId);

    /** Usado para impedir excluir um servico que ainda tem agendamentos. */
    long countByServicoId(Long servicoId);
}
