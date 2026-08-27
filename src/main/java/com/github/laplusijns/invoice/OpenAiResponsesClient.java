package com.github.laplusijns.invoice;

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

	OpenAiResponsesClient(final OpenAIClient openAiClient,
			@Value("${spring.ai.openai.responses.model:${spring.ai.openai.chat.options.model}}") final String model,
			@Value("${spring.ai.openai.responses.image-detail:high}") final String imageDetail,
			@Value("${spring.ai.openai.responses.reasoning-effort:high}") final String reasoningEffort) {
		this.openAiClient = openAiClient;
		this.model = requireText(model, "OpenAI Responses model");
		this.imageDetail = ResponseInputImage.Detail.of(requireText(imageDetail, "OpenAI image detail").toLowerCase())
				.validate();
		this.reasoningEffort = ReasoningEffort.of(
				requireText(reasoningEffort, "OpenAI reasoning effort").toLowerCase()).validate();
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
				.store(false)
				.text(responseType, JsonSchemaLocalValidation.YES)
				.build();

		final StructuredResponse<T> response = openAiClient.responses().create(params);
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

	private static String requireText(final String value, final String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required");
		}
		return value.strip();
	}
}
