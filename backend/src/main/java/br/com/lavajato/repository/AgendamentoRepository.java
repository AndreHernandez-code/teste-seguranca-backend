package br.com.lavajato.repository;

import br.com.lavajato.model.Agendamento;
import br.com.lavajato.model.StatusAgendamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {

    /** Usado para impedir excluir um veiculo que ainda tem agendamentos. */
    long countByVeiculoId(Long veiculoId);

    /** Usado para impedir excluir um servico que ainda tem agendamentos. */
    long countByServicoId(Long servicoId);

    /** Mesmo veículo, mesmo horário, agendamento ainda ativo (ignora o próprio registro ao editar). */
    @Query("select count(a) > 0 from Agendamento a where a.veiculo.id = :veiculoId "
            + "and a.dataHora = :dataHora and a.status <> :cancelado and a.id <> :ignorarId")
    boolean existeConflito(@Param("veiculoId") Long veiculoId,
                           @Param("dataHora") LocalDateTime dataHora,
                           @Param("cancelado") StatusAgendamento cancelado,
                           @Param("ignorarId") Long ignorarId);
}
