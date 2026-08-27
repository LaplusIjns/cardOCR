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
    void neverCombinesBlocksFromDifferentPages() {
        final OcrDocument document = new OcrDocument(List.of(
                new OcrPage(1, 100, 100, List.of(new OcrBlock("p1", 1, "A", new BoundingBox(0, 0, 10, 10), 1)), null),
                new OcrPage(2, 100, 100, List.of(new OcrBlock("p2", 2, "B", new BoundingBox(0, 0, 10, 10), 1)), null)));

        assertThat(service.reconstruct(document).lines())
                .extracting(LayoutLine::pageNumber)
                .containsExactly(1, 2);
    }
}
