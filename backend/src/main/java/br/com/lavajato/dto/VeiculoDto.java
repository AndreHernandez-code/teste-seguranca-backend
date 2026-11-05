package br.com.lavajato.dto;

import br.com.lavajato.model.Veiculo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Contrato da API para Veiculo.
 *
 * ATENCAO ao campo "cliente": ele e um OBJETO com id, nao um numero solto.
 * O front envia {"placa":"ABC1D23","modelo":"Fiat Argo","cliente":{"id":1}}.
 * Trocar por "clienteId":1 mudaria o contrato e quebraria o front - por isso
 * a referencia e um record proprio, e nao um Long.
 */
public final class VeiculoDto {

    /** Referencia a outro recurso: apenas o id. Ver a nota acima. */
    public record ReferenciaCliente(@NotNull(message = "Selecione o cliente.") Long id) {
    }

    public record VeiculoEntrada(
            @NotBlank(message = "Informe a placa.")
            @Size(max = 8, message = "A placa deve ter no maximo 8 caracteres.")
            @Pattern(regexp = "^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$",
                    message = "Placa inválida (ex.: ABC1D23 ou ABC1234).")
            String placa,

            @NotBlank(message = "Informe o modelo.")
            @Size(max = 120, message = "O modelo deve ter no maximo 120 caracteres.")
            String modelo,

            // @Valid faz a validacao DESCER para dentro do record aninhado.
            // Sem ele, um cliente com id nulo passaria pela validacao e so
            // falharia depois, no banco.
            @NotNull(message = "Selecione o cliente.")
            @Valid
            ReferenciaCliente cliente) {
    }

    /**
     * A saida traz o cliente COMPLETO, nao so o id - e o que o front le hoje
     * (v.cliente?.nome na tabela e nos selects).
     */
    public record VeiculoSaida(Long id, String placa, String modelo, ClienteDto.ClienteSaida cliente) {

        public static VeiculoSaida de(Veiculo v) {
            return new VeiculoSaida(
                    v.getId(),
                    v.getPlaca(),
                    v.getModelo(),
                    v.getCliente() == null ? null : ClienteDto.ClienteSaida.de(v.getCliente()));
        }
    }

    private VeiculoDto() {
    }
}
