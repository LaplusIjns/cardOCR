package com.github.laplusijns.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
public class CardVerificationService {

    private static final String SYSTEM_PROMPT = """
            你是繁體中文名片辨識的集中複核系統。

            你會收到原始圖片、OCR evidence、第一次解析結果，以及需要複核的欄位與原因。
            必須重新查看原圖，只修改列出的問題欄位；未列出的欄位必須逐字保留第一次解析結果。
            OCR evidence 只是輔助資料，若與原圖衝突，以原圖為準。
            禁止根據公司常識、網路資料或模糊印象猜測；原圖仍無法確認時將問題欄位回傳空字串。
            請回傳完整的 BusinessCardRecognition。
            """;

    private final ChatClient parserChatClient;
    private final ObjectMapper objectMapper;

    public CardVerificationService(
            @Qualifier("parserChatClient") final ChatClient parserChatClient, final ObjectMapper objectMapper) {
        this.parserChatClient = parserChatClient;
        this.objectMapper = objectMapper;
    }

    public BusinessCardRecognition verify(
            final VisionEvidence evidence,
            final String mimeType,
            final byte[] imageBytes,
            final BusinessCardRecognition initial,
            final List<CardValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return initial;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image must not be empty");
        }
        final String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(initial);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize initial recognition", exception);
        }

        final String issueText = issues.stream()
                .map(issue -> "- " + issue.field().apiName() + "：" + issue.reason())
                .distinct()
                .collect(Collectors.joining("\n"));
        final String prompt = """
                需要集中複核的欄位：
                %s

                第一次解析結果：
                %s

                <vision-evidence>
                %s
                </vision-evidence>
                """.formatted(issueText, resultJson, evidence.content());

        final BusinessCardRecognition result = parserChatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(user -> user.text(prompt)
                        .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                .call()
                .entity(
                        BusinessCardRecognition.class,
                        spec -> spec.useProviderStructuredOutput().validateSchema());
        return result == null ? initial : result;
    }
}
