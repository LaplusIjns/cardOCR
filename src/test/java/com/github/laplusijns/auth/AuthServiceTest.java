package com.github.laplusijns.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private final UserAccountRepository repository = Mockito.mock(UserAccountRepository.class);
    private final AuthService authService = new AuthService(repository, new BCryptPasswordEncoder());

    @Test
    void registerHashesPasswordAndTrimsUsername() {
        when(repository.existsByUsernameIgnoreCase("alice")).thenReturn(false);
        when(repository.saveAndFlush(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final RegistrationResult result = authService.register(new RegisterRequest("  alice  ", "password123"));

        assertThat(result.success()).isTrue();
        final ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("password123");
        assertThat(new BCryptPasswordEncoder()
                        .matches("password123", captor.getValue().getPasswordHash()))
                .isTrue();
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(repository.existsByUsernameIgnoreCase("Alice")).thenReturn(true);

        final RegistrationResult result = authService.register(new RegisterRequest("Alice", "password123"));

        assertThat(result.success()).isFalse();
        verify(repository, never()).saveAndFlush(any(UserAccount.class));
    }
}
