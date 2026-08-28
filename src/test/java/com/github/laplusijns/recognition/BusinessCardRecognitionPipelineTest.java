package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.laplusijns.ocr.BoundingBox;
import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.ocr.OcrBlock;
import com.github.laplusijns.ocr.OcrClient;
import com.github.laplusijns.ocr.OcrDocument;
import com.github.laplusijns.ocr.OcrPage;
import com.github.laplusijns.ocr.TraditionalChineseOcrNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessCardRecognitionPipelineTest {
    private final SemanticDisambiguator disambiguator = mock(SemanticDisambiguator.class);

    @Test
    void skipsOpenAiWhenJavaRulesResolveEveryLine() {
        final BusinessCardRecognitionPipeline pipeline = pipeline(
                document(List.of(block("label", "F", 10, 20, 0.99), block("number", "03-12345678", 40, 20, 0.98))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
        assertThat(result.openAiUsed()).isFalse();
        verify(disambiguator, never()).resolve(any(), any(), any());
    }

    @Test
    void modelCanFillAmbiguousFieldsButCannotOverwriteDeterministicFax() {
        final BusinessCardRecognition semantic = new BusinessCardRecognition();
        semantic.companyName = "範例股份有限公司";
        semantic.fax = "02-0000-0000";
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("company", "範例股份有限公司", 10, 10, 0.99),
                block("label", "F", 10, 50, 0.99),
                block("number", "03-12345678", 40, 50, 0.99))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.openAiUsed()).isTrue();
        assertThat(result.businessCard().companyName).isEqualTo("範例股份有限公司");
        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
    }

    @Test
    void convertsSimplifiedPaddleTextBeforeLayoutAndRules() {
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("label", "传真", 10, 20, 0.99), block("number", "03-12345678", 50, 20, 0.98))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.ocrDocument().blocks().getFirst().text()).isEqualTo("傳真");
        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
        assertThat(result.openAiUsed()).isFalse();
        verify(disambiguator, never()).resolve(any(), any(), any());
    }

    private BusinessCardRecognitionPipeline pipeline(final OcrDocument document) {
        final OcrClient client = input -> document;
        return new BusinessCardRecognitionPipeline(
                client,
                new TraditionalChineseOcrNormalizer(),
                new LayoutReconstructionService(),
                new BusinessCardRuleEngine(new SemanticNormalizer(), 0.85),
                new ImageCropService(),
                disambiguator,
                new BusinessCardValidator());
    }

    private static OcrDocument document(final List<OcrBlock> blocks) {
        return new OcrDocument(List.of(new OcrPage(1, 500, 200, blocks, null)));
    }

    private static OcrBlock block(
            final String id, final String text, final double left, final double top, final double confidence) {
        return new OcrBlock(id, 1, text, new BoundingBox(left, top, left + 25, top + 20), confidence);
    }
}
