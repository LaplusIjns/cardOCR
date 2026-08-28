package com.github.laplusijns.recognition;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Pattern NON_NAME_TERMS = Pattern.compile(
            "公司|企業|集團|股份|有限|科技|實業|商行|工作室|協會|學校|大學|中心|"
                    + "經理|主任|董事|總監|部門|事業部|市|縣|區|鄉|鎮|村|里|路|街|巷|弄|號");
    private static final String COMMON_SINGLE_CHARACTER_SURNAMES =
            "趙錢孫李周吳鄭王馮陳褚衛蔣沈韓楊朱秦尤許何呂施張孔曹嚴華金魏陶姜戚謝鄒喻柏水竇章雲蘇潘葛奚范彭郎魯韋昌馬苗鳳花方俞任袁柳鮑史唐費廉岑薛雷賀倪湯滕殷羅畢郝鄔安常樂于時傅皮卞齊康伍余元卜顧孟平黃和穆蕭尹姚邵汪祁毛米貝戴宋龐熊紀舒屈項祝董梁杜阮藍閔席季麻強賈路江童顏郭梅盛林鍾徐邱駱高夏蔡田樊胡凌霍虞萬柯管盧莫房裘解應宗丁宣鄧郁洪包諸左石崔吉龔程邢裴陸榮翁羊惠曲家封儲段富巫烏焦巴牧山谷車侯全秋仲伊宮寧仇甘祖武符劉景詹龍葉黎喬聞翟譚姬申冉牛溫莊柴閻連習艾魚容向古易慎廖文寇廣歐利聶辛簡饒曾沙關查游權益公";
    private static final List<String> COMMON_COMPOUND_SURNAMES =
            List.of("歐陽", "司馬", "上官", "諸葛", "夏侯", "東方", "皇甫", "尉遲", "公孫", "慕容");

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
                final LayoutTextCandidate nameCandidate = possiblePersonName(line).orElse(null);
                if (nameCandidate == null) {
                    ambiguities.add(new AmbiguousRegion(line, AmbiguityReason.UNCLASSIFIED_TEXT));
                } else {
                    ambiguities.add(new AmbiguousRegion(
                            line,
                            AmbiguityReason.POSSIBLE_PERSON_NAME,
                            nameCandidate.boundingBox(),
                            nameCandidate.text()));
                }
            }
        }
        return new RuleEngineResult(resolved, resolvedTypes, ambiguities);
    }

    private static Optional<LayoutTextCandidate> possiblePersonName(final LayoutLine line) {
        return line.compactTextCandidates().stream()
                .filter(candidate -> personNameScore(candidate) >= 4)
                .max(Comparator.comparingInt(BusinessCardRuleEngine::personNameScore)
                        .thenComparingInt(candidate -> characterCount(candidate.text())));
    }

    private static int personNameScore(final LayoutTextCandidate candidate) {
        final String text = candidate.text();
        final int length = characterCount(text);
        if (length < 2 || length > 5 || NON_NAME_TERMS.matcher(text).find()) return Integer.MIN_VALUE;

        int score = 1;
        if (hasCommonSurname(text)) score += 2;
        if (candidate.blockIds().size() > 1) score += 2;
        if (candidate.blockIds().size() == length) score++;
        if (length <= 4) score++;
        return score;
    }

    private static boolean hasCommonSurname(final String text) {
        if (COMMON_COMPOUND_SURNAMES.stream().anyMatch(text::startsWith)) return true;
        final int firstCodePoint = text.codePointAt(0);
        return COMMON_SINGLE_CHARACTER_SURNAMES.indexOf(firstCodePoint) >= 0;
    }

    private static int characterCount(final String text) {
        return text.codePointCount(0, text.length());
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
