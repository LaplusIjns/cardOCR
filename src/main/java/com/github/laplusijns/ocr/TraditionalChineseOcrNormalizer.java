package com.github.laplusijns.ocr;

import com.github.houbb.opencc4j.core.impl.ZhConvertBootstrap;
import com.github.houbb.opencc4j.support.datamap.impl.DataMaps;
import com.github.houbb.opencc4j.support.segment.impl.Segments;
import com.github.houbb.opencc4j.util.ZhTwConverterUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TraditionalChineseOcrNormalizer implements OcrTextNormalizer {
    private static final Pattern PROTECTED_IDENTIFIER = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9-]+(?:\\.[A-Z0-9-]+)+");
    private static final ZhConvertBootstrap JAPANESE_KANJI_TO_TRADITIONAL = ZhConvertBootstrap.newInstance()
            .segment(Segments.jpSelfFastForward())
            .dataMap(DataMaps.japanSelf())
            .init();

    @Override
    public OcrDocument normalize(final OcrDocument document) {
        if (document == null) throw new IllegalArgumentException("OCR document is required");

        boolean documentChanged = false;
        final List<OcrPage> normalizedPages = new ArrayList<>(document.pages().size());
        for (final OcrPage page : document.pages()) {
            boolean pageChanged = false;
            final List<OcrBlock> normalizedBlocks = new ArrayList<>(page.blocks().size());
            for (final OcrBlock block : page.blocks()) {
                final String normalizedText = normalizeText(block.text());
                if (normalizedText.equals(block.text())) {
                    normalizedBlocks.add(block);
                } else {
                    pageChanged = true;
                    normalizedBlocks.add(new OcrBlock(
                            block.id(),
                            block.pageNumber(),
                            normalizedText,
                            block.boundingBox(),
                            block.confidence()));
                }
            }
            if (pageChanged) {
                documentChanged = true;
                normalizedPages.add(
                        new OcrPage(page.pageNumber(), page.width(), page.height(), normalizedBlocks, page.pageImage()));
            } else {
                normalizedPages.add(page);
            }
        }
        return documentChanged ? new OcrDocument(normalizedPages) : document;
    }

    String normalizeText(final String text) {
        if (text == null || text.isBlank()) return text == null ? "" : text;

        final Matcher matcher = PROTECTED_IDENTIFIER.matcher(text);
        final StringBuilder normalized = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            normalized.append(toTaiwanTraditional(text.substring(cursor, matcher.start())));
            normalized.append(matcher.group());
            cursor = matcher.end();
        }
        normalized.append(toTaiwanTraditional(text.substring(cursor)));
        return normalized.toString();
    }

    private static String toTaiwanTraditional(final String value) {
        if (value.isEmpty()) return value;

        final String traditionalKanji = JAPANESE_KANJI_TO_TRADITIONAL.toSimple(value);
        return ZhTwConverterUtil.containsSimple(traditionalKanji)
                ? ZhTwConverterUtil.toTraditional(traditionalKanji)
                : traditionalKanji;
    }
}
