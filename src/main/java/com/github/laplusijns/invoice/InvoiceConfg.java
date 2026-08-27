package com.github.laplusijns.invoice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.resilience.annotation.EnableResilientMethods;

@Configuration
@EnableResilientMethods
public class InvoiceConfg {

	private final String baseUrl;
	private final String apiKey;
	private final String visionModel;
	private final String parserModel;

	public InvoiceConfg(@Value("${card-ocr.ai.base-url:${spring.ai.openai.base-url}}") final String baseUrl,
			@Value("${card-ocr.ai.api-key:${spring.ai.openai.api-key:}}") final String apiKey,
			@Value("${card-ocr.ai.vision.model:qwen-vl-max}") final String visionModel,
			@Value("${card-ocr.ai.parser.model:qwen3.8-max}") final String parserModel) {
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.visionModel = visionModel;
		this.parserModel = parserModel;
	}

	@Bean("visionChatModel")
	ChatModel visionChatModel() {
		return chatModel(visionModel);
	}

	@Bean("parserChatModel")
	@Primary
	ChatModel parserChatModel() {
		return chatModel(parserModel);
	}

	@Bean("visionChatClient")
	ChatClient visionChatClient(@Qualifier("visionChatModel") final ChatModel visionChatModel) {
		return ChatClient.builder(visionChatModel).build();
	}

	@Bean("parserChatClient")
	ChatClient parserChatClient(@Qualifier("parserChatModel") final ChatModel parserChatModel) {
		return ChatClient.builder(parserChatModel).build();
	}

	private ChatModel chatModel(final String model) {
		final OpenAiChatOptions options = OpenAiChatOptions.builder()
				.model(model)
				.apiKey(apiKey)
				.baseUrl(baseUrl)
				.build();
		return OpenAiChatModel.builder().options(options).build();
	}
}
