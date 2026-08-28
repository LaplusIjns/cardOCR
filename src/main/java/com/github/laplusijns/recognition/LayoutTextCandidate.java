package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import java.util.List;

public record LayoutTextCandidate(
        String text, List<String> blockIds, BoundingBox boundingBox, double confidence) {
    public LayoutTextCandidate {
        text = text == null ? "" : text.strip();
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        if (boundingBox == null) throw new IllegalArgumentException("Candidate bounding box is required");
        if (!Double.isFinite(confidence)) throw new IllegalArgumentException("Candidate confidence must be finite");
    }
}
