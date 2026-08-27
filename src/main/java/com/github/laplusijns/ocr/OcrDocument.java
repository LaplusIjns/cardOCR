package com.github.laplusijns.ocr;

import java.util.List;

public record OcrDocument(List<OcrPage> pages) {
    public OcrDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public List<OcrBlock> blocks() {
        return pages.stream().flatMap(page -> page.blocks().stream()).toList();
    }
}
