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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class ImageCropService {
    private static final int MAX_CROPS = 4;
    private static final int MAX_FULL_PAGE_IMAGES = 4;

    public List<CroppedImage> cropAmbiguousRegions(
            final DocumentInput input, final OcrDocument ocrDocument, final List<AmbiguousRegion> ambiguities) {
        final List<CroppedImage> crops = new ArrayList<>();
        final Set<String> croppedLines = new HashSet<>();
        final List<AmbiguousRegion> prioritized = ambiguities.stream()
                .filter(ImageCropService::needsVision)
                .sorted(Comparator.comparingInt(ImageCropService::cropPriority))
                .toList();
        for (final AmbiguousRegion ambiguity : prioritized) {
            if (crops.size() >= MAX_CROPS) break;
            if (!croppedLines.add(ambiguity.line().id())) continue;
            final OcrPage page = ocrDocument.pages().stream()
                    .filter(candidate ->
                            candidate.pageNumber() == ambiguity.line().pageNumber())
                    .findFirst()
                    .orElse(null);
            if (page == null) continue;
            final byte[] sourceBytes = page.pageImage().length > 0
                    ? page.pageImage()
                    : (!input.isPdf() && page.pageNumber() == 1 ? input.bytes() : new byte[0]);
            final CroppedImage crop = crop(page.pageNumber(), sourceBytes, ambiguity);
            if (crop != null) crops.add(crop);
        }
        return List.copyOf(crops);
    }

    public List<CroppedImage> fullPageImages(
            final DocumentInput input, final OcrDocument ocrDocument) {
        final List<CroppedImage> images = new ArrayList<>();
        for (final OcrPage page : ocrDocument.pages()) {
            if (images.size() >= MAX_FULL_PAGE_IMAGES) break;
            final byte[] pageBytes = page.pageImage();
            final CroppedImage image;
            if (pageBytes.length > 0) {
                image = fullPagePng(page.pageNumber(), pageBytes);
            } else if (!input.isPdf() && page.pageNumber() == 1) {
                image = new CroppedImage(
                        page.pageNumber(),
                        fullPageBox(page.width(), page.height()),
                        input.mimeType(),
                        input.bytes());
            } else {
                image = null;
            }
            if (image != null) images.add(image);
        }
        if (images.isEmpty() && !input.isPdf()) {
            images.add(new CroppedImage(
                    1, fullPageBox(0, 0), input.mimeType(), input.bytes()));
        }
        return List.copyOf(images);
    }

    private static boolean needsVision(final AmbiguousRegion ambiguity) {
        return ambiguity.reason() == AmbiguityReason.LOW_OCR_CONFIDENCE
                || ambiguity.reason() == AmbiguityReason.UNKNOWN_LABEL
                || ambiguity.reason() == AmbiguityReason.INVALID_LABELED_VALUE
                || ambiguity.reason() == AmbiguityReason.POSSIBLE_PERSON_NAME;
    }

    private static int cropPriority(final AmbiguousRegion ambiguity) {
        return switch (ambiguity.reason()) {
            case POSSIBLE_PERSON_NAME -> 0;
            case LOW_OCR_CONFIDENCE -> 1;
            case UNKNOWN_LABEL, INVALID_LABELED_VALUE -> 2;
            default -> 3;
        };
    }

    private static CroppedImage crop(
            final int pageNumber, final byte[] sourceBytes, final AmbiguousRegion ambiguity) {
        final BoundingBox box = ambiguity.focusBox();
        if (sourceBytes.length == 0 || box.width() <= 0 || box.height() <= 0) return null;
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (image == null) return null;
            final boolean isolatedSurname = ambiguity.reason() == AmbiguityReason.POSSIBLE_PERSON_NAME
                    && ambiguity.candidateText().codePointCount(0, ambiguity.candidateText().length()) == 1;
            final double paddingRatio = isolatedSurname
                    ? 6.0
                    : ambiguity.reason() == AmbiguityReason.POSSIBLE_PERSON_NAME ? 0.30 : 0.15;
            final int padding = Math.max(
                    8, (int) Math.ceil(Math.max(box.width(), box.height()) * paddingRatio));
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

    private static CroppedImage fullPagePng(final int pageNumber, final byte[] sourceBytes) {
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (image == null) return null;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return new CroppedImage(
                    pageNumber,
                    fullPageBox(image.getWidth(), image.getHeight()),
                    "image/png",
                    output.toByteArray());
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static BoundingBox fullPageBox(final int width, final int height) {
        return new BoundingBox(0, 0, Math.max(1, width), Math.max(1, height));
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
