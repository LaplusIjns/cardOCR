package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CardRecognitionPipelineTest {

    private final CardVisionService cardVisionService = mock(CardVisionService.class);
    private final CardParsingService cardParsingService = mock(CardParsingService.class);
    private final CardRecognitionPipeline pipeline = new CardRecognitionPipeline(cardVisionService, cardParsingService);

    @Test
    void passesVisionEvidenceToTheTextParserInOrder() {
        final byte[] image = {1, 2, 3};
        final VisionEvidence evidence = new VisionEvidence("左上：範例股份有限公司");
        final BusinessCardRecognition expected = new BusinessCardRecognition();
        expected.companyName = "範例股份有限公司";
        when(cardVisionService.understand("image/png", image)).thenReturn(evidence);
        when(cardParsingService.parse(evidence)).thenReturn(expected);

        final BusinessCardRecognition result = pipeline.recognize("image/png", image);

        assertThat(result).isSameAs(expected);
        final InOrder calls = inOrder(cardVisionService, cardParsingService);
        calls.verify(cardVisionService).understand("image/png", image);
        calls.verify(cardParsingService).parse(evidence);
        verifyNoMoreInteractions(cardVisionService, cardParsingService);
    }

    @Test
    void visionEvidenceRejectsBlankContentAndTrimsValidContent() {
        assertThat(new VisionEvidence("  可見文字  ").content()).isEqualTo("可見文字");
        assertThatThrownBy(() -> new VisionEvidence("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
