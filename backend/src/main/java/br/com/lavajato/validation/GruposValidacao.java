package br.com.lavajato.validation;

/**
 * Grupos de validacao para reaproveitar o mesmo DTO na criacao e na edicao,
 * onde a obrigatoriedade de alguns campos muda.
 *
 * Caso concreto: ao criar um usuario a senha e obrigatoria; ao editar, deixar
 * a senha em branco significa "manter a atual". Sem grupos, um unico @NotBlank
 * obrigaria o operador a redigitar a senha so para corrigir o nome.
 */
public final class GruposValidacao {

    /** Validacoes aplicadas apenas na criacao. */
    public interface Criacao {
    }

    /** Validacoes aplicadas apenas na edicao. */
    public interface Edicao {
    }

    private GruposValidacao() {
    }
}
