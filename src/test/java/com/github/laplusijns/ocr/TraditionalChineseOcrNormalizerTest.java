package com.github.laplusijns.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TraditionalChineseOcrNormalizerTest {
    private final TraditionalChineseOcrNormalizer normalizer = new TraditionalChineseOcrNormalizer();

    @Test
    void convertsSimplifiedAndMainlandPhrasesToTaiwanTraditionalChinese() {
        assertThat(normalizer.normalizeText("业务经理 使用互联网")).isEqualTo("業務經理 使用網際網路");
    }

    @Test
    void convertsJapaneseShinjitaiKanjiToTraditionalAndKeepsKana() {
        assertThat(normalizer.normalizeText("株式会社 国際営業部 東京駅 さくらテック"))
                .isEqualTo("株式會社 國際營業部 東京驛 さくらテック");
    }

    @Test
    void normalizesMixedSimplifiedChineseAndJapaneseKanji() {
        assertThat(normalizer.normalizeText("业务担当 広沢竜"))
                .isEqualTo("業務擔當 廣澤龍");
    }

    @Test
    void keepsTraditionalEnglishEmailUrlAndNumbersUnchanged() {
        final String original = "範例股份有限公司 service@example.com https://example.com 03-12345678";

        assertThat(normalizer.normalizeText(original)).isEqualTo(original);
    }

    @Test
    void protectsEmailAndUrlIdentifiersWhileConvertingSurroundingChinese() {
        final String original = "业务 株式会社 service@example.com https://example.jp/软件下载/株式会社";

        assertThat(normalizer.normalizeText(original))
                .isEqualTo("業務 株式會社 service@example.com https://example.jp/软件下载/株式会社");
    }

    @Test
    void changesOnlyTextAndPreservesOcrGeometryConfidencePageAndImage() {
        final BoundingBox box = new BoundingBox(10, 20, 120, 45);
        final OcrBlock sourceBlock = new OcrBlock("1-0", 1, "传真番号 東京駅", box, 0.93);
        final byte[] pageImage = {1, 2, 3};
        final OcrDocument source =
                new OcrDocument(List.of(new OcrPage(1, 800, 600, List.of(sourceBlock), pageImage)));

        final OcrDocument result = normalizer.normalize(source);
        final OcrPage page = result.pages().getFirst();
        final OcrBlock block = page.blocks().getFirst();

        assertThat(block.text()).isEqualTo("傳真番號 東京驛");
        assertThat(block.id()).isEqualTo("1-0");
        assertThat(block.pageNumber()).isEqualTo(1);
        assertThat(block.boundingBox()).isSameAs(box);
        assertThat(block.confidence()).isEqualTo(0.93);
        assertThat(page.width()).isEqualTo(800);
        assertThat(page.height()).isEqualTo(600);
        assertThat(page.pageImage()).containsExactly(pageImage);
    }

    @Test
    void reusesDocumentWhenTextNeedsNoNormalization() {
        final OcrDocument source = new OcrDocument(List.of(new OcrPage(
                1,
                100,
                100,
                List.of(new OcrBlock("1-0", 1, "繁體中文", new BoundingBox(0, 0, 10, 10), 1)),
                null)));

        assertThat(normalizer.normalize(source)).isSameAs(source);
    }
}
