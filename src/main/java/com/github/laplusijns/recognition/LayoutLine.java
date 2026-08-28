package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import java.util.List;

public record LayoutLine(
        int pageNumber,
        String id,
        List<OcrBlock> blocks,
        BoundingBox boundingBox,
        double confidence,
        String text,
        List<LayoutTextCandidate> compactTextCandidates) {
    public LayoutLine(
            final int pageNumber,
            final String id,
            final List<OcrBlock> blocks,
            final BoundingBox boundingBox,
            final double confidence,
            final String text) {
        this(pageNumber, id, blocks, boundingBox, confidence, text, List.of());
    }

    public LayoutLine {
        blocks = List.copyOf(blocks);
        compactTextCandidates =
                compactTextCandidates == null ? List.of() : List.copyOf(compactTextCandidates);
    }
}
