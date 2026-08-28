package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessCardRuleEngineTest {
    private final BusinessCardRuleEngine engine = new BusinessCardRuleEngine(new SemanticNormalizer(), 0.85);

    @Test
    void classifiesFaxFromSeparatedLabelAndNumberWithoutOpenAi() {
        final LayoutLine line = line("F | 03-12345678", 0.98, "F", "03-12345678");

        final RuleEngineResult result = engine.classify(new LayoutDocument(List.of(line)));

        assertThat(result.resolvedFields().fax).isEqualTo("03-12345678");
        assertThat(result.resolvedFieldTypes()).containsExactly(FieldType.FAX);
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void routesPossibleMisreadLabelAndLowConfidenceTextToSemanticResolution() {
        final LayoutLine unknownLabel = line("P | 03-12345678", 0.97, "P", "03-12345678");
        final LayoutLine lowConfidence = line("王小明", 0.61, "王小明");

        final RuleEngineResult result = engine.classify(new LayoutDocument(List.of(unknownLabel, lowConfidence)));

        assertThat(result.ambiguities())
                .extracting(AmbiguousRegion::reason)
                .containsExactly(AmbiguityReason.UNKNOWN_LABEL, AmbiguityReason.LOW_OCR_CONFIDENCE);
    }

    @Test
    void extractsHighConfidenceEmailWithoutModelCall() {
        final RuleEngineResult result =
                engine.classify(new LayoutDocument(List.of(line("alice@example.com", 0.99, "alice@example.com"))));

        assertThat(result.resolvedFields().email).isEqualTo("alice@example.com");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void routesCompactSpacedNameCandidateWithItsFocusedRegion() {
        final BoundingBox nameBox = new BoundingBox(10, 10, 170, 35);
        final LayoutTextCandidate candidate =
                new LayoutTextCandidate("王小明", List.of("b0", "b1", "b2"), nameBox, 0.98);
        final LayoutLine line = new LayoutLine(
                1,
                "name-line",
                List.of(),
                nameBox,
                0.98,
                "王 | 小 | 明",
                List.of(candidate));

        final RuleEngineResult result = engine.classify(new LayoutDocument(List.of(line)));

        assertThat(result.ambiguities()).singleElement().satisfies(ambiguity -> {
            assertThat(ambiguity.reason()).isEqualTo(AmbiguityReason.POSSIBLE_PERSON_NAME);
            assertThat(ambiguity.candidateText()).isEqualTo("王小明");
            assertThat(ambiguity.focusBox()).isEqualTo(nameBox);
        });
    }

    @Test
    void doesNotTreatCompanyTermAsPersonName() {
        final BoundingBox box = new BoundingBox(10, 10, 120, 35);
        final LayoutLine line = new LayoutLine(
                1,
                "company-line",
                List.of(),
                box,
                0.98,
                "王氏企業",
                List.of(new LayoutTextCandidate("王氏企業", List.of("b0"), box, 0.98)));

        final RuleEngineResult result = engine.classify(new LayoutDocument(List.of(line)));

        assertThat(result.ambiguities())
                .extracting(AmbiguousRegion::reason)
                .containsExactly(AmbiguityReason.UNCLASSIFIED_TEXT);
    }

    private static LayoutLine line(final String text, final double confidence, final String... blocks) {
        final List<OcrBlock> ocrBlocks = java.util.stream.IntStream.range(0, blocks.length)
                .mapToObj(index -> new OcrBlock(
                        "b" + index,
                        1,
                        blocks[index],
                        new BoundingBox(index * 60, 10, index * 60 + 50, 30),
                        confidence))
                .toList();
        return new LayoutLine(1, "line", ocrBlocks, new BoundingBox(0, 10, blocks.length * 60, 30), confidence, text);
    }
}
