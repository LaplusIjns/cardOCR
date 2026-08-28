package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutReconstructionServiceTest {
    private final LayoutReconstructionService service = new LayoutReconstructionService();

    @Test
    void rebuildsNearbyHorizontalBlocksIntoOneLineBeforeClassification() {
        final OcrDocument document = new OcrDocument(List.of(new OcrPage(
                1,
                800,
                400,
                List.of(
                        new OcrBlock("fax-label", 1, "F", new BoundingBox(20, 100, 35, 125), 0.99),
                        new OcrBlock("fax-number", 1, "03-12345678", new BoundingBox(48, 101, 190, 126), 0.97),
                        new OcrBlock("name", 1, "王小明", new BoundingBox(20, 30, 100, 55), 0.98)),
                null)));

        final LayoutDocument layout = service.reconstruct(document);

        assertThat(layout.lines()).hasSize(2);
        assertThat(layout.lines().get(1).text()).isEqualTo("F | 03-12345678");
        assertThat(layout.lines().get(1).blocks())
                .extracting(block -> block.id())
                .containsExactly("fax-label", "fax-number");
    }

    @Test
    void keepsRawBlocksAndCreatesCompactCandidateForWidelySpacedName() {
        final OcrDocument document = new OcrDocument(List.of(new OcrPage(
                1,
                800,
                400,
                List.of(
                        new OcrBlock("surname", 1, "王", new BoundingBox(20, 30, 45, 55), 0.99),
                        new OcrBlock("given-1", 1, "小", new BoundingBox(95, 31, 120, 56), 0.97),
                        new OcrBlock("given-2", 1, "明", new BoundingBox(170, 30, 195, 55), 0.98)),
                null)));

        final LayoutLine line = service.reconstruct(document).lines().getFirst();

        assertThat(line.text()).isEqualTo("王 | 小 | 明");
        assertThat(line.blocks()).extracting(OcrBlock::id).containsExactly("surname", "given-1", "given-2");
        assertThat(line.compactTextCandidates())
                .filteredOn(candidate -> candidate.text().equals("王小明"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.blockIds()).containsExactly("surname", "given-1", "given-2");
                    assertThat(candidate.boundingBox()).isEqualTo(new BoundingBox(20, 30, 195, 56));
                });
    }

    @Test
    void doesNotCompactCjkBlocksWhoseHorizontalDistanceIsTooLarge() {
        final OcrDocument document = new OcrDocument(List.of(new OcrPage(
                1,
                800,
                400,
                List.of(
                        new OcrBlock("left", 1, "王", new BoundingBox(20, 30, 45, 55), 0.99),
                        new OcrBlock("right", 1, "明", new BoundingBox(400, 30, 425, 55), 0.99)),
                null)));

        final LayoutLine line = service.reconstruct(document).lines().getFirst();

        assertThat(line.text()).isEqualTo("王 | 明");
        assertThat(line.compactTextCandidates()).extracting(LayoutTextCandidate::text).doesNotContain("王明");
    }

    @Test
    void neverCombinesBlocksFromDifferentPages() {
        final OcrDocument document = new OcrDocument(List.of(
                new OcrPage(1, 100, 100, List.of(new OcrBlock("p1", 1, "A", new BoundingBox(0, 0, 10, 10), 1)), null),
                new OcrPage(2, 100, 100, List.of(new OcrBlock("p2", 2, "B", new BoundingBox(0, 0, 10, 10), 1)), null)));

        assertThat(service.reconstruct(document).lines())
                .extracting(LayoutLine::pageNumber)
                .containsExactly(1, 2);
    }
}
