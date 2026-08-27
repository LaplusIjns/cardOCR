package com.github.laplusijns.invoice;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.StructuredResponse;

@Component
final class OpenAiResponsesClient implements CardRecognitionClient {

	private final OpenAIClient openAiClient;
	private final String model;
	private final ResponseInputImage.Detail imageDetail;
	private final ReasoningEffort reasoningEffort;
	private final Duration pollInterval;
	private final Duration maxWait;

	OpenAiResponsesClient(final OpenAIClient openAiClient,
			@Value("${spring.ai.openai.responses.model:${spring.ai.openai.chat.options.model}}") final String model,
			@Value("${spring.ai.openai.responses.image-detail:high}") final String imageDetail,
			@Value("${spring.ai.openai.responses.reasoning-effort:high}") final String reasoningEffort,
			@Value("${spring.ai.openai.responses.poll-interval:2s}") final Duration pollInterval,
			@Value("${spring.ai.openai.responses.max-wait:8m}") final Duration maxWait) {
		this.openAiClient = openAiClient;
		this.model = requireText(model, "OpenAI Responses model");
		this.imageDetail = ResponseInputImage.Detail.of(requireText(imageDetail, "OpenAI image detail").toLowerCase())
				.validate();
		this.reasoningEffort = ReasoningEffort.of(
				requireText(reasoningEffort, "OpenAI reasoning effort").toLowerCase()).validate();
		this.pollInterval = requirePositive(pollInterval, "OpenAI Responses poll interval");
		this.maxWait = requirePositive(maxWait, "OpenAI Responses maximum wait");
	}

	@Override
	public <T> T recognize(final String instructions, final String prompt, final String mimeType,
			final byte[] imageBytes, final Class<T> responseType) {
		final String imageDataUrl = "data:%s;base64,%s"
				.formatted(mimeType, Base64.getEncoder().encodeToString(imageBytes));
		final EasyInputMessage message = EasyInputMessage.builder()
				.role(EasyInputMessage.Role.USER)
				.contentOfResponseInputMessageContentList(List.of(
						ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()),
						ResponseInputContent.ofInputImage(ResponseInputImage.builder()
								.imageUrl(imageDataUrl)
								.detail(imageDetail)
								.build())))
				.build();
		final var params = ResponseCreateParams.builder()
				.model(model)
				.instructions(instructions)
				.inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(message)))
				.reasoning(Reasoning.builder().effort(reasoningEffort).build())
				.background(true)
				.store(false)
				.text(responseType, JsonSchemaLocalValidation.YES)
				.build();

		final StructuredResponse<T> response = awaitCompletion(openAiClient.responses().create(params), responseType);
		ensureSuccessful(response);
		for (final var outputItem : response.output()) {
			if (!outputItem.isMessage()) {
				continue;
			}
			for (final var content : outputItem.asMessage().content()) {
				if (content.isOutputText()) {
					return content.asOutputText();
				}
			}
		}
		throw new IllegalStateException("OpenAI Responses API returned no structured output (response id: "
				+ response.id() + ")");
	}

	private <T> StructuredResponse<T> awaitCompletion(StructuredResponse<T> response, final Class<T> responseType) {
		final long deadlineNanos = System.nanoTime() + maxWait.toNanos();
		while (isPending(response)) {
			final long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				throw new IllegalStateException("OpenAI Responses API did not complete within " + maxWait
						+ " (response id: " + response.id() + ")");
			}

			final Duration remaining = Duration.ofNanos(remainingNanos);
			sleep(pollInterval.compareTo(remaining) < 0 ? pollInterval : remaining, response.id());
			response = new StructuredResponse<>(responseType, openAiClient.responses().retrieve(response.id()));
		}
		return response;
	}

	private static boolean isPending(final StructuredResponse<?> response) {
		return response.status()
				.map(status -> status.asString().equals("queued") || status.asString().equals("in_progress"))
				.orElse(false);
	}

	private static void ensureSuccessful(final StructuredResponse<?> response) {
		response.error().ifPresent(error -> {
			throw new IllegalStateException("OpenAI Responses API failed: " + error.message()
					+ " (response id: " + response.id() + ")");
		});
		response.status().ifPresent(status -> {
			if (!status.asString().equals("completed")) {
				throw new IllegalStateException("OpenAI Responses API ended with status " + status.asString()
						+ " (response id: " + response.id() + ")");
			}
		});
	}

	private static void sleep(final Duration duration, final String responseId) {
		try {
			Thread.sleep(duration);
		} catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					"Interrupted while waiting for OpenAI response (response id: " + responseId + ")", exception);
		}
	}

	private static String requireText(final String value, final String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required");
		}
		return value.strip();
	}

	private static Duration requirePositive(final Duration value, final String label) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive");
		}
		return value;
	}
}
