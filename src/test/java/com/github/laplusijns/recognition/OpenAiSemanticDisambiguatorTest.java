package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.laplusijns.ocr.BoundingBox;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiSemanticDisambiguatorTest {
    @Test
    void usesResponsesStructuredOutputAndOnlySendsProvidedLocalCrop() throws Exception {
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            final byte[] response = """
                    {"status":"completed","output":[{"type":"message","content":[
                      {"type":"output_text","text":"{\\"companyName\\":\\"範例公司\\",\\"name\\":\\"\\",\\"jobTitle\\":\\"\\",\\"telephone\\":\\"\\",\\"mobilePhone\\":\\"\\",\\"fax\\":\\"03-12345678\\",\\"email\\":\\"\\",\\"address\\":\\"\\",\\"businessNumber\\":\\"\\",\\"stockCode\\":\\"\\",\\"companyWebsite\\":\\"\\"}"}
                    ]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            final OpenAiSemanticDisambiguator client = new OpenAiSemanticDisambiguator(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model");
            final BoundingBox nameBox = new BoundingBox(10, 20, 180, 50);
            final LayoutTextCandidate nameCandidate =
                    new LayoutTextCandidate("王小明", List.of("name-0", "name-1", "name-2"), nameBox, 0.98);
            final LayoutLine nameLine = new LayoutLine(
                    1,
                    "name-line",
                    List.of(),
                    nameBox,
                    0.98,
                    "王 | 小 | 明",
                    List.of(nameCandidate));
            final LayoutDocument layout = new LayoutDocument(List.of(nameLine));
            final RuleEngineResult rules = new RuleEngineResult(
                    new BusinessCardRecognition(),
                    Set.of(),
                    List.of(new AmbiguousRegion(
                            nameLine, AmbiguityReason.POSSIBLE_PERSON_NAME, nameBox, "王小明")));
            final CroppedImage crop = new CroppedImage(1, new BoundingBox(1, 2, 3, 4), "image/png", new byte[] {1, 2});

            final BusinessCardRecognition result = client.resolve(layout, rules, List.of(crop));

            assertThat(result.companyName).isEqualTo("範例公司");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(requestBody.get())
                    .contains("\"type\":\"json_schema\"")
                    .contains("\"strict\":true")
                    .contains("\\\"compactTextCandidates\\\"")
                    .contains("\\\"candidateText\\\":\\\"王小明\\\"")
                    .contains("王 | 小 | 明")
                    .contains("O、o、0、○")
                    .contains("\"type\":\"input_image\"")
                    .contains("data:image/png;base64,AQI=");
        } finally {
            server.stop(0);
        }
    }
}
