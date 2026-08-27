package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;

class InvoiceConfgTest {

    @Test
	void createsSeparateVisionAndParserModelsAndClients() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "spring.ai.openai.base-url=https://example.test/compatible-mode/v1",
                    "spring.ai.openai.api-key=test-key");
            context.register(InvoiceConfg.class);
            context.refresh();

            final OpenAiChatModel visionModel = context.getBean("visionChatModel", OpenAiChatModel.class);
            final OpenAiChatModel parserModel = context.getBean("parserChatModel", OpenAiChatModel.class);
            assertThat(visionModel).isNotSameAs(parserModel);
            assertThat(visionModel.getOptions().getModel()).isEqualTo("qwen-vl-max");
            assertThat(parserModel.getOptions().getModel()).isEqualTo("qwen3.8-max");
            assertThat(context.getBean("visionChatClient", ChatClient.class))
                    .isNotSameAs(context.getBean("parserChatClient", ChatClient.class));
		}
	}

	@Test
	void resolvesDocumentedEnvironmentVariableNames() {
		final SystemEnvironmentPropertySource environment = new SystemEnvironmentPropertySource(
				"test",
				Map.of(
						"CARD_OCR_AI_VISION_MODEL", "vision-model",
						"CARD_OCR_AI_PARSER_MODEL", "parser-model"));

		assertThat(environment.getProperty("card-ocr.ai.vision.model")).isEqualTo("vision-model");
		assertThat(environment.getProperty("card-ocr.ai.parser.model")).isEqualTo("parser-model");
	}
}
