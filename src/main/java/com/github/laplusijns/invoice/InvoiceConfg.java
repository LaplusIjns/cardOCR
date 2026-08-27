package com.github.laplusijns.invoice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

import com.github.laplusijns.invoice.ProcessService.BusinessCardRecognition;

@Configuration
@EnableResilientMethods
public class InvoiceConfg {

	private final String baseUrl;
	private final String apiKey;
	private final String optionsModel;

	public InvoiceConfg(@Value("${spring.ai.openai.base-url}") final String baseUrl,
			@Value("${spring.ai.openai.api-key:}") final String apiKey,
			@Value("${spring.ai.openai.chat.options.model}") final String optionsModel) {
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.optionsModel = optionsModel;
	}

	@Bean
	ChatModel chatModel() {
		final var openAiChatOptions = OpenAiChatOptions.builder().model(optionsModel)
				.temperature(1.0).apiKey(apiKey).baseUrl(baseUrl).reasoningEffort("high").build();

		return OpenAiChatModel.builder().options(openAiChatOptions).build();
	}

	@Bean
	ChatClient chatClient(final ChatModel chatModel) {
		return ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor()).build();
	}
}
