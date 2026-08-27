package com.github.laplusijns.ocr;

public record OcrBlock(String id, int pageNumber, String text, BoundingBox boundingBox, double confidence) {
    public OcrBlock {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("OCR block id is required");
        if (pageNumber < 1) throw new IllegalArgumentException("Page number starts at 1");
        if (text == null) text = "";
        if (boundingBox == null) throw new IllegalArgumentException("Bounding box is required");
        if (!Double.isFinite(confidence)) confidence = 0.0;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
