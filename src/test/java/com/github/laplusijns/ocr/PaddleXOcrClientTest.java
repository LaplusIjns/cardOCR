package com.github.laplusijns.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaddleXOcrClientTest {
    @Test
    void mapsPaddleXTextScoresBoxesAndPdfPageNumbersWithoutFlattening() throws Exception {
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] response = """
                    {"errorCode":0,"errorMsg":"Success","result":{"ocrResults":[
                      {"prunedResult":{"rec_texts":["F","03-12345678"],"rec_scores":[0.99,0.96],
                      "rec_boxes":[[10,20,20,40],[30,20,160,40]]}},
                      {"prunedResult":{"rec_texts":["第二頁"],"rec_scores":[0.91],
                      "rec_polys":[[[5,6],[50,6],[50,26],[5,26]]]}}
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            final PaddleXOcrClient client =
                    new PaddleXOcrClient("http://127.0.0.1:" + server.getAddress().getPort(), "/ocr", false);

            final OcrDocument result = client.recognize(new DocumentInput("application/pdf", "%PDF".getBytes()));

            assertThat(requestBody.get()).contains("\"fileType\":0").contains("\"visualize\":false");
            assertThat(result.pages()).hasSize(2);
            assertThat(result.pages().getFirst().blocks())
                    .extracting(OcrBlock::text)
                    .containsExactly("F", "03-12345678");
            assertThat(result.pages().getFirst().blocks().get(1).confidence()).isEqualTo(0.96);
            assertThat(result.pages().getFirst().blocks().get(1).boundingBox())
                    .isEqualTo(new BoundingBox(30, 20, 160, 40));
            assertThat(result.pages().get(1).pageNumber()).isEqualTo(2);
            assertThat(result.pages().get(1).blocks().getFirst().boundingBox())
                    .isEqualTo(new BoundingBox(5, 6, 50, 26));
        } finally {
            server.stop(0);
        }
    }
}
