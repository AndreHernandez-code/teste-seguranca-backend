package br.com.lavajato.security;

import br.com.lavajato.model.Usuario;
import br.com.lavajato.repository.UsuarioRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Carrega o funcionario do banco para o Spring Security autenticar.
 *
 * O perfil vira a authority "ROLE_ADMIN" / "ROLE_ATENDENTE" - o prefixo ROLE_
 * e exigido pelo hasRole() nas regras de autorizacao.
 */
@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository usuarios;

    public UsuarioDetailsService(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        Usuario usuario = usuarios.findByLogin(login)
                // Mensagem generica: nao revela se o login existe.
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais invalidas."));

        return User.withUsername(usuario.getLogin())
                .password(usuario.getSenha())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getPerfil().name())))
                .disabled(!usuario.isAtivo())
                .build();
    }
}
