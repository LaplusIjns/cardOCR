package com.github.laplusijns.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.card.BusinessCard;
import com.github.laplusijns.card.BusinessCardChannels;
import com.github.laplusijns.card.BusinessCardDTO;
import com.github.laplusijns.card.BusinessCardRepository;
import com.github.laplusijns.image.ImageCache;
import com.github.laplusijns.image.ImageStorageService;
import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.recognition.BusinessCardRecognition;
import com.github.laplusijns.recognition.BusinessCardRecognitionPipeline;
import com.github.laplusijns.recognition.RecognitionResult;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ProcessServiceTest {
    private final BusinessCardRecognitionPipeline recognitionPipeline = mock(BusinessCardRecognitionPipeline.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BusinessCardRepository businessCardRepository = mock(BusinessCardRepository.class);
    private final BusinessCardChannels channels = mock(BusinessCardChannels.class);
    private final ImageStorageService imageStorageService = mock(ImageStorageService.class);
    private final ImageCache imageCache = mock(ImageCache.class);
    private final UserAccount user = mock(UserAccount.class);
    private final ExecutorService workerExecutor = mock(ExecutorService.class);
    private final ProcessService service = new ProcessService(
            recognitionPipeline,
            userAccountRepository,
            businessCardRepository,
            channels,
            imageStorageService,
            imageCache,
            workerExecutor);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "password", List.of(() -> "ROLE_USER")));
        when(user.getId()).thenReturn(7L);
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        service.shutdownExecutors();
    }

    @Test
    void acceptsAndQueuesMultipleImages() throws Exception {
        when(imageStorageService.store(eq(7L), anyString(), eq("image/png"), any(byte[].class)))
                .thenAnswer(invocation -> "7/" + invocation.getArgument(1) + ".png");
        final String image = "data:image/png;base64,AQID";

        final int accepted = service.processImages(List.of(image, image), "session-1");

        assertThat(accepted).isEqualTo(2);
        verify(imageStorageService, times(2)).store(eq(7L), anyString(), eq("image/png"), any(byte[].class));
        verify(imageCache, times(2)).put(anyString(), any(byte[].class));
        verify(channels, times(2)).emit(eq("session-1"), any(BusinessCardDTO.class));
        verify(workerExecutor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void acceptsPdfAndPreservesItsMimeType() throws Exception {
        when(imageStorageService.store(eq(7L), anyString(), eq("application/pdf"), any(byte[].class)))
                .thenReturn("7/document.pdf");

        final int accepted = service.processImages(List.of("data:application/pdf;base64,JVBERg=="), "session-1");

        assertThat(accepted).isEqualTo(1);
        verify(imageStorageService).store(eq(7L), anyString(), eq("application/pdf"), any(byte[].class));
    }

    @Test
    void validatesWholeBatchBeforeStoringAnything() {
        final List<String> documents = List.of("data:image/png;base64,AQID", "data:text/plain;base64,AQID");

        assertThatThrownBy(() -> service.processImages(documents, "session-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document type");

        verifyNoInteractions(imageStorageService, imageCache, channels);
    }

    @Test
    void rejectsMoreThanTwentyDocuments() {
        final List<String> documents = Collections.nCopies(21, "data:image/png;base64,AQID");

        assertThatThrownBy(() -> service.processImages(documents, "session-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum of 20");

        verifyNoInteractions(imageStorageService, imageCache, channels);
    }

    @Test
    void exposesReRecognitionProgressUntilTheWorkerSavesTheResult() throws Exception {
        final BusinessCard card = mock(BusinessCard.class);
        when(card.getId()).thenReturn(42L);
        when(card.getImageId()).thenReturn("card-image");
        when(card.getImagePath()).thenReturn("7/card-image.png");
        when(businessCardRepository.findByIdAndUser_Id(42L, 7L)).thenReturn(Optional.of(card));
        when(businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(7L)).thenReturn(List.of(card));
        when(businessCardRepository.save(card)).thenReturn(card);
        when(imageStorageService.read("7/card-image.png")).thenReturn(new byte[] {1, 2, 3});
        when(recognitionPipeline.recognize(any(DocumentInput.class))).thenReturn(result(completeRecognition()));
        final ArgumentCaptor<Runnable> workerTask = ArgumentCaptor.forClass(Runnable.class);

        service.reRecognize(42L, "session-1");

        verify(workerExecutor).submit(workerTask.capture());
        assertThat(service.data())
                .singleElement()
                .extracting(BusinessCardDTO::status)
                .isEqualTo("重新辨識中");

        workerTask.getValue().run();

        assertThat(service.data())
                .singleElement()
                .extracting(BusinessCardDTO::status)
                .isEqualTo("辨識完成");
        final ArgumentCaptor<BusinessCardDTO> updates = ArgumentCaptor.forClass(BusinessCardDTO.class);
        verify(channels, times(2)).emit(eq("session-1"), updates.capture());
        assertThat(updates.getAllValues()).extracting(BusinessCardDTO::status).containsExactly("重新辨識中", "辨識完成");
    }

    @Test
    void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("recognitionPipeline", recognitionPipeline);
            context.getBeanFactory().registerSingleton("userAccountRepository", userAccountRepository);
            context.getBeanFactory().registerSingleton("businessCardRepository", businessCardRepository);
            context.getBeanFactory().registerSingleton("channels", channels);
            context.getBeanFactory().registerSingleton("imageStorageService", imageStorageService);
            context.getBeanFactory().registerSingleton("imageCache", imageCache);
            context.register(ProcessService.class);
            context.refresh();

            assertThat(context.getBean(ProcessService.class)).isNotNull();
        }
    }

    @Test
    void delegatesRecognitionToLayeredPipeline() {
        when(recognitionPipeline.recognize(any(DocumentInput.class))).thenReturn(result(completeRecognition()));

        final BusinessCardRecognition recognized = service.recognizeDocument("image/png", new byte[] {1, 2, 3});

        assertThat(recognized.name).isEqualTo("王小明");
        final ArgumentCaptor<DocumentInput> input = ArgumentCaptor.forClass(DocumentInput.class);
        verify(recognitionPipeline).recognize(input.capture());
        assertThat(input.getValue().mimeType()).isEqualTo("image/png");
    }

    @Test
    void formatsNotesWithOnlyBusinessNumberStockCodeAndCompanyWebsite() {
        final BusinessCardRecognition recognition = new BusinessCardRecognition();
        recognition.businessNumber = " 12345678 ";
        recognition.stockCode = "2330";
        recognition.companyWebsite = "https://example.com";

        assertThat(ProcessService.formatNotes(recognition)).isEqualTo("統編：12345678、股票代號：2330、公司網址：https://example.com");
    }

    @Test
    void omitsMissingAllowedValuesFromNotes() {
        final BusinessCardRecognition recognition = new BusinessCardRecognition();
        recognition.companyWebsite = "https://example.com";

        assertThat(ProcessService.formatNotes(recognition)).isEqualTo("公司網址：https://example.com");
    }

    private static RecognitionResult result(final BusinessCardRecognition recognition) {
        return new RecognitionResult(recognition, null, null, null, false, List.of());
    }

    private static BusinessCardRecognition completeRecognition() {
        final BusinessCardRecognition result = new BusinessCardRecognition();
        result.companyName = "範例股份有限公司";
        result.name = "王小明";
        result.jobTitle = "業務部經理";
        result.telephone = "02-1234-5678";
        result.mobilePhone = "0912-345-678";
        result.fax = "02-8765-4321";
        result.email = "alice@example.com";
        result.address = "台北市中正區範例路 1 號";
        result.businessNumber = "12345678";
        result.stockCode = "2330";
        result.companyWebsite = "https://example.com";
        return result;
    }
}
