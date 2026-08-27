package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class OpenAiResponsesClientTest {

	@Test
	void postsMultimodalStructuredRequestToResponsesEndpoint() throws Exception {
		final AtomicReference<String> requestMethod = new AtomicReference<>();
		final AtomicReference<String> requestPath = new AtomicReference<>();
		final AtomicReference<String> authorization = new AtomicReference<>();
		final AtomicReference<String> requestBody = new AtomicReference<>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/responses", exchange -> respond(exchange, requestMethod, requestPath,
				authorization, requestBody));
		server.start();

		final OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
			.apiKey("test-key")
			.baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
			.maxRetries(0)
			.build();
		try {
			final OpenAiResponsesClient client = new OpenAiResponsesClient(openAiClient, "gpt-test", "auto", "high");

			final ProcessService.FieldRecognition result = client.recognize(
					"system instructions", "read the email", "image/png", new byte[] {1, 2, 3},
					ProcessService.FieldRecognition.class);

			assertThat(result.value).isEqualTo("alice@example.com");
			assertThat(requestMethod).hasValue("POST");
			assertThat(requestPath).hasValue("/v1/responses");
			assertThat(authorization).hasValue("Bearer test-key");
			assertThat(requestBody.get())
				.contains("\"model\":\"gpt-test\"")
				.contains("\"type\":\"input_image\"")
				.contains("\"image_url\":\"data:image/png;base64,AQID\"")
				.contains("\"detail\":\"auto\"")
				.contains("\"effort\":\"high\"")
				.contains("\"store\":false")
				.contains("\"type\":\"json_schema\"");
		} finally {
			openAiClient.close();
			server.stop(0);
		}
	}

	@Test
	void acceptsEitherApiRootOrFullResponsesEndpointAsConfiguredBaseUrl() {
		assertThat(InvoiceConfg.normalizeBaseUrl("https://api.openai.com"))
				.isEqualTo("https://api.openai.com/v1");
		assertThat(InvoiceConfg.normalizeBaseUrl("https://api.openai.com/v1/"))
				.isEqualTo("https://api.openai.com/v1");
		assertThat(InvoiceConfg.normalizeBaseUrl("https://api.openai.com/v1/responses"))
			.isEqualTo("https://api.openai.com/v1");
	}

	private static void respond(final HttpExchange exchange, final AtomicReference<String> requestMethod,
			final AtomicReference<String> requestPath, final AtomicReference<String> authorization,
			final AtomicReference<String> requestBody) throws IOException {
		requestMethod.set(exchange.getRequestMethod());
		requestPath.set(exchange.getRequestURI().getPath());
		authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
		requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

		final byte[] responseBody = """
				{
				  "id": "resp_test",
				  "object": "response",
				  "created_at": 0,
				  "model": "gpt-test",
				  "output": [{
				    "id": "msg_test",
				    "type": "message",
				    "status": "completed",
				    "role": "assistant",
				    "content": [{
				      "type": "output_text",
				      "text": "{\\\"value\\\":\\\"alice@example.com\\\"}",
				      "annotations": [],
				      "logprobs": []
				    }]
				  }],
				  "parallel_tool_calls": true,
				  "tool_choice": "auto",
				  "tools": []
				}
				""".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, responseBody.length);
		exchange.getResponseBody().write(responseBody);
		exchange.close();
	}
}
