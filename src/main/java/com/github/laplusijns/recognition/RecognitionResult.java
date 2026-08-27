package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.OcrDocument;
import java.util.List;

public record RecognitionResult(
        BusinessCardRecognition businessCard,
        OcrDocument ocrDocument,
        LayoutDocument layoutDocument,
        RuleEngineResult ruleEngineResult,
        boolean openAiUsed,
        List<CroppedImage> croppedImages) {
    public RecognitionResult {
        croppedImages = List.copyOf(croppedImages);
    }
}
