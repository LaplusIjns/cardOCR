package com.github.laplusijns.auth;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.Endpoint;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Endpoint
@AnonymousAllowed
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(final UserAccountRepository userAccountRepository, final PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistrationResult register(@Valid final RegisterRequest request) {
        final String username = request.username().trim();
        if (userAccountRepository.existsByUsernameIgnoreCase(username)) {
            return new RegistrationResult(false, "此帳號已被使用");
        }

        try {
            userAccountRepository.saveAndFlush(new UserAccount(username, passwordEncoder.encode(request.password())));
            return new RegistrationResult(true, "註冊成功");
        } catch (DataIntegrityViolationException exception) {
            return new RegistrationResult(false, "此帳號已被使用");
        }
    }
}
