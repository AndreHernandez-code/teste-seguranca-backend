package br.com.lavajato.dto;

import br.com.lavajato.model.Agendamento;
import br.com.lavajato.model.StatusAgendamento;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Contrato da API para Agendamento.
 *
 * O ponto central deste DTO: "precoCobrado" NAO existe na entrada.
 *
 * Antes, a entidade vinha no @RequestBody e trazia o campo. Como o service so
 * recalcula o preco quando o SERVICO muda, um PUT que mantinha o mesmo servico
 * aceitava qualquer valor enviado pelo cliente e gravava. Agora o preco e
 * sempre calculado pelo servidor: o cliente nao tem como influenciar.
 *
 * Veiculo e servico seguem o mesmo formato aninhado de VeiculoDto: objeto com
 * id, nunca um Long solto, para o contrato com o front nao mudar.
 */
public final class AgendamentoDto {

    public record ReferenciaVeiculo(@NotNull(message = "Selecione o veículo.") Long id) {
    }

    public record ReferenciaServico(@NotNull(message = "Selecione o serviço.") Long id) {
    }

    public record AgendamentoEntrada(
            @NotNull(message = "Informe a data e a hora.")
            LocalDateTime dataHora,

            // Opcional de proposito: sem status, o service assume AGUARDANDO.
            // E o que o front faz hoje ao criar um agendamento.
            StatusAgendamento status,

            @NotNull(message = "Selecione o veículo.")
            @Valid
            ReferenciaVeiculo veiculo,

            @NotNull(message = "Selecione o serviço.")
            @Valid
            ReferenciaServico servico) {
    }

    /**
     * Saida identica a de hoje, inclusive "precoCobrado" quando for null.
     *
     * NAO acrescentar @JsonInclude(NON_NULL) aqui: o front faz
     * `ag.precoCobrado ?? ag.servico?.preco ?? 0`. Se o campo sumisse do JSON
     * quando nulo, a tela mostraria o preco do SERVICO no lugar do preco
     * cobrado, silenciosamente, sempre que os dois diferissem.
     */
    public record AgendamentoSaida(Long id,
                                   LocalDateTime dataHora,
                                   StatusAgendamento status,
                                   BigDecimal precoCobrado,
                                   VeiculoDto.VeiculoSaida veiculo,
                                   ServicoDto.ServicoSaida servico) {

        public static AgendamentoSaida de(Agendamento a) {
            return new AgendamentoSaida(
                    a.getId(),
                    a.getDataHora(),
                    a.getStatus(),
                    a.getPrecoCobrado(),
                    a.getVeiculo() == null ? null : VeiculoDto.VeiculoSaida.de(a.getVeiculo()),
                    a.getServico() == null ? null : ServicoDto.ServicoSaida.de(a.getServico()));
        }
    }

    private AgendamentoDto() {
    }
}
