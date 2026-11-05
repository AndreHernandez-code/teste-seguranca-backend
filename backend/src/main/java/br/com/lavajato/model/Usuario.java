package br.com.lavajato.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login unico. Guardado em minusculas para o login nao depender de maiusculas. */
    @NotBlank(message = "Informe o login.")
    @Size(min = 3, max = 60, message = "O login deve ter entre 3 e 60 caracteres.")
    @Column(nullable = false, unique = true, length = 60)
    private String login;

    /**
     * Hash BCrypt da senha. Nunca guardamos nem devolvemos a senha em texto puro.
     * length 100 acomoda o formato $2a$10$... (~60 chars) com folga.
     */
    @Column(nullable = false, length = 100)
    private String senha;

    @NotBlank(message = "Informe o nome do funcionario.")
    @Size(max = 120, message = "O nome deve ter no maximo 120 caracteres.")
    @Column(nullable = false, length = 120)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Perfil perfil;

    @Column(nullable = false)
    private boolean ativo = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getSenha() {
        return senha;
    }

    public void setSenha(String senha) {
        this.senha = senha;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Perfil getPerfil() {
        return perfil;
    }

    public void setPerfil(Perfil perfil) {
        this.perfil = perfil;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
