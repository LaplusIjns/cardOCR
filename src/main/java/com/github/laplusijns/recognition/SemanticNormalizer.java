package com.github.laplusijns.recognition;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SemanticNormalizer {
    private static final Map<String, FieldType> LABELS = labels();
    private static final Pattern INLINE_LABEL = Pattern.compile("^([^\\s:：|]{1,24})[\\s:：|]+(.+)$");

    public Optional<LabeledValue> labeledValue(final LayoutLine line) {
        if (!line.blocks().isEmpty()) {
            final FieldType firstBlockType =
                    labelType(line.blocks().getFirst().text()).orElse(null);
            if (firstBlockType != null && line.blocks().size() > 1) {
                final String value = String.join(
                        " ",
                        line.blocks().subList(1, line.blocks().size()).stream()
                                .map(block -> block.text().strip())
                                .toList());
                return Optional.of(new LabeledValue(
                        firstBlockType, line.blocks().getFirst().text(), value));
            }
        }
        final String text = line.text().replace(" | ", " ").strip();
        final Matcher matcher = INLINE_LABEL.matcher(text);
        if (matcher.matches()) {
            return labelType(matcher.group(1))
                    .map(type -> new LabeledValue(
                            type, matcher.group(1), matcher.group(2).strip()));
        }
        return Optional.empty();
    }

    public Optional<FieldType> labelType(final String rawLabel) {
        return Optional.ofNullable(LABELS.get(normalizeLabel(rawLabel)));
    }

    static String normalizeLabel(final String value) {
        if (value == null) return "";
        return value.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s.:：|_-]+", "");
    }

    private static Map<String, FieldType> labels() {
        final Map<String, FieldType> labels = new LinkedHashMap<>();
        add(labels, FieldType.TELEPHONE, "T", "TEL", "TELEPHONE", "PHONE", "電話", "公司電話");
        add(labels, FieldType.FAX, "F", "FAX", "FACSIMILE", "傳真");
        add(labels, FieldType.MOBILE_PHONE, "M", "MOBILE", "CELL", "CELLPHONE", "手機", "行動電話");
        add(labels, FieldType.EMAIL, "E", "EMAIL", "E-MAIL", "電子郵件");
        add(labels, FieldType.ADDRESS, "A", "ADDR", "ADDRESS", "地址");
        add(labels, FieldType.BUSINESS_NUMBER, "統編", "統一編號", "TAXID", "UBN");
        add(labels, FieldType.STOCK_CODE, "股票代號", "STOCKCODE", "TICKER");
        add(labels, FieldType.COMPANY_WEBSITE, "W", "WEB", "WEBSITE", "網址");
        return Map.copyOf(labels);
    }

    private static void add(final Map<String, FieldType> labels, final FieldType type, final String... aliases) {
        for (final String alias : aliases) labels.put(normalizeLabel(alias), type);
    }

    public record LabeledValue(FieldType type, String label, String value) {}
}
