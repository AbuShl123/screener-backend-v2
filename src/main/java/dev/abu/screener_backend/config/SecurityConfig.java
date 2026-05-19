package dev.abu.screener_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security configuration that permits all requests unconditionally.
 *
 * <p><strong>This class is temporary.</strong> JWT-based authentication and role-based
 * authorization will replace this configuration in a future phase once the core data
 * pipeline is stable. CSRF is disabled because this API is consumed by programmatic
 * clients, not browser forms.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Permits all incoming requests without authentication.
     *
     * @param http Spring Security HTTP builder
     * @return configured security filter chain
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
