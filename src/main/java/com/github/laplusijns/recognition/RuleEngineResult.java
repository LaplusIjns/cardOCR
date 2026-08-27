package com.github.laplusijns.recognition;

import java.util.List;
import java.util.Set;

public record RuleEngineResult(
        BusinessCardRecognition resolvedFields, Set<FieldType> resolvedFieldTypes, List<AmbiguousRegion> ambiguities) {
    public RuleEngineResult {
        resolvedFieldTypes = Set.copyOf(resolvedFieldTypes);
        ambiguities = List.copyOf(ambiguities);
    }
}
