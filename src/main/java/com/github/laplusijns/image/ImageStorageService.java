package com.github.laplusijns.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageStorageService {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif");

    private final Path storageRoot;

    public ImageStorageService(@Value("${card-ocr.image-storage-path:./uploads}") final String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    public String store(final Long userId, final String imageId, final String mimeType, final byte[] imageBytes)
            throws IOException {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid user id");
        }
        if (imageBytes.length == 0) {
            throw new IllegalArgumentException("Image is empty");
        }

        final String normalizedMimeType = mimeType.toLowerCase(Locale.ROOT);
        final String extension = EXTENSIONS.get(normalizedMimeType);
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported image type: " + mimeType);
        }

        final Path userDirectory = storageRoot.resolve(userId.toString()).normalize();
        if (!userDirectory.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        Files.createDirectories(userDirectory);

        final Path imagePath = userDirectory.resolve(imageId + extension).normalize();
        if (!imagePath.startsWith(userDirectory)) {
            throw new IllegalArgumentException("Invalid image id");
        }
        Files.write(imagePath, imageBytes, StandardOpenOption.CREATE_NEW);
        return storageRoot.relativize(imagePath).toString().replace('\\', '/');
    }

    public byte[] read(final String relativePath) throws IOException {
        final Path imagePath = storageRoot.resolve(relativePath).normalize();
        if (!imagePath.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid image path");
        }
        return Files.readAllBytes(imagePath);
    }

    public void delete(final String relativePath) throws IOException {
        final Path imagePath = storageRoot.resolve(relativePath).normalize();
        if (!imagePath.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid image path");
        }
        Files.deleteIfExists(imagePath);
    }
}
