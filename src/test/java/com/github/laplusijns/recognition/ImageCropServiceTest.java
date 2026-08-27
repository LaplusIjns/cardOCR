package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.DocumentInput;
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
}
