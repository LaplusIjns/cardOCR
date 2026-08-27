package com.github.laplusijns.invoice;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

@Configuration
@EnableResilientMethods
public class InvoiceConfg {

	@Bean(destroyMethod = "close")
	OpenAIClient openAiClient(
			@Value("${spring.ai.openai.base-url:${OPENAI_BASE_URL:https://api.openai.com/v1}}") final String baseUrl,
			@Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") final String apiKey,
			@Value("${spring.ai.openai.timeout:60s}") final Duration timeout,
			@Value("${spring.ai.openai.max-retries:3}") final int maxRetries) {
		final OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
			.fromEnv()
			.baseUrl(normalizeBaseUrl(baseUrl))
			.timeout(timeout)
			.maxRetries(maxRetries);
		if (apiKey != null && !apiKey.isBlank()) {
			builder.apiKey(apiKey);
		}
		return builder.build();
	}

	static String normalizeBaseUrl(final String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return "https://api.openai.com/v1";
		}

		String normalized = baseUrl.strip();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (normalized.endsWith("/responses")) {
			normalized = normalized.substring(0, normalized.length() - "/responses".length());
		}
		if (normalized.equals("https://api.openai.com")) {
			return normalized + "/v1";
		}
		return normalized;
	}
}
