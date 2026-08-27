package com.github.laplusijns.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaddleXOcrClient implements OcrClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final boolean returnPageImages;

    @Autowired
    public PaddleXOcrClient(
            @Value("${card-ocr.paddlex.base-url:http://127.0.0.1:16601}") final String baseUrl,
            @Value("${card-ocr.paddlex.endpoint:/ocr}") final String endpoint,
            @Value("${card-ocr.paddlex.return-page-images:true}") final boolean returnPageImages) {
        this(new ObjectMapper(), baseUrl, endpoint, returnPageImages);
    }

    PaddleXOcrClient(
            final ObjectMapper objectMapper,
            final String baseUrl,
            final String endpoint,
            final boolean returnPageImages) {
        this.objectMapper = objectMapper;
        this.restClient =
                RestClient.builder().baseUrl(stripTrailingSlash(baseUrl)).build();
        this.endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        this.returnPageImages = returnPageImages;
    }

    @Override
    public OcrDocument recognize(final DocumentInput document) {
        final Map<String, Object> request = Map.of(
                "file",
                Base64.getEncoder().encodeToString(document.bytes()),
                "fileType",
                document.paddleXFileType(),
                "useDocOrientationClassify",
                true,
                "useDocUnwarping",
                true,
                "useTextlineOrientation",
                true,
                "textRecScoreThresh",
                0.0,
                "visualize",
                returnPageImages);
        final String responseBody = restClient
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("PaddleX returned an empty response");
        }
        final JsonNode response;
        try {
            response = objectMapper.readTree(responseBody);
        } catch (IOException exception) {
            throw new IllegalStateException("PaddleX returned invalid JSON", exception);
        }
        final int errorCode = response.path("errorCode").asInt(0);
        if (errorCode != 0) {
            throw new IllegalStateException(
                    "PaddleX OCR failed: " + response.path("errorMsg").asText("unknown error"));
        }
        final JsonNode results = response.path("result").path("ocrResults");
        if (!results.isArray()) throw new IllegalStateException("PaddleX response does not contain OCR pages");

        final List<OcrPage> pages = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < results.size(); pageIndex++) {
            pages.add(parsePage(results.get(pageIndex), pageIndex + 1));
        }
        return new OcrDocument(pages);
    }

    private static OcrPage parsePage(final JsonNode pageNode, final int pageNumber) {
        final JsonNode result = pageNode.path("prunedResult");
        final JsonNode texts = field(result, "rec_texts", "recTexts");
        final JsonNode scores = field(result, "rec_scores", "recScores");
        final JsonNode boxes = field(result, "rec_boxes", "recBoxes");
        final JsonNode polygons = field(result, "rec_polys", "recPolys");
        final int count = texts.isArray() ? texts.size() : 0;
        final List<OcrBlock> blocks = new ArrayList<>(count);
        for (int blockIndex = 0; blockIndex < count; blockIndex++) {
            final BoundingBox boundingBox = boundingBox(boxes, polygons, blockIndex);
            final double confidence = scores.isArray() && blockIndex < scores.size()
                    ? scores.get(blockIndex).asDouble(0.0)
                    : 0.0;
            blocks.add(new OcrBlock(
                    pageNumber + "-" + blockIndex,
                    pageNumber,
                    texts.get(blockIndex).asText(""),
                    boundingBox,
                    confidence));
        }

        final byte[] pageImage = decodeInlineImage(pageNode.path("inputImage").asText(""));
        final int[] imageSize = imageSize(pageImage);
        final int width = imageSize[0] > 0
                ? imageSize[0]
                : (int) Math.ceil(blocks.stream()
                        .mapToDouble(block -> block.boundingBox().right())
                        .max()
                        .orElse(0));
        final int height = imageSize[1] > 0
                ? imageSize[1]
                : (int) Math.ceil(blocks.stream()
                        .mapToDouble(block -> block.boundingBox().bottom())
                        .max()
                        .orElse(0));
        return new OcrPage(pageNumber, width, height, blocks, pageImage);
    }

    private static BoundingBox boundingBox(final JsonNode boxes, final JsonNode polygons, final int index) {
        if (boxes.isArray() && index < boxes.size()) {
            final JsonNode box = boxes.get(index);
            if (box.isArray() && box.size() >= 4) {
                return new BoundingBox(
                        box.get(0).asDouble(),
                        box.get(1).asDouble(),
                        box.get(2).asDouble(),
                        box.get(3).asDouble());
            }
        }
        if (polygons.isArray() && index < polygons.size()) {
            final JsonNode polygon = polygons.get(index);
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            if (polygon.isArray()) {
                for (final JsonNode point : polygon) {
                    if (point.isArray() && point.size() >= 2) {
                        left = Math.min(left, point.get(0).asDouble());
                        top = Math.min(top, point.get(1).asDouble());
                        right = Math.max(right, point.get(0).asDouble());
                        bottom = Math.max(bottom, point.get(1).asDouble());
                    }
                }
            }
            if (Double.isFinite(left)) return new BoundingBox(left, top, right, bottom);
        }
        return new BoundingBox(0, 0, 0, 0);
    }

    private static JsonNode field(final JsonNode object, final String snakeCase, final String camelCase) {
        final JsonNode value = object.path(snakeCase);
        return value.isMissingNode() ? object.path(camelCase) : value;
    }

    private static byte[] decodeInlineImage(final String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.startsWith("http://") || encoded.startsWith("https://")) {
            return new byte[0];
        }
        final int comma = encoded.indexOf(',');
        final String base64 = encoded.startsWith("data:") && comma >= 0 ? encoded.substring(comma + 1) : encoded;
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    private static int[] imageSize(final byte[] imageBytes) {
        if (imageBytes.length == 0) return new int[] {0, 0};
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return image == null ? new int[] {0, 0} : new int[] {image.getWidth(), image.getHeight()};
        } catch (IOException exception) {
            return new int[] {0, 0};
        }
    }

    private static String stripTrailingSlash(final String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
