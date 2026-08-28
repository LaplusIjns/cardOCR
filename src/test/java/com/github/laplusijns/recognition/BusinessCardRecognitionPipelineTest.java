package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.Set;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class BusinessCardRecognitionPipelineTest {
    private final SemanticDisambiguator disambiguator = mock(SemanticDisambiguator.class);
    private final MissingFieldVisionVerifier finalVerifier = mock(MissingFieldVisionVerifier.class);

    @Test
    void runsFullImageFinalVerificationWhenCoreFieldsRemainEmpty() {
        final BusinessCardRecognition verified = completeCoreFields();
        verified.fax = "02-0000-0000";
        when(finalVerifier.verify(any(), any(), any(), any())).thenReturn(verified);
        final BusinessCardRecognitionPipeline pipeline = pipeline(
                document(List.of(block("label", "F", 10, 20, 0.99), block("number", "03-12345678", 40, 20, 0.98))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
        assertThat(result.businessCard().name).isEqualTo("王小明");
        assertThat(result.openAiUsed()).isTrue();
        verify(disambiguator, never()).resolve(any(), any(), any());
        final ArgumentCaptor<Set<FieldType>> missingFields = ArgumentCaptor.forClass(Set.class);
        final ArgumentCaptor<List<CroppedImage>> fullPageImages = ArgumentCaptor.forClass(List.class);
        verify(finalVerifier).verify(any(), any(), missingFields.capture(), fullPageImages.capture());
        assertThat(missingFields.getValue())
                .containsExactlyInAnyOrder(FieldType.COMPANY_NAME, FieldType.NAME, FieldType.JOB_TITLE);
        assertThat(fullPageImages.getValue()).singleElement().satisfies(image -> {
            assertThat(image.mimeType()).isEqualTo("image/png");
            assertThat(image.bytes()).containsExactly(1);
        });
    }

    @Test
    void modelCanFillAmbiguousFieldsButCannotOverwriteDeterministicFax() {
        final BusinessCardRecognition semantic = new BusinessCardRecognition();
        semantic.companyName = "範例股份有限公司";
        semantic.fax = "02-0000-0000";
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognition verified = completeCoreFields();
        verified.companyName = "不可覆寫公司";
        verified.fax = "02-1111-1111";
        when(finalVerifier.verify(any(), any(), any(), any())).thenReturn(verified);
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("company", "範例股份有限公司", 10, 10, 0.99),
                block("label", "F", 10, 50, 0.99),
                block("number", "03-12345678", 40, 50, 0.99))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.openAiUsed()).isTrue();
        assertThat(result.businessCard().companyName).isEqualTo("範例股份有限公司");
        assertThat(result.businessCard().name).isEqualTo("王小明");
        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
        final ArgumentCaptor<Set<FieldType>> missingFields = ArgumentCaptor.forClass(Set.class);
        verify(finalVerifier).verify(any(), any(), missingFields.capture(), any());
        assertThat(missingFields.getValue()).doesNotContain(FieldType.COMPANY_NAME);
    }

    @Test
    void convertsSimplifiedPaddleTextBeforeLayoutAndRules() {
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("label", "传真", 10, 20, 0.99), block("number", "03-12345678", 50, 20, 0.98))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.ocrDocument().blocks().getFirst().text()).isEqualTo("傳真");
        assertThat(result.businessCard().fax).isEqualTo("03-12345678");
        assertThat(result.openAiUsed()).isTrue();
        verify(disambiguator, never()).resolve(any(), any(), any());
        verify(finalVerifier).verify(any(), any(), any(), any());
    }

    @Test
    void fillsNameFromWidelySpacedOcrBlocksWithoutDiscardingRawLayout() {
        final BusinessCardRecognition semantic = new BusinessCardRecognition();
        semantic.name = "王小明";
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("surname", "王", 10, 20, 0.99),
                block("given-1", "小", 75, 20, 0.98),
                block("given-2", "明", 140, 20, 0.97))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().name).isEqualTo("王小明");
        assertThat(result.layoutDocument().lines().getFirst().text()).isEqualTo("王 | 小 | 明");
        assertThat(result.layoutDocument().lines().getFirst().compactTextCandidates())
                .extracting(LayoutTextCandidate::text)
                .contains("王小明");
        assertThat(result.ruleEngineResult().ambiguities())
                .extracting(AmbiguousRegion::reason)
                .containsExactly(AmbiguityReason.POSSIBLE_PERSON_NAME);
        verify(disambiguator).resolve(any(), any(), any());
    }

    @Test
    void fillsNameWhenSpacedGlyphsAreRecognizedAsLatinO() {
        final BusinessCardRecognition semantic = new BusinessCardRecognition();
        semantic.name = "王OO";
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognitionPipeline pipeline = pipeline(document(List.of(
                block("surname", "王", 10, 20, 0.99),
                block("given-1", "O", 75, 20, 0.82),
                block("given-2", "O", 140, 20, 0.80))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().name).isEqualTo("王OO");
        assertThat(result.layoutDocument().lines().getFirst().text()).isEqualTo("王 | O | O");
        assertThat(result.layoutDocument().lines().getFirst().compactTextCandidates())
                .extracting(LayoutTextCandidate::text)
                .contains("王OO");
        assertThat(result.ruleEngineResult().ambiguities())
                .extracting(AmbiguousRegion::reason)
                .containsExactly(AmbiguityReason.POSSIBLE_PERSON_NAME);
    }

    @Test
    void canRecoverNameFromVisionWhenOcrOnlyDetectsSurname() {
        final BusinessCardRecognition semantic = new BusinessCardRecognition();
        semantic.name = "王OO";
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognitionPipeline pipeline =
                pipeline(document(List.of(block("surname", "王", 100, 40, 0.92))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().name).isEqualTo("王OO");
        assertThat(result.ruleEngineResult().ambiguities()).singleElement().satisfies(ambiguity -> {
            assertThat(ambiguity.reason()).isEqualTo(AmbiguityReason.POSSIBLE_PERSON_NAME);
            assertThat(ambiguity.candidateText()).isEqualTo("王");
        });
        verify(disambiguator).resolve(any(), any(), any());
    }

    @Test
    void skipsFinalVerificationWhenFirstSemanticPassFillsEveryCoreField() {
        final BusinessCardRecognition semantic = completeCoreFields();
        when(disambiguator.resolve(any(), any(), any())).thenReturn(semantic);
        final BusinessCardRecognitionPipeline pipeline =
                pipeline(document(List.of(block("company", "範例股份有限公司", 10, 10, 0.99))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().companyName).isEqualTo("範例股份有限公司");
        assertThat(result.businessCard().name).isEqualTo("王小明");
        assertThat(result.businessCard().jobTitle).isEqualTo("經理");
        verify(finalVerifier, never()).verify(any(), any(), any(), any());
    }

    @Test
    void invokesFinalVerificationAtMostOnceEvenWhenFieldsRemainMissing() {
        when(finalVerifier.verify(any(), any(), any(), any())).thenReturn(new BusinessCardRecognition());
        final BusinessCardRecognitionPipeline pipeline = pipeline(
                document(List.of(block("label", "F", 10, 20, 0.99), block("number", "03-12345678", 40, 20, 0.98))));

        final RecognitionResult result = pipeline.recognize(new DocumentInput("image/png", new byte[] {1}));

        assertThat(result.businessCard().name).isEmpty();
        verify(finalVerifier, times(1)).verify(any(), any(), any(), any());
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
                finalVerifier,
                new BusinessCardValidator());
    }

    private static BusinessCardRecognition completeCoreFields() {
        final BusinessCardRecognition fields = new BusinessCardRecognition();
        fields.companyName = "範例股份有限公司";
        fields.name = "王小明";
        fields.jobTitle = "經理";
        return fields;
    }

    private static OcrDocument document(final List<OcrBlock> blocks) {
        return new OcrDocument(List.of(new OcrPage(1, 500, 200, blocks, null)));
    }

    private static OcrBlock block(
            final String id, final String text, final double left, final double top, final double confidence) {
        return new OcrBlock(id, 1, text, new BoundingBox(left, top, left + 25, top + 20), confidence);
    }
}
