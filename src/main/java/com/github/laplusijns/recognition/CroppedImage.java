package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;

public record CroppedImage(int pageNumber, BoundingBox sourceBox, String mimeType, byte[] bytes) {
    public CroppedImage {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
