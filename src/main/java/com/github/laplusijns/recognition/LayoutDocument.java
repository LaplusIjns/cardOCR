package com.github.laplusijns.recognition;

import java.util.List;

public record LayoutDocument(List<LayoutLine> lines) {
    public LayoutDocument {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
