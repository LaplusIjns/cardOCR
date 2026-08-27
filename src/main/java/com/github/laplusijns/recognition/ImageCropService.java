package com.github.laplusijns.recognition;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class ImageCropService {
    private static final int MAX_CROPS = 4;

    public List<CroppedImage> cropAmbiguousRegions(
            final DocumentInput input, final OcrDocument ocrDocument, final List<AmbiguousRegion> ambiguities) {
        final List<CroppedImage> crops = new ArrayList<>();
        final Set<String> croppedLines = new HashSet<>();
        for (final AmbiguousRegion ambiguity : ambiguities) {
            if (crops.size() >= MAX_CROPS) break;
            if (!needsVision(ambiguity) || !croppedLines.add(ambiguity.line().id())) continue;
            final OcrPage page = ocrDocument.pages().stream()
                    .filter(candidate ->
                            candidate.pageNumber() == ambiguity.line().pageNumber())
                    .findFirst()
                    .orElse(null);
            if (page == null) continue;
            final byte[] sourceBytes = page.pageImage().length > 0
                    ? page.pageImage()
                    : (!input.isPdf() && page.pageNumber() == 1 ? input.bytes() : new byte[0]);
            final CroppedImage crop =
                    crop(page.pageNumber(), sourceBytes, ambiguity.line().boundingBox());
            if (crop != null) crops.add(crop);
        }
        return List.copyOf(crops);
    }

    private static boolean needsVision(final AmbiguousRegion ambiguity) {
        return ambiguity.reason() == AmbiguityReason.LOW_OCR_CONFIDENCE
                || ambiguity.reason() == AmbiguityReason.UNKNOWN_LABEL
                || ambiguity.reason() == AmbiguityReason.INVALID_LABELED_VALUE;
    }

    private static CroppedImage crop(final int pageNumber, final byte[] sourceBytes, final BoundingBox box) {
        if (sourceBytes.length == 0 || box.width() <= 0 || box.height() <= 0) return null;
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (image == null) return null;
            final int padding = Math.max(8, (int) Math.ceil(Math.max(box.width(), box.height()) * 0.15));
            final int left = clamp((int) Math.floor(box.left()) - padding, 0, image.getWidth() - 1);
            final int top = clamp((int) Math.floor(box.top()) - padding, 0, image.getHeight() - 1);
            final int right = clamp((int) Math.ceil(box.right()) + padding, left + 1, image.getWidth());
            final int bottom = clamp((int) Math.ceil(box.bottom()) + padding, top + 1, image.getHeight());
            final BufferedImage cropped = image.getSubimage(left, top, right - left, bottom - top);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", output);
            return new CroppedImage(pageNumber, box, "image/png", output.toByteArray());
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
