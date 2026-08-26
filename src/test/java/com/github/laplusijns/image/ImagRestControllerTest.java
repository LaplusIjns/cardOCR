package com.github.laplusijns.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.card.BusinessCard;
import com.github.laplusijns.card.BusinessCardRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ImagRestControllerTest {

    private final ImageCache imageCache = mock(ImageCache.class);
    private final ImageStorageService imageStorageService = mock(ImageStorageService.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BusinessCardRepository businessCardRepository = mock(BusinessCardRepository.class);
    private final ImagRestController controller = new ImagRestController(
            imageCache, imageStorageService, userAccountRepository, businessCardRepository);

    @Test
    void returnsImageOwnedByAuthenticatedUser() {
        final UserAccount user = new UserAccount("alice", "hash");
        final BusinessCard card = new BusinessCard(user, "image-1", "1/image-1.png");
        final byte[] image = {1, 2, 3};
        final var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password", List.of(() -> "ROLE_USER"));
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(businessCardRepository.findByImageIdAndUser_Id("image-1", null)).thenReturn(Optional.of(card));
        when(imageCache.get("image-1")).thenReturn(image);

        final var response = controller.downloadFile("image-1", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(image);
        verify(businessCardRepository).findByImageIdAndUser_Id("image-1", null);
    }

    @Test
    void doesNotReturnAnotherUsersImage() {
        final UserAccount user = new UserAccount("alice", "hash");
        final var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password", List.of(() -> "ROLE_USER"));
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(businessCardRepository.findByImageIdAndUser_Id("someone-elses-image", null))
                .thenReturn(Optional.empty());

        final var response = controller.downloadFile("someone-elses-image", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsAnonymousRequests() {
        assertThat(controller.downloadFile("image-1", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
