package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

class CardAiServicesTest {

	private final ChatClient chatClient = mock(ChatClient.class);
	private final ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
	private final ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

	@Test
	void visionStageSendsTheImageAndReturnsTrimmedEvidence() throws IOException {
		stubRequest();
		when(response.content()).thenReturn("  左上：範例股份有限公司  ");
		final CardVisionService service = new CardVisionService(chatClient);
		final byte[] image = {1, 2, 3};

		final VisionEvidence evidence = service.understand("image/png", image);

		assertThat(evidence.content()).isEqualTo("左上：範例股份有限公司");
		final ChatClient.PromptUserSpec user = applyUserPrompt();
		final ArgumentCaptor<Resource> imageResource = ArgumentCaptor.forClass(Resource.class);
		verify(user).media(eq(MimeTypeUtils.IMAGE_PNG), imageResource.capture());
		assertThat(imageResource.getValue().getContentAsByteArray()).containsExactly(image);
	}

	@Test
	void visionStageRejectsAnEmptyImageBeforeCallingTheModel() {
		final CardVisionService service = new CardVisionService(chatClient);

		assertThatThrownBy(() -> service.understand("image/png", new byte[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must not be empty");
		verifyNoInteractions(chatClient);
	}

	@Test
	void parserStageReceivesEvidenceAsTextWithoutImageMedia() {
		stubRequest();
		final BusinessCardRecognition expected = new BusinessCardRecognition();
		expected.companyName = "範例股份有限公司";
		final ArgumentCaptor<Consumer<ChatClient.EntityParamSpec>> entityOptions = ArgumentCaptor.captor();
		when(response.entity(eq(BusinessCardRecognition.class), entityOptions.capture()))
				.thenReturn(expected);
		final CardParsingService service = new CardParsingService(chatClient);

		final BusinessCardRecognition result = service.parse(new VisionEvidence("左上：範例股份有限公司"));

		assertThat(result).isSameAs(expected);
		final ChatClient.PromptUserSpec user = applyUserPrompt();
		final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(user).text(text.capture());
		assertThat(text.getValue()).contains("<vision-evidence>", "左上：範例股份有限公司");
		verify(user, never()).media(any(MimeType.class), any(Resource.class));
		final ChatClient.EntityParamSpec structuredOutput = mock(ChatClient.EntityParamSpec.class);
		when(structuredOutput.useProviderStructuredOutput()).thenReturn(structuredOutput);
		when(structuredOutput.validateSchema()).thenReturn(structuredOutput);
		entityOptions.getValue().accept(structuredOutput);
		verify(structuredOutput).useProviderStructuredOutput();
		verify(structuredOutput).validateSchema();
	}

	private void stubRequest() {
		when(chatClient.prompt()).thenReturn(request);
		when(request.system(anyString())).thenReturn(request);
		when(request.user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any()))
				.thenReturn(request);
		when(request.call()).thenReturn(response);
	}

	private ChatClient.PromptUserSpec applyUserPrompt() {
		final ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> userPrompt = ArgumentCaptor.captor();
		verify(request).user(userPrompt.capture());
		final ChatClient.PromptUserSpec user = mock(ChatClient.PromptUserSpec.class);
		when(user.text(anyString())).thenReturn(user);
		when(user.media(any(MimeType.class), any(Resource.class))).thenReturn(user);
		userPrompt.getValue().accept(user);
		return user;
	}
}
