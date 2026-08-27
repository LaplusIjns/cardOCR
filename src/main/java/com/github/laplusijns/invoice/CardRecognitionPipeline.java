package com.github.laplusijns.invoice;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CardRecognitionPipeline {

    private static final Logger log = LoggerFactory.getLogger(CardRecognitionPipeline.class);

    private final CardVisionService cardVisionService;
    private final CardParsingService cardParsingService;
    private final CardRecognitionValidator validator;
    private final CardVerificationService verificationService;
    private final RecognitionPipelineMode mode;

    @Autowired
    public CardRecognitionPipeline(
            final CardVisionService cardVisionService,
            final CardParsingService cardParsingService,
            final CardRecognitionValidator validator,
            final CardVerificationService verificationService,
            @Value("${card-ocr.ai.pipeline.mode:grounded}") final String mode) {
        this(cardVisionService, cardParsingService, validator, verificationService, RecognitionPipelineMode.parse(mode));
    }

    CardRecognitionPipeline(
            final CardVisionService cardVisionService,
            final CardParsingService cardParsingService,
            final CardRecognitionValidator validator,
            final CardVerificationService verificationService,
            final RecognitionPipelineMode mode) {
        this.cardVisionService = cardVisionService;
        this.cardParsingService = cardParsingService;
        this.validator = validator;
        this.verificationService = verificationService;
        this.mode = mode;
    }

    public BusinessCardRecognition recognize(final String mimeType, final byte[] imageBytes) {
        final VisionEvidence evidence = visionEvidence(mimeType, imageBytes);
        return switch (mode) {
            case TEXT_ONLY -> parseTextOnlyWithRetry(evidence).safeCard();
            case GROUNDED -> recognizeGrounded(evidence, mimeType, imageBytes);
            case SHADOW -> recognizeShadow(evidence, mimeType, imageBytes);
        };
    }

    private BusinessCardRecognition recognizeGrounded(
            final VisionEvidence evidence, final String mimeType, final byte[] imageBytes) {
        final CardExtractionResult extraction = parseGroundedWithRetry(evidence, mimeType, imageBytes);
        final BusinessCardRecognition initial = extraction.safeCard();
        final List<CardValidationIssue> issues = validator.validate(extraction, evidence);
        if (issues.isEmpty()) {
            return initial;
        }

        try {
            final BusinessCardRecognition verified =
                    verificationService.verify(evidence, mimeType, imageBytes, initial, issues);
            final Set<CardField> reviewedFields = issues.stream()
                    .map(CardValidationIssue::field)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            reviewedFields.forEach(field -> field.copy(verified, initial));
        } catch (RuntimeException exception) {
            log.warn("Consolidated card verification failed; keeping the grounded extraction");
        }
        return initial;
    }

    private BusinessCardRecognition recognizeShadow(
            final VisionEvidence evidence, final String mimeType, final byte[] imageBytes) {
        final BusinessCardRecognition grounded = recognizeGrounded(evidence, mimeType, imageBytes);
        try {
            final BusinessCardRecognition textOnly = parseTextOnlyWithRetry(evidence).safeCard();
            final List<String> differences = Arrays.stream(CardField.values())
                    .filter(field -> !Objects.equals(field.read(grounded), field.read(textOnly)))
                    .map(CardField::apiName)
                    .toList();
            log.info("Recognition shadow comparison completed; differing fields: {}", differences);
        } catch (RuntimeException exception) {
            log.warn("Text-only shadow recognition failed; grounded result remains available");
        }
        return grounded;
    }

    private VisionEvidence visionEvidence(final String mimeType, final byte[] imageBytes) {
        try {
            return cardVisionService.understand(mimeType, imageBytes);
        } catch (RuntimeException exception) {
            log.warn("Vision evidence generation failed; Qwen3.8-Max will inspect the original image directly");
            return VisionEvidence.unavailable();
        }
    }

    private CardExtractionResult parseGroundedWithRetry(
            final VisionEvidence evidence, final String mimeType, final byte[] imageBytes) {
        try {
            return cardParsingService.parse(evidence, mimeType, imageBytes);
        } catch (RuntimeException firstFailure) {
            log.warn("Grounded card parsing failed; retrying once with the same image and evidence");
            try {
                return cardParsingService.parse(evidence, mimeType, imageBytes);
            } catch (RuntimeException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private CardExtractionResult parseTextOnlyWithRetry(final VisionEvidence evidence) {
        try {
            return cardParsingService.parseTextOnly(evidence);
        } catch (RuntimeException firstFailure) {
            log.warn("Text-only card parsing failed; retrying once with the same evidence");
            try {
                return cardParsingService.parseTextOnly(evidence);
            } catch (RuntimeException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }
}
