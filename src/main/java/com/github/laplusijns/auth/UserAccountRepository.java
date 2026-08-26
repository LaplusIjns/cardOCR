package com.github.laplusijns.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    boolean existsByUsernameIgnoreCase(String username);

    Optional<UserAccount> findByUsernameIgnoreCase(String username);
}
