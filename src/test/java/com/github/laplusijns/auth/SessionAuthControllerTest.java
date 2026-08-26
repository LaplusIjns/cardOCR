package com.github.laplusijns.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class SessionAuthControllerTest {

    private final AuthenticationManager authenticationManager = Mockito.mock(AuthenticationManager.class);
    private final SessionAuthController controller = new SessionAuthController(authenticationManager);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginStoresAuthenticationInHttpSession() {
        final Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password123", java.util.List.of(() -> "ROLE_USER"));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        final MockHttpServletRequest request = new MockHttpServletRequest();

        final var result =
                controller.login(new LoginRequest("alice", "password123"), request, new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(new SessionInfo(true, "alice"));
        final Object storedContext =
                request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(storedContext).isInstanceOf(SecurityContext.class);
        assertThat(((SecurityContext) storedContext).getAuthentication().getName())
                .isEqualTo("alice");
    }

    @Test
    void loginRejectsInvalidCredentialsWithoutCreatingSession() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad credentials"));
        final MockHttpServletRequest request = new MockHttpServletRequest();

        final var result =
                controller.login(new LoginRequest("alice", "wrong-password"), request, new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void logoutInvalidatesSession() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        final Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password123", java.util.List.of(() -> "ROLE_USER"));

        final SessionInfo result = controller.logout(authentication, request, new MockHttpServletResponse());

        assertThat(result.authenticated()).isFalse();
        assertThat(request.getSession(false)).isNull();
    }
}
