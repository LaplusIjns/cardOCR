package com.github.laplusijns.recognition;

import java.util.List;

public interface SemanticDisambiguator {
    BusinessCardRecognition resolve(
            LayoutDocument layout, RuleEngineResult ruleResult, List<CroppedImage> croppedImages);
}
