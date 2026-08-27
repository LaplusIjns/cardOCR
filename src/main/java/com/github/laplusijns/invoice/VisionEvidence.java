package com.github.laplusijns.invoice;

public record VisionEvidence(String content) {

    public VisionEvidence {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Vision evidence must not be blank");
        }
        content = content.strip();
    }
}
