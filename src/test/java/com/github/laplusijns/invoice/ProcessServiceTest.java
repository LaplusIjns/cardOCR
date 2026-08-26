package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.card.BusinessCard;
import com.github.laplusijns.card.BusinessCardChannels;
import com.github.laplusijns.card.BusinessCardDTO;
import com.github.laplusijns.card.BusinessCardRepository;
import com.github.laplusijns.image.ImageCache;
import com.github.laplusijns.image.ImageStorageService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ProcessServiceTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BusinessCardRepository businessCardRepository = mock(BusinessCardRepository.class);
    private final BusinessCardChannels channels = mock(BusinessCardChannels.class);
    private final ImageStorageService imageStorageService = mock(ImageStorageService.class);
    private final ImageCache imageCache = mock(ImageCache.class);
    private final UserAccount user = mock(UserAccount.class);
    private final ProcessService service = new ProcessService(
            chatClient, userAccountRepository, businessCardRepository, channels, imageStorageService, imageCache);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "password", List.of(() -> "ROLE_USER")));
        when(user.getId()).thenReturn(7L);
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAndQueuesMultipleImages() throws Exception {
        when(imageStorageService.store(eq(7L), anyString(), eq("image/png"), any(byte[].class)))
                .thenAnswer(invocation -> "7/" + invocation.getArgument(1) + ".png");
        when(chatClient.prompt(any(Prompt.class)).call().entity(ProcessService.BusinessCardRecognition.class))
                .thenReturn(new ProcessService.BusinessCardRecognition());
        when(businessCardRepository.save(any(BusinessCard.class))).thenAnswer(invocation -> invocation.getArgument(0));
        final String image = "data:image/png;base64,AQID";

        final int accepted = service.processImages(List.of(image, image), "session-1");

        assertThat(accepted).isEqualTo(2);
        verify(imageStorageService, times(2)).store(eq(7L), anyString(), eq("image/png"), any(byte[].class));
        verify(imageCache, times(2)).put(anyString(), any(byte[].class));
        verify(channels, times(2)).emit(eq("session-1"), any(BusinessCardDTO.class));
    }

    @Test
    void validatesWholeBatchBeforeStoringAnything() {
        final List<String> images = List.of("data:image/png;base64,AQID", "data:text/plain;base64,AQID");

        assertThatThrownBy(() -> service.processImages(images, "session-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image type");

        verifyNoInteractions(imageStorageService, imageCache, channels);
    }

    @Test
    void rejectsMoreThanTwentyImages() {
        final List<String> images = Collections.nCopies(21, "data:image/png;base64,AQID");

        assertThatThrownBy(() -> service.processImages(images, "session-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum of 20");

        verifyNoInteractions(imageStorageService, imageCache, channels);
    }
}
