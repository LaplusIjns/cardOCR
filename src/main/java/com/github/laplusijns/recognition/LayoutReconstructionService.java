package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LayoutReconstructionService {
    private static final double MIN_VERTICAL_OVERLAP = 0.40;
    private static final double MIN_CENTER_TOLERANCE = 6.0;

    public LayoutDocument reconstruct(final OcrDocument document) {
        final List<LayoutLine> reconstructed = new ArrayList<>();
        for (final OcrPage page : document.pages()) {
            reconstructed.addAll(reconstructPage(page));
        }
        return new LayoutDocument(reconstructed);
    }

    private static List<LayoutLine> reconstructPage(final OcrPage page) {
        final List<OcrBlock> sorted = page.blocks().stream()
                .filter(block -> !block.text().isBlank())
                .sorted(Comparator.comparingDouble(
                                (OcrBlock block) -> block.boundingBox().top())
                        .thenComparingDouble(block -> block.boundingBox().left()))
                .toList();
        final List<MutableLine> lines = new ArrayList<>();
        for (final OcrBlock block : sorted) {
            MutableLine best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (final MutableLine line : lines) {
                if (!sameLine(line.boundingBox, block.boundingBox())) continue;
                final double distance = Math.abs(
                        line.boundingBox.centerY() - block.boundingBox().centerY());
                if (distance < bestDistance) {
                    best = line;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                lines.add(new MutableLine(block));
            } else {
                best.add(block);
            }
        }
        lines.sort(Comparator.comparingDouble((MutableLine line) -> line.boundingBox.top())
                .thenComparingDouble(line -> line.boundingBox.left()));

        final List<LayoutLine> result = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            final MutableLine line = lines.get(index);
            line.blocks.sort(
                    Comparator.comparingDouble(block -> block.boundingBox().left()));
            final double confidence = line.blocks.stream()
                    .mapToDouble(OcrBlock::confidence)
                    .average()
                    .orElse(0.0);
            final String text =
                    String.join(" | ", line.blocks.stream().map(OcrBlock::text).toList());
            result.add(new LayoutLine(
                    page.pageNumber(),
                    page.pageNumber() + "-line-" + index,
                    line.blocks,
                    line.boundingBox,
                    confidence,
                    text));
        }
        return result;
    }

    private static boolean sameLine(final BoundingBox line, final BoundingBox block) {
        final double overlap =
                Math.max(0.0, Math.min(line.bottom(), block.bottom()) - Math.max(line.top(), block.top()));
        final double minimumHeight = Math.max(1.0, Math.min(line.height(), block.height()));
        if (overlap / minimumHeight >= MIN_VERTICAL_OVERLAP) return true;
        final double tolerance = Math.max(MIN_CENTER_TOLERANCE, minimumHeight * 0.6);
        return Math.abs(line.centerY() - block.centerY()) <= tolerance;
    }

    private static final class MutableLine {
        private final List<OcrBlock> blocks = new ArrayList<>();
        private BoundingBox boundingBox;

        private MutableLine(final OcrBlock firstBlock) {
            blocks.add(firstBlock);
            boundingBox = firstBlock.boundingBox();
        }

        private void add(final OcrBlock block) {
            blocks.add(block);
            boundingBox = boundingBox.union(block.boundingBox());
        }
    }
}
