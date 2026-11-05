package br.com.lavajato.dto;

import br.com.lavajato.model.Cliente;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contrato da API para Cliente: o que entra e o que sai.
 *
 * Por que DTO e nao a entidade direto:
 *  - ENTRADA: se a entidade viesse no @RequestBody, o cliente escolheria o
 *    proprio "id" e qualquer campo interno que viesse a existir.
 *  - SAIDA: a entidade sai inteira, entao todo campo novo vira campo publico
 *    sem ninguem decidir isso. Aqui o que sai e uma escolha explicita.
 *
 * Estes records existem justamente para que a lista de campos publicos seja
 * lida de relance, num arquivo so.
 */
public final class ClienteDto {

    /**
     * O que a API aceita. Os NOMES dos campos nao podem mudar: o front monta
     * o JSON com eles, e o GlobalExceptionHandler usa estes nomes como chave
     * em "campos" quando a validacao falha.
     */
    public record ClienteEntrada(
            @NotBlank(message = "Informe o nome do cliente.")
            @Size(max = 120, message = "O nome deve ter no maximo 120 caracteres.")
            String nome,

            @NotBlank(message = "Informe o telefone.")
            @Size(max = 20, message = "O telefone deve ter no maximo 20 caracteres.")
            String telefone) {
    }

    /**
     * O que a API devolve. Mesmos campos e mesma forma de hoje - trocar isto
     * quebra o front, que le exatamente id/nome/telefone.
     */
    public record ClienteSaida(Long id, String nome, String telefone) {

        public static ClienteSaida de(Cliente c) {
            return new ClienteSaida(c.getId(), c.getNome(), c.getTelefone());
        }
    }

    private ClienteDto() {
    }
}
