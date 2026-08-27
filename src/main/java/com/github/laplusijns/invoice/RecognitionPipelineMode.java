package com.github.laplusijns.invoice;

import java.util.Locale;

enum RecognitionPipelineMode {
    TEXT_ONLY,
    GROUNDED,
    SHADOW;

    static RecognitionPipelineMode parse(final String value) {
        if (value == null || value.isBlank()) {
            return GROUNDED;
        }
        try {
            return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported recognition pipeline mode: " + value + ". Use text-only, grounded, or shadow.",
                    exception);
        }
    }
}
