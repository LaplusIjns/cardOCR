package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import java.util.List;

public record LayoutLine(
        int pageNumber, String id, List<OcrBlock> blocks, BoundingBox boundingBox, double confidence, String text) {
    public LayoutLine {
        blocks = List.copyOf(blocks);
    }
}
