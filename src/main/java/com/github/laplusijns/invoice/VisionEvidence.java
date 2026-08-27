package com.github.laplusijns.invoice;

public record VisionEvidence(String content, boolean available) {

    public VisionEvidence(final String content) {
        this(content, true);
    }

    public VisionEvidence {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Vision evidence must not be blank");
        }
        content = content.strip();
    }

    public static VisionEvidence unavailable() {
        return new VisionEvidence("OCR evidence unavailable. Inspect the original image directly.", false);
    }
}
