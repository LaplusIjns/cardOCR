package com.github.laplusijns.auth;

import static com.vaadin.flow.spring.security.VaadinSecurityConfigurer.vaadin;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(final UserAccountRepository repository) {
        return username -> repository
                .findByUsernameIgnoreCase(username)
                .map(account -> new User(account.getUsername(), account.getPasswordHash(), List.of(() -> "ROLE_USER")))
                .orElseThrow(
                        () -> new org.springframework.security.core.userdetails.UsernameNotFoundException("帳號不存在"));
    }

    @Bean
    AuthenticationManager authenticationManager(
            final UserDetailsService userDetailsService, final PasswordEncoder passwordEncoder) {
        final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/exports/**","/blob/**","/thumbnail/**")
                .authenticated()
                .requestMatchers(
                        "/api/auth/**",
                        "/connect/AuthService/**",
                        "/thumbnail/**",
                        "/login",
                        "/register",
                        "/line-awesome/**")
                .permitAll());
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**"));
        http.with(vaadin(), configurer -> configurer.loginView("/login"));
        return http.build();
    }
}
