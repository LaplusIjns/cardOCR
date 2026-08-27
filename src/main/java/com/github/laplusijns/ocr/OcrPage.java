package com.github.laplusijns.ocr;

import java.util.List;

public record OcrPage(int pageNumber, int width, int height, List<OcrBlock> blocks, byte[] pageImage) {
    public OcrPage {
        if (pageNumber < 1) throw new IllegalArgumentException("Page number starts at 1");
        width = Math.max(0, width);
        height = Math.max(0, height);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        pageImage = pageImage == null ? new byte[0] : pageImage.clone();
    }

    @Override
    public byte[] pageImage() {
        return pageImage.clone();
    }
}
