package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.ocr.OcrBlock;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageCropServiceTest {
    @Test
    void cropsOnlyTheQuestionableBoundingBoxInsteadOfSendingTheWholeImage() throws Exception {
        final BufferedImage original = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(original, "png", encoded);
        final BoundingBox question = new BoundingBox(50, 30, 90, 50);
        final LayoutLine line = new LayoutLine(1, "line-1", List.of(), question, 0.7, "P | 03-12345678");
        final OcrDocument ocr = new OcrDocument(List.of(new OcrPage(1, 200, 100, List.of(), null)));

        final List<CroppedImage> crops = new ImageCropService().cropAmbiguousRegions(
                new DocumentInput("image/png", encoded.toByteArray()),
                ocr,
                List.of(new AmbiguousRegion(line, AmbiguityReason.UNKNOWN_LABEL)));

        assertThat(crops).hasSize(1);
        final BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(crops.getFirst().bytes()));
        assertThat(cropped.getWidth()).isLessThan(original.getWidth());
        assertThat(cropped.getHeight()).isLessThan(original.getHeight());
        assertThat(crops.getFirst().sourceBox()).isEqualTo(question);
    }

    @Test
    void prioritizesPossibleNameCropWhenCropLimitIsReached() throws Exception {
        final BufferedImage original = new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(original, "png", encoded);
        final BoundingBox nameBox = new BoundingBox(120, 40, 280, 75);
        final LayoutLine nameLine = new LayoutLine(
                1,
                "name",
                List.of(new OcrBlock("name-block", 1, "王小明", nameBox, 0.99)),
                nameBox,
                0.99,
                "王 | 小 | 明");
        final List<AmbiguousRegion> ambiguities = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            final BoundingBox box = new BoundingBox(20, 100 + index * 30, 100, 120 + index * 30);
            ambiguities.add(new AmbiguousRegion(
                    new LayoutLine(1, "low-" + index, List.of(), box, 0.4, "模糊"),
                    AmbiguityReason.LOW_OCR_CONFIDENCE));
        }
        ambiguities.add(new AmbiguousRegion(
                nameLine, AmbiguityReason.POSSIBLE_PERSON_NAME, nameBox, "王小明"));
        final OcrDocument ocr = new OcrDocument(List.of(new OcrPage(1, 500, 300, List.of(), null)));

        final List<CroppedImage> crops = new ImageCropService().cropAmbiguousRegions(
                new DocumentInput("image/png", encoded.toByteArray()), ocr, ambiguities);

        assertThat(crops).hasSize(4);
        assertThat(crops.getFirst().sourceBox()).isEqualTo(nameBox);
    }

    @Test
    void expandsIsolatedSurnameCropToIncludeUndetectedAdjacentGlyphs() throws Exception {
        final BufferedImage original = new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(original, "png", encoded);
        final BoundingBox surnameBox = new BoundingBox(200, 100, 225, 125);
        final LayoutLine line = new LayoutLine(1, "surname", List.of(), surnameBox, 0.90, "王");
        final OcrDocument ocr = new OcrDocument(List.of(new OcrPage(1, 500, 300, List.of(), null)));

        final List<CroppedImage> crops = new ImageCropService().cropAmbiguousRegions(
                new DocumentInput("image/png", encoded.toByteArray()),
                ocr,
                List.of(new AmbiguousRegion(
                        line, AmbiguityReason.POSSIBLE_PERSON_NAME, surnameBox, "王")));

        final BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(crops.getFirst().bytes()));
        assertThat(cropped.getWidth()).isGreaterThan(300).isLessThan(original.getWidth());
        assertThat(cropped.getHeight()).isGreaterThan(250).isLessThanOrEqualTo(original.getHeight());
    }

    @Test
    void usesOriginalImageAsCompletePageWhenPaddlePageImageIsUnavailable() throws Exception {
        final BufferedImage original = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(original, "jpg", encoded);
        final DocumentInput input = new DocumentInput("image/jpeg", encoded.toByteArray());
        final OcrDocument ocr =
                new OcrDocument(List.of(new OcrPage(1, 240, 120, List.of(), null)));

        final List<CroppedImage> images = new ImageCropService().fullPageImages(input, ocr);

        assertThat(images).singleElement().satisfies(image -> {
            assertThat(image.pageNumber()).isEqualTo(1);
            assertThat(image.mimeType()).isEqualTo("image/jpeg");
            assertThat(image.sourceBox()).isEqualTo(new BoundingBox(0, 0, 240, 120));
            assertThat(image.bytes()).containsExactly(encoded.toByteArray());
        });
    }

    @Test
    void usesPaddlePageImageForPdfFinalVerification() throws Exception {
        final BufferedImage page = new BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream encodedPage = new ByteArrayOutputStream();
        ImageIO.write(page, "png", encodedPage);
        final DocumentInput input = new DocumentInput("application/pdf", new byte[] {1, 2, 3});
        final OcrDocument ocr = new OcrDocument(List.of(
                new OcrPage(1, 300, 150, List.of(), encodedPage.toByteArray())));

        final List<CroppedImage> images = new ImageCropService().fullPageImages(input, ocr);

        assertThat(images).hasSize(1);
        final CroppedImage image = images.getFirst();
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.sourceBox()).isEqualTo(new BoundingBox(0, 0, 300, 150));
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(300);
        assertThat(decoded.getHeight()).isEqualTo(150);
    }
}
