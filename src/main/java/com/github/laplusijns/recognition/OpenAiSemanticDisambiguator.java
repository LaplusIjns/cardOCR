package com.github.laplusijns.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiSemanticDisambiguator implements SemanticDisambiguator, MissingFieldVisionVerifier {
    private static final String INSTRUCTIONS = """
            你是名片 OCR 語意判斷器。PaddleOCR 已完成文字與座標辨識，Java 規則引擎也已先處理高確定性欄位。
            你的工作只是在 OCR 行、相鄰區塊、座標、信心值及局部裁圖的上下文中補齊仍有歧義的欄位。
            不可覆寫規則已確定的資料，不可猜測圖片或 OCR 中沒有的內容；無法可靠確認時回傳空字串。
            電話分類必須參考相鄰標籤：T/TEL/Phone 是 telephone，F/FAX 是 fax，M/Mobile 是 mobilePhone。
            若疑似標籤被 OCR 誤讀（例如 F 被讀成 P），應綜合局部圖片、同行與前後行位置判斷。
            compactTextCandidates 是依字框距離建立的排版候選，不是已確認欄位；請結合原始文字、座標及圖片驗證。
            中文姓名可能因刻意拉大字距而被拆成「王 | 小 | 明」；若候選與圖片支持同一姓名，name 應輸出「王小明」，不可保留 | 或排版空白。
            姓名字形也可能被 OCR 讀成 O、o、0、○、全形英文字母或方框；這些符號只是待驗證候選。必須以局部圖片抄錄實際可見字形，不可自行猜成其他中文字；若圖片實際印的是拉丁字母或圓圈，應如實保留。
            """;
    private static final String FINAL_VERIFICATION_INSTRUCTIONS = """
            你是名片欄位的最終視覺確認器。先前 OCR、Java 規則與一次語意判斷已經完成，但核心欄位仍有空白。
            你會收到完整名片頁面、OCR 版面、目前已確認欄位及 missingFields。
            只能從完整圖片中補齊 missingFields 列出的欄位；不可更改、重述或推測其他欄位。
            姓名可能使用很大的字距、直排、旋轉排版，或完全未被 OCR 偵測，必須直接檢查完整圖片。
            圖片無法可靠確認的欄位必須回傳空字串，不可依 Email、公司、常見姓名或上下文臆測。
            回應仍須包含 schema 的所有欄位；不在 missingFields 內的欄位一律回傳空字串，Java 只會合併原本空白的欄位。
            """;
    private static final List<String> FIELD_NAMES = List.of(
            "companyName",
            "name",
            "jobTitle",
            "telephone",
            "mobilePhone",
            "fax",
            "email",
            "address",
            "businessNumber",
            "stockCode",
            "companyWebsite");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    @Autowired
    public OpenAiSemanticDisambiguator(
            @Value("${card-ocr.openai.base-url:${spring.ai.openai.base-url:https://api.openai.com}}")
                    final String baseUrl,
            @Value("${card-ocr.openai.api-key:${spring.ai.openai.api-key:}}") final String apiKey,
            @Value("${card-ocr.openai.model:${spring.ai.openai.chat.options.model:gpt-4o-mini}}") final String model) {
        this(new ObjectMapper(), baseUrl, apiKey, model);
    }

    OpenAiSemanticDisambiguator(
            final ObjectMapper objectMapper, final String baseUrl, final String apiKey, final String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        final String normalizedBaseUrl = stripTrailingSlash(baseUrl);
        this.endpoint = normalizedBaseUrl.endsWith("/v1") ? "/responses" : "/v1/responses";
        this.restClient = RestClient.builder().baseUrl(normalizedBaseUrl).build();
    }

    @Override
    public BusinessCardRecognition resolve(
            final LayoutDocument layout, final RuleEngineResult ruleResult, final List<CroppedImage> croppedImages) {
        requireApiKey("the OCR result contains semantic ambiguities");
        final List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "input_text", "text", contextJson(layout, ruleResult)));
        appendImages(content, croppedImages, "以下局部圖片對應");
        return request(content, INSTRUCTIONS, "business_card_semantic_resolution");
    }

    @Override
    public BusinessCardRecognition verify(
            final LayoutDocument layout,
            final BusinessCardRecognition currentFields,
            final Set<FieldType> missingFields,
            final List<CroppedImage> fullPageImages) {
        requireApiKey("core business-card fields require final visual verification");
        final List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of(
                "type",
                "input_text",
                "text",
                finalVerificationContextJson(layout, currentFields, missingFields)));
        appendImages(content, fullPageImages, "以下完整名片頁面對應");
        return request(content, FINAL_VERIFICATION_INSTRUCTIONS, "business_card_final_verification");
    }

    private BusinessCardRecognition request(
            final List<Map<String, Object>> content, final String instructions, final String schemaName) {

        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("store", false);
        request.put("instructions", instructions);
        request.put("input", List.of(Map.of("role", "user", "content", content)));
        request.put("text", Map.of("format", structuredOutputFormat(schemaName)));

        final String responseBody = restClient
                .post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
        final JsonNode response;
        try {
            response = responseBody == null ? null : objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenAI returned invalid JSON", exception);
        }
        final String outputText = outputText(response);
        try {
            return objectMapper.readValue(outputText, BusinessCardRecognition.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenAI returned invalid structured output", exception);
        }
    }

    private static void appendImages(
            final List<Map<String, Object>> content,
            final List<CroppedImage> images,
            final String labelPrefix) {
        for (final CroppedImage image : images) {
            content.add(Map.of(
                    "type",
                    "input_text",
                    "text",
                    labelPrefix + " page=" + image.pageNumber() + ", sourceBox=" + image.sourceBox()));
            content.add(Map.of(
                    "type",
                    "input_image",
                    "image_url",
                    "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes()),
                    "detail",
                    "high"));
        }
    }

    private String contextJson(final LayoutDocument layout, final RuleEngineResult ruleResult) {
        final List<Map<String, Object>> lines = layoutLines(layout);
        final List<Map<String, Object>> ambiguities = ruleResult.ambiguities().stream()
                .map(ambiguity -> Map.of(
                        "lineId",
                        ambiguity.line().id(),
                        "reason",
                        ambiguity.reason().name(),
                        "candidateText",
                        ambiguity.candidateText(),
                        "focusBox",
                        ambiguity.focusBox()))
                .toList();
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "task",
                    "Resolve only ambiguous business-card fields and return every schema field.",
                    "layoutLines",
                    lines,
                    "ruleResolvedFields",
                    ruleResult.resolvedFields(),
                    "ambiguities",
                    ambiguities));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize OCR context", exception);
        }
    }

    private String finalVerificationContextJson(
            final LayoutDocument layout,
            final BusinessCardRecognition currentFields,
            final Set<FieldType> missingFields) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "task",
                    "Inspect complete business-card images and fill only missingFields.",
                    "missingFields",
                    missingFields.stream().map(OpenAiSemanticDisambiguator::fieldName).toList(),
                    "currentFields",
                    currentFields,
                    "layoutLines",
                    layoutLines(layout)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize final verification context", exception);
        }
    }

    private static List<Map<String, Object>> layoutLines(final LayoutDocument layout) {
        return layout.lines().stream()
                .map(line -> Map.<String, Object>of(
                        "id", line.id(),
                        "page", line.pageNumber(),
                        "text", line.text(),
                        "confidence", line.confidence(),
                        "boundingBox", line.boundingBox(),
                        "compactTextCandidates",
                                line.compactTextCandidates().stream()
                                        .map(candidate -> Map.of(
                                                "text", candidate.text(),
                                                "blockIds", candidate.blockIds(),
                                                "confidence", candidate.confidence(),
                                                "boundingBox", candidate.boundingBox()))
                                        .toList(),
                        "blocks",
                                line.blocks().stream()
                                        .map(block -> Map.of(
                                                "text", block.text(),
                                                "confidence", block.confidence(),
                                                "boundingBox", block.boundingBox()))
                                        .toList()))
                .toList();
    }

    private static String fieldName(final FieldType fieldType) {
        return switch (fieldType) {
            case COMPANY_NAME -> "companyName";
            case NAME -> "name";
            case JOB_TITLE -> "jobTitle";
            case TELEPHONE -> "telephone";
            case MOBILE_PHONE -> "mobilePhone";
            case FAX -> "fax";
            case EMAIL -> "email";
            case ADDRESS -> "address";
            case BUSINESS_NUMBER -> "businessNumber";
            case STOCK_CODE -> "stockCode";
            case COMPANY_WEBSITE -> "companyWebsite";
        };
    }

    private static Map<String, Object> structuredOutputFormat(final String schemaName) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        for (final String field : FIELD_NAMES) properties.put(field, Map.of("type", "string"));
        final Map<String, Object> schema = Map.of(
                "type", "object", "properties", properties, "required", FIELD_NAMES, "additionalProperties", false);
        return Map.of(
                "type", "json_schema", "name", schemaName, "strict", true, "schema", schema);
    }

    private void requireApiKey(final String reason) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is required because " + reason);
        }
    }

    private static String outputText(final JsonNode response) {
        if (response == null) throw new IllegalStateException("OpenAI returned an empty response");
        if (response.hasNonNull("error")) {
            throw new IllegalStateException("OpenAI request failed: "
                    + response.path("error").path("message").asText());
        }
        if (response.path("output_text").isTextual())
            return response.path("output_text").asText();
        for (final JsonNode output : response.path("output")) {
            for (final JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())
                        && content.path("text").isTextual()) {
                    return content.path("text").asText();
                }
                if ("refusal".equals(content.path("type").asText())) {
                    throw new IllegalStateException("OpenAI refused semantic resolution: "
                            + content.path("refusal").asText());
                }
            }
        }
        throw new IllegalStateException("OpenAI response did not contain structured output text");
    }

    private static String stripTrailingSlash(final String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
