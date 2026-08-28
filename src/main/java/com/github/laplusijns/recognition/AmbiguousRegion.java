package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;

public record AmbiguousRegion(
        LayoutLine line, AmbiguityReason reason, BoundingBox focusBox, String candidateText) {
    public AmbiguousRegion(final LayoutLine line, final AmbiguityReason reason) {
        this(line, reason, line.boundingBox(), "");
    }

    public AmbiguousRegion {
        if (line == null) throw new IllegalArgumentException("Ambiguous layout line is required");
        if (reason == null) throw new IllegalArgumentException("Ambiguity reason is required");
        focusBox = focusBox == null ? line.boundingBox() : focusBox;
        candidateText = candidateText == null ? "" : candidateText.strip();
    }
}
