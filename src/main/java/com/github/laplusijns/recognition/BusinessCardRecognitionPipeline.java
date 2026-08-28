package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.ocr.OcrClient;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrTextNormalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class BusinessCardRecognitionPipeline {
    private final OcrClient ocrClient;
    private final OcrTextNormalizer ocrTextNormalizer;
    private final LayoutReconstructionService layoutReconstruction;
    private final BusinessCardRuleEngine ruleEngine;
    private final ImageCropService imageCropService;
    private final SemanticDisambiguator semanticDisambiguator;
    private final MissingFieldVisionVerifier missingFieldVisionVerifier;
    private final BusinessCardValidator validator;

    public BusinessCardRecognitionPipeline(
            final OcrClient ocrClient,
            final OcrTextNormalizer ocrTextNormalizer,
            final LayoutReconstructionService layoutReconstruction,
            final BusinessCardRuleEngine ruleEngine,
            final ImageCropService imageCropService,
            final SemanticDisambiguator semanticDisambiguator,
            final MissingFieldVisionVerifier missingFieldVisionVerifier,
            final BusinessCardValidator validator) {
        this.ocrClient = ocrClient;
        this.ocrTextNormalizer = ocrTextNormalizer;
        this.layoutReconstruction = layoutReconstruction;
        this.ruleEngine = ruleEngine;
        this.imageCropService = imageCropService;
        this.semanticDisambiguator = semanticDisambiguator;
        this.missingFieldVisionVerifier = missingFieldVisionVerifier;
        this.validator = validator;
    }

    public RecognitionResult recognize(final DocumentInput input) {
        final OcrDocument ocr = ocrTextNormalizer.normalize(ocrClient.recognize(input));
        final LayoutDocument layout = layoutReconstruction.reconstruct(ocr);
        final RuleEngineResult ruleResult = ruleEngine.classify(layout);
        final boolean hasAmbiguity = !ruleResult.ambiguities().isEmpty();
        final List<CroppedImage> crops =
                hasAmbiguity ? imageCropService.cropAmbiguousRegions(input, ocr, ruleResult.ambiguities()) : List.of();
        final BusinessCardRecognition merged = ruleResult.resolvedFields().copy();
        boolean openAiUsed = false;
        if (hasAmbiguity) {
            final BusinessCardRecognition semantic = semanticDisambiguator.resolve(layout, ruleResult, crops);
            mergeOnlyUnresolved(merged, semantic, ruleResult.resolvedFieldTypes());
            openAiUsed = true;
        }

        BusinessCardRecognition normalized = validator.normalize(merged);
        final Set<FieldType> missingFields = missingCoreFields(normalized);
        if (!missingFields.isEmpty()) {
            final List<CroppedImage> fullPageImages = imageCropService.fullPageImages(input, ocr);
            if (!fullPageImages.isEmpty()) {
                final BusinessCardRecognition verified = missingFieldVisionVerifier.verify(
                        layout, normalized.copy(), missingFields, fullPageImages);
                mergeOnlyMissing(normalized, verified, missingFields);
                normalized = validator.normalize(normalized);
                openAiUsed = true;
            }
        }
        return new RecognitionResult(normalized, ocr, layout, ruleResult, openAiUsed, crops);
    }

    private static Set<FieldType> missingCoreFields(final BusinessCardRecognition fields) {
        final Set<FieldType> missing = EnumSet.noneOf(FieldType.class);
        if (isBlank(fields.companyName)) missing.add(FieldType.COMPANY_NAME);
        if (isBlank(fields.name)) missing.add(FieldType.NAME);
        if (isBlank(fields.jobTitle)) missing.add(FieldType.JOB_TITLE);
        return Set.copyOf(missing);
    }

    private static void mergeOnlyMissing(
            final BusinessCardRecognition target,
            final BusinessCardRecognition verified,
            final Set<FieldType> missingFields) {
        if (verified == null) return;
        if (missingFields.contains(FieldType.COMPANY_NAME)
                && isBlank(target.companyName)
                && !isBlank(verified.companyName)) target.companyName = verified.companyName;
        if (missingFields.contains(FieldType.NAME) && isBlank(target.name) && !isBlank(verified.name))
            target.name = verified.name;
        if (missingFields.contains(FieldType.JOB_TITLE)
                && isBlank(target.jobTitle)
                && !isBlank(verified.jobTitle)) target.jobTitle = verified.jobTitle;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private static void mergeOnlyUnresolved(
            final BusinessCardRecognition target,
            final BusinessCardRecognition semantic,
            final Set<FieldType> deterministicFields) {
        if (semantic == null) return;
        if (!deterministicFields.contains(FieldType.COMPANY_NAME)) target.companyName = semantic.companyName;
        if (!deterministicFields.contains(FieldType.NAME)) target.name = semantic.name;
        if (!deterministicFields.contains(FieldType.JOB_TITLE)) target.jobTitle = semantic.jobTitle;
        if (!deterministicFields.contains(FieldType.TELEPHONE)) target.telephone = semantic.telephone;
        if (!deterministicFields.contains(FieldType.MOBILE_PHONE)) target.mobilePhone = semantic.mobilePhone;
        if (!deterministicFields.contains(FieldType.FAX)) target.fax = semantic.fax;
        if (!deterministicFields.contains(FieldType.EMAIL)) target.email = semantic.email;
        if (!deterministicFields.contains(FieldType.ADDRESS)) target.address = semantic.address;
        if (!deterministicFields.contains(FieldType.BUSINESS_NUMBER)) target.businessNumber = semantic.businessNumber;
        if (!deterministicFields.contains(FieldType.STOCK_CODE)) target.stockCode = semantic.stockCode;
        if (!deterministicFields.contains(FieldType.COMPANY_WEBSITE)) target.companyWebsite = semantic.companyWebsite;
    }
}
