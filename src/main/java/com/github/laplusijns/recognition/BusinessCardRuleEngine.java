package com.github.laplusijns.recognition;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BusinessCardRuleEngine {
    private static final Pattern EMAIL =
            Pattern.compile("(?i)(?<![\\w.+-])([\\w.!#$%&'*+/=?^`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+)");
    private static final Pattern WEBSITE = Pattern.compile("(?i)\\b((?:https?://|www\\.)[^\\s|、]+)");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(\\+?\\d[\\d() .-]{4,}\\d(?:\\s*(?:#|ext\\.?|分機)\\s*\\d+)?)(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_NUMBER = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");
    private static final Pattern STOCK_CODE =
            Pattern.compile("(?<![A-Z0-9])([A-Z0-9]{2,10})(?![A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSSIBLE_SHORT_LABEL = Pattern.compile("^[\\p{L}]{1,5}$");

    private final SemanticNormalizer normalizer;
    private final double deterministicConfidence;

    public BusinessCardRuleEngine(
            final SemanticNormalizer normalizer,
            @Value("${card-ocr.rules.deterministic-confidence:0.85}") final double deterministicConfidence) {
        this.normalizer = normalizer;
        this.deterministicConfidence = deterministicConfidence;
    }

    public RuleEngineResult classify(final LayoutDocument document) {
        final BusinessCardRecognition resolved = new BusinessCardRecognition();
        final Set<FieldType> resolvedTypes = EnumSet.noneOf(FieldType.class);
        final List<AmbiguousRegion> ambiguities = new ArrayList<>();
        for (final LayoutLine line : document.lines()) {
            if (line.text().isBlank()) continue;
            if (line.confidence() < deterministicConfidence) {
                ambiguities.add(new AmbiguousRegion(line, AmbiguityReason.LOW_OCR_CONFIDENCE));
                continue;
            }

            final Optional<SemanticNormalizer.LabeledValue> labeled = normalizer.labeledValue(line);
            if (labeled.isPresent()) {
                final SemanticNormalizer.LabeledValue value = labeled.get();
                if (applyLabeled(resolved, resolvedTypes, value.type(), value.value())) continue;
                ambiguities.add(new AmbiguousRegion(line, AmbiguityReason.INVALID_LABELED_VALUE));
                continue;
            }

            boolean consumed = false;
            consumed |= addMatches(resolved, resolvedTypes, FieldType.EMAIL, line.text(), EMAIL);
            consumed |= addMatches(resolved, resolvedTypes, FieldType.COMPANY_WEBSITE, line.text(), WEBSITE);
            if (consumed) continue;

            final Matcher phone = PHONE.matcher(line.text());
            if (phone.find()) {
                final String firstText = line.blocks().isEmpty()
                        ? ""
                        : line.blocks().getFirst().text().strip();
                final AmbiguityReason reason =
                        POSSIBLE_SHORT_LABEL.matcher(firstText).matches()
                                ? AmbiguityReason.UNKNOWN_LABEL
                                : AmbiguityReason.UNLABELED_PHONE;
                ambiguities.add(new AmbiguousRegion(line, reason));
            } else {
                ambiguities.add(new AmbiguousRegion(line, AmbiguityReason.UNCLASSIFIED_TEXT));
            }
        }
        return new RuleEngineResult(resolved, resolvedTypes, ambiguities);
    }

    private static boolean applyLabeled(
            final BusinessCardRecognition result,
            final Set<FieldType> resolvedTypes,
            final FieldType type,
            final String rawValue) {
        final Pattern expected =
                switch (type) {
                    case TELEPHONE, MOBILE_PHONE, FAX -> PHONE;
                    case EMAIL -> EMAIL;
                    case BUSINESS_NUMBER -> BUSINESS_NUMBER;
                    case STOCK_CODE -> STOCK_CODE;
                    case COMPANY_WEBSITE -> WEBSITE;
                    case ADDRESS -> null;
                    default -> null;
                };
        if (type == FieldType.ADDRESS && !rawValue.isBlank()) {
            append(result, type, rawValue.strip());
            resolvedTypes.add(type);
            return true;
        }
        if (expected == null) return false;
        return addMatches(result, resolvedTypes, type, rawValue, expected);
    }

    private static boolean addMatches(
            final BusinessCardRecognition result,
            final Set<FieldType> resolvedTypes,
            final FieldType type,
            final String input,
            final Pattern pattern) {
        final Matcher matcher = pattern.matcher(input);
        boolean found = false;
        while (matcher.find()) {
            append(result, type, matcher.group(1).strip());
            found = true;
        }
        if (found) resolvedTypes.add(type);
        return found;
    }

    private static void append(final BusinessCardRecognition result, final FieldType type, final String value) {
        switch (type) {
            case TELEPHONE -> result.telephone = join(result.telephone, value);
            case MOBILE_PHONE -> result.mobilePhone = join(result.mobilePhone, value);
            case FAX -> result.fax = join(result.fax, value);
            case EMAIL -> result.email = join(result.email, value);
            case ADDRESS -> result.address = join(result.address, value);
            case BUSINESS_NUMBER -> result.businessNumber = join(result.businessNumber, value);
            case STOCK_CODE -> result.stockCode = join(result.stockCode, value);
            case COMPANY_WEBSITE -> result.companyWebsite = join(result.companyWebsite, value);
            default -> throw new IllegalArgumentException("Unsupported deterministic field: " + type);
        }
    }

    private static String join(final String current, final String value) {
        return current == null || current.isBlank() ? value : current + "、" + value;
    }
}
