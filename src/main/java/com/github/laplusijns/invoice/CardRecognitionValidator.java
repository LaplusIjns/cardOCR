package com.github.laplusijns.invoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CardRecognitionValidator {

    private static final Set<CardField> IMPORTANT_FIELDS =
            Set.of(CardField.COMPANY_NAME, CardField.NAME, CardField.JOB_TITLE, CardField.EMAIL);
    private static final Set<CardField> EXACT_GROUNDING_FIELDS = Set.of(
            CardField.TELEPHONE,
            CardField.MOBILE_PHONE,
            CardField.FAX,
            CardField.EMAIL,
            CardField.BUSINESS_NUMBER,
            CardField.STOCK_CODE,
            CardField.COMPANY_WEBSITE);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEBSITE_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,}(?::\\d+)?(?:/\\S*)?$",
            Pattern.CASE_INSENSITIVE);

    private final double confidenceThreshold;

    public CardRecognitionValidator(
            @Value("${card-ocr.ai.verification.confidence-threshold:0.75}") final double confidenceThreshold) {
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("Confidence threshold must be between 0 and 1");
        }
        this.confidenceThreshold = confidenceThreshold;
    }

    public List<CardValidationIssue> validate(
            final CardExtractionResult extraction, final VisionEvidence evidence) {
        final BusinessCardRecognition card = extraction.safeCard();
        final List<CardValidationIssue> issues = new ArrayList<>();

        for (final CardField field : CardField.values()) {
            final String value = field.read(card).strip();
            if (value.isEmpty()) {
                if (IMPORTANT_FIELDS.contains(field)) {
                    issues.add(new CardValidationIssue(field, "重要欄位為空"));
                }
                continue;
            }

            extraction.assessment(field).ifPresentOrElse(
                    assessment -> validateAssessment(field, assessment, issues),
                    () -> issues.add(new CardValidationIssue(field, "缺少欄位來源與可信度")));
            if (EXACT_GROUNDING_FIELDS.contains(field) && evidence.available() && !grounded(value, evidence.content())) {
                issues.add(new CardValidationIssue(field, "欄位值無法在 OCR evidence 中逐字對應"));
            }
        }

        validateSeparatedValues(card.email, CardField.EMAIL, EMAIL_PATTERN.asMatchPredicate(), "Email 格式異常", issues);
        validateSeparatedValues(
                card.companyWebsite,
                CardField.COMPANY_WEBSITE,
                WEBSITE_PATTERN.asMatchPredicate(),
                "公司網址格式異常",
                issues);
        if (!blank(card.businessNumber)) {
            for (final String value : card.businessNumber.split("、")) {
                if (value.replaceAll("\\D", "").length() != 8) {
                    issues.add(new CardValidationIssue(CardField.BUSINESS_NUMBER, "統編不是 8 位數字"));
                    break;
                }
            }
        }
        return List.copyOf(issues);
    }

    private void validateAssessment(
            final CardField field,
            final FieldAssessment assessment,
            final List<CardValidationIssue> issues) {
        if (assessment.confidence < confidenceThreshold) {
            issues.add(new CardValidationIssue(field, "可信度低於 " + confidenceThreshold));
        }
        if (assessment.safeSourceBlockIds().isEmpty()) {
            issues.add(new CardValidationIssue(field, "沒有來源 block 或 IMAGE 標記"));
        }
        if (!assessment.safeAlternatives().isEmpty()) {
            issues.add(new CardValidationIssue(field, "存在其他可能值"));
        }
    }

    private static void validateSeparatedValues(
            final String input,
            final CardField field,
            final Predicate<String> predicate,
            final String reason,
            final List<CardValidationIssue> issues) {
        if (blank(input)) {
            return;
        }
        for (final String value : input.split("、")) {
            if (!predicate.test(value.strip())) {
                issues.add(new CardValidationIssue(field, reason));
                return;
            }
        }
    }

    private static boolean grounded(final String value, final String evidence) {
        final String normalizedEvidence = normalize(evidence);
        for (final String part : value.split("、")) {
            final String normalizedValue = normalize(part);
            if (!normalizedValue.isEmpty() && !normalizedEvidence.contains(normalizedValue)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(final String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}@._:/+-]", "");
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }
}
