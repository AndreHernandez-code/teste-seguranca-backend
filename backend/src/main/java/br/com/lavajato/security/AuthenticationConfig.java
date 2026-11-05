package br.com.lavajato.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Expoe o AuthenticationManager usado pelo AuthController para autenticar no login.
 * Usa o UsuarioDetailsService (banco) + BCrypt.
 */
@Configuration
public class AuthenticationConfig {

    @Bean
    public AuthenticationManager authenticationManager(UsuarioDetailsService detailsService,
                                                       PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(detailsService);
        provider.setPasswordEncoder(encoder);
        // Nao revela se o login existe quando o usuario nao e encontrado.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }
}
