package com.github.laplusijns.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.image.ImageStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class BusinessCardExportControllerTest {

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BusinessCardRepository businessCardRepository = mock(BusinessCardRepository.class);
    private final ImageStorageService imageStorageService = mock(ImageStorageService.class);
    private final BusinessCardExportController controller =
            new BusinessCardExportController(userAccountRepository, businessCardRepository, imageStorageService);

    @Test
    void archiveKeepsZipStreamOpenAfterWritingJson() throws Exception {
        final UserAccount user = new UserAccount("alice", "hash");
        final var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password", List.of(() -> "ROLE_USER"));
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(null)).thenReturn(List.of());

        final var response = controller.archive(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("cards.json");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("[ ]");
            assertThat(zip.getNextEntry()).isNull();
        }
    }
}
