package com.github.laplusijns.invoice;

import org.springframework.stereotype.Service;

@Service
public class CardRecognitionPipeline {

    private final CardVisionService cardVisionService;
    private final CardParsingService cardParsingService;

    public CardRecognitionPipeline(
            final CardVisionService cardVisionService, final CardParsingService cardParsingService) {
        this.cardVisionService = cardVisionService;
        this.cardParsingService = cardParsingService;
    }

    public BusinessCardRecognition recognize(final String mimeType, final byte[] imageBytes) {
        final VisionEvidence evidence = cardVisionService.understand(mimeType, imageBytes);
        return cardParsingService.parse(evidence);
    }
}
