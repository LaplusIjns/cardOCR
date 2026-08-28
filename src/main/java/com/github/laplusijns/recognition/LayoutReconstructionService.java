package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.OcrBlock;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LayoutReconstructionService {
    private static final double MIN_VERTICAL_OVERLAP = 0.40;
    private static final double MIN_CENTER_TOLERANCE = 6.0;
    private static final double MAX_COMPACT_GAP_SCALE = 3.5;
    private static final double MAX_COMPACT_WIDTH_PER_CHARACTER_SCALE = 4.0;
    private static final int MAX_COMPACT_CHARACTERS = 6;

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
                    text,
                    compactTextCandidates(line.blocks)));
        }
        return result;
    }

    private static List<LayoutTextCandidate> compactTextCandidates(final List<OcrBlock> blocks) {
        final List<LayoutTextCandidate> candidates = new ArrayList<>();
        for (int start = 0; start < blocks.size(); start++) {
            final OcrBlock first = blocks.get(start);
            final String firstText = compactNameGlyphText(first.text());
            if (firstText.isEmpty()) continue;

            final int firstCharacters = characterCount(firstText);
            if (firstCharacters <= MAX_COMPACT_CHARACTERS
                    && containsCjkCharacter(firstText)) {
                candidates.add(candidate(firstText, List.of(first), first.boundingBox(), first.confidence()));
            }

            final List<OcrBlock> candidateBlocks = new ArrayList<>();
            candidateBlocks.add(first);
            final StringBuilder joined = new StringBuilder(firstText);
            BoundingBox boundingBox = first.boundingBox();
            double confidenceSum = first.confidence();
            double maximumHeight = Math.max(1.0, first.boundingBox().height());
            int characters = firstCharacters;

            for (int end = start + 1; end < blocks.size(); end++) {
                final OcrBlock previous = blocks.get(end - 1);
                final OcrBlock current = blocks.get(end);
                final String currentText = compactNameGlyphText(current.text());
                if (currentText.isEmpty() || !canCompact(previous, current)) break;

                characters += characterCount(currentText);
                if (characters > MAX_COMPACT_CHARACTERS) break;
                candidateBlocks.add(current);
                joined.append(currentText);
                boundingBox = boundingBox.union(current.boundingBox());
                confidenceSum += current.confidence();
                maximumHeight = Math.max(maximumHeight, current.boundingBox().height());

                final double widthPerCharacter = boundingBox.width() / Math.max(1, characters);
                if (widthPerCharacter > maximumHeight * MAX_COMPACT_WIDTH_PER_CHARACTER_SCALE) break;
                if (containsCjkCharacter(joined)) {
                    candidates.add(candidate(
                            joined.toString(),
                            candidateBlocks,
                            boundingBox,
                            confidenceSum / candidateBlocks.size()));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static LayoutTextCandidate candidate(
            final String text,
            final List<OcrBlock> blocks,
            final BoundingBox boundingBox,
            final double confidence) {
        return new LayoutTextCandidate(
                text,
                blocks.stream().map(OcrBlock::id).toList(),
                boundingBox,
                confidence);
    }

    private static boolean canCompact(final OcrBlock previous, final OcrBlock current) {
        final BoundingBox previousBox = previous.boundingBox();
        final BoundingBox currentBox = current.boundingBox();
        final double maximumHeight = Math.max(1.0, Math.max(previousBox.height(), currentBox.height()));
        final double minimumHeight = Math.max(1.0, Math.min(previousBox.height(), currentBox.height()));
        if (minimumHeight / maximumHeight < 0.55) return false;
        if (Math.abs(previousBox.centerY() - currentBox.centerY()) > maximumHeight * 0.55) return false;

        final double gap = currentBox.left() - previousBox.right();
        final double characterScale = Math.max(
                maximumHeight,
                Math.max(
                        previousBox.width() / Math.max(1, characterCount(compactNameGlyphText(previous.text()))),
                        currentBox.width() / Math.max(1, characterCount(compactNameGlyphText(current.text())))));
        return gap >= -Math.min(previousBox.width(), currentBox.width()) * 0.30
                && gap <= characterScale * MAX_COMPACT_GAP_SCALE;
    }

    private static String compactNameGlyphText(final String text) {
        if (text == null || text.isBlank()) return "";
        final String compact = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s|·•・‧.．]+", "");
        return !compact.isEmpty() && compact.codePoints().allMatch(LayoutReconstructionService::isNameGlyphCharacter)
                ? compact
                : "";
    }

    private static boolean isNameGlyphCharacter(final int codePoint) {
        if (isCjkCharacter(codePoint)) return true;
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.LATIN && Character.isLetter(codePoint)) return true;
        return codePoint == '0'
                || codePoint == '○'
                || codePoint == '◯'
                || codePoint == '●'
                || codePoint == '◎'
                || codePoint == '□'
                || codePoint == '?';
    }

    private static boolean containsCjkCharacter(final CharSequence text) {
        return text.codePoints().anyMatch(LayoutReconstructionService::isCjkCharacter);
    }

    private static boolean isCjkCharacter(final int codePoint) {
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static int characterCount(final String text) {
        return text.codePointCount(0, text.length());
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
