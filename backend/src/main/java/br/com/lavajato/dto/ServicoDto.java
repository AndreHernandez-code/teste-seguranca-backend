package br.com.lavajato.dto;

import br.com.lavajato.model.Servico;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Contrato da API para Servico. Ver ClienteDto para o porque de existir. */
public final class ServicoDto {

    public record ServicoEntrada(
            @NotBlank(message = "Informe o nome do serviço.")
            @Size(max = 120, message = "O nome deve ter no maximo 120 caracteres.")
            String nome,

            // Teto de R$ 100.000,00: nenhuma lavagem chega perto, e o limite
            // impede que um erro de digitacao (ou um valor gigante de proposito)
            // entre no banco e distorca o faturamento somado na tela.
            @NotNull(message = "Informe o preço.")
            @PositiveOrZero(message = "O preço não pode ser negativo.")
            @DecimalMax(value = "100000.00", message = "O preço deve ser no máximo R$ 100.000,00.")
            BigDecimal preco,

            // Teto de 24h: um servico que dura mais que um dia util nao existe
            // na operacao, e o valor tambem entra na conta de agenda.
            @NotNull(message = "Informe a duração.")
            @Positive(message = "A duração deve ser maior que zero.")
            @Max(value = 1440, message = "A duração deve ser de no máximo 1440 minutos (24 horas).")
            Integer tempoEstimado) {
    }

    public record ServicoSaida(Long id, String nome, BigDecimal preco, Integer tempoEstimado) {

        public static ServicoSaida de(Servico s) {
            return new ServicoSaida(s.getId(), s.getNome(), s.getPreco(), s.getTempoEstimado());
        }
    }

    private ServicoDto() {
    }
}
