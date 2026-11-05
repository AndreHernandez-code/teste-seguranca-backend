package br.com.lavajato.repository;

import br.com.lavajato.model.Perfil;
import br.com.lavajato.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByLogin(String login);

    boolean existsByLogin(String login);

    /** Usado para impedir que o sistema fique sem nenhum ADMIN ativo. */
    long countByPerfilAndAtivoTrue(Perfil perfil);
}
