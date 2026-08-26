package com.github.laplusijns.image;

import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.card.BusinessCardRepository;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImagRestController {
    private final ImageCache imageCache;
    private final ImageStorageService imageStorageService;
    private final UserAccountRepository userAccountRepository;
    private final BusinessCardRepository businessCardRepository;

    public ImagRestController(
            final ImageCache imageCache,
            final ImageStorageService imageStorageService,
            final UserAccountRepository userAccountRepository,
            final BusinessCardRepository businessCardRepository) {
        this.imageCache = imageCache;
        this.imageStorageService = imageStorageService;
        this.userAccountRepository = userAccountRepository;
        this.businessCardRepository = businessCardRepository;
    }

    @GetMapping({"/blob/{imageId}", "/thumbnail/{imageId}"})
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable final String imageId, final Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.status(401).build();
        final var user = userAccountRepository.findByUsernameIgnoreCase(authentication.getName());
        if (user.isEmpty()) return ResponseEntity.status(401).build();
        final var card = businessCardRepository.findByImageIdAndUser_Id(
                imageId, user.get().getId());
        if (card.isEmpty()) return ResponseEntity.notFound().build();
        byte[] file = imageCache.get(imageId);
        if (file == null) {
            try {
                file = imageStorageService.read(card.get().getImagePath());
                imageCache.put(imageId, file);
            } catch (IOException exception) {
                return ResponseEntity.notFound().build();
            }
        }
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(file);
    }
}
