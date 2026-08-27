package com.github.laplusijns.ocr;

import java.util.Locale;
import java.util.Set;

public record DocumentInput(String mimeType, byte[] bytes) {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    public DocumentInput {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("Document MIME type is required");
        }
        mimeType = mimeType.toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.contains(mimeType) && !"application/pdf".equals(mimeType)) {
            throw new IllegalArgumentException("Unsupported document type: " + mimeType);
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Document is empty");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean isPdf() {
        return "application/pdf".equals(mimeType);
    }

    public int paddleXFileType() {
        return isPdf() ? 0 : 1;
    }
}
