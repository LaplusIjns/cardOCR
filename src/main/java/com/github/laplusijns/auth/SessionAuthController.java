package com.github.laplusijns.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SessionAuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public SessionAuthController(final AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionInfo> login(
            @Valid @RequestBody final LoginRequest loginRequest,
            final HttpServletRequest request,
            final HttpServletResponse response) {
        try {
            final Authentication authentication =
                    authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                            loginRequest.username().trim(), loginRequest.password()));
            final SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(new SessionInfo(true, authentication.getName()));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(SessionInfo.anonymous());
        }
    }

    @PostMapping("/logout")
    public SessionInfo logout(
            final Authentication authentication, final HttpServletRequest request, final HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return SessionInfo.anonymous();
    }

    @GetMapping("/session")
    public SessionInfo session(final Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return SessionInfo.anonymous();
        }
        return new SessionInfo(true, authentication.getName());
    }
}
