package com.github.laplusijns.invoice;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.card.BusinessCard;
import com.github.laplusijns.card.BusinessCardChannels;
import com.github.laplusijns.card.BusinessCardDTO;
import com.github.laplusijns.card.BusinessCardRepository;
import com.github.laplusijns.card.BusinessCardUpdateRequest;
import com.github.laplusijns.image.ImageCache;
import com.github.laplusijns.image.ImageStorageService;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.hilla.Endpoint;
import jakarta.annotation.security.PermitAll;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

@Endpoint
@PermitAll
public class ProcessService {
    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);
    private static final String AI_PROMPT = """
            你是一個專業的名片 OCR 助理。請辨識圖片中的名片資訊，並只輸出 JSON，不要輸出 Markdown 或說明文字。
            JSON 欄位必須完全如下：
            {
              "companyName": "公司名稱",
              "name": "姓名",
              "jobTitle": "職稱",
              "telephone": "電話",
              "mobilePhone": "行動電話",
              "fax": "傳真",
              "email": "EMAIL",
              "address": "地址",
              "notes": "備註"
            }
            看不到或無法確認的欄位請回傳空字串。保留電話分機、國碼及原有標點。
            notes 僅放置名片上不屬於其他欄位的重要資訊；若名片上有統一編號（統編）、公司股票代號、公司網址或部門等資訊，請完整寫入 notes。不可臆測不存在的內容。
            """;

    private final ChatClient chatClient;
    private final UserAccountRepository userAccountRepository;
    private final BusinessCardRepository businessCardRepository;
    private final BusinessCardChannels channels;
    private final ImageStorageService imageStorageService;
    private final ImageCache imageCache;
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

    public ProcessService(
            final ChatClient chatClient,
            final UserAccountRepository userAccountRepository,
            final BusinessCardRepository businessCardRepository,
            final BusinessCardChannels channels,
            final ImageStorageService imageStorageService,
            final ImageCache imageCache) {
        this.chatClient = chatClient;
        this.userAccountRepository = userAccountRepository;
        this.businessCardRepository = businessCardRepository;
        this.channels = channels;
        this.imageStorageService = imageStorageService;
        this.imageCache = imageCache;
    }

    @NonNull
    public String jsessionId() {
        final VaadinServletRequest request = (VaadinServletRequest) VaadinService.getCurrentRequest();
        return request.getHttpServletRequest().getSession().getId();
    }

    @NonNull
    public Flux<@NonNull BusinessCardDTO> cardSubscription(final String sessionId) {
        return channels.subscription(sessionId);
    }

    public List<BusinessCardDTO> data() {
        final UserAccount user = currentUser();
        return businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(user.getId()).stream()
                .map(BusinessCardDTO::from)
                .toList();
    }

    public void process(final String base64Image, final String sessionId) {
        final UserAccount user = currentUser();
        final String imageId = UUID.randomUUID().toString();
        final String[] parts = base64Image.split(";base64,", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid image data URL");
        }
        final String mimeType = parts[0].replace("data:", "");
        final byte[] imageBytes = Base64.getDecoder().decode(parts[1]);
        final String imagePath;
        try {
            imagePath = imageStorageService.store(user.getId(), imageId, mimeType, imageBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to store image", exception);
        }

        imageCache.put(imageId, imageBytes);
        channels.emit(sessionId, BusinessCardDTO.progress(imageId));
        workerExecutor.submit(() -> recognize(user, imageId, imagePath, mimeType, imageBytes, sessionId));
    }

    public void deleteCard(final Long id) {
        final UserAccount user = currentUser();
        businessCardRepository.findByIdAndUser_Id(id, user.getId()).ifPresent(card -> {
            businessCardRepository.delete(card);
            imageCache.delete(card.getImageId());
            try {
                imageStorageService.delete(card.getImagePath());
            } catch (IOException exception) {
                log.warn("Unable to delete image {}", card.getImagePath(), exception);
            }
        });
    }

    public BusinessCardDTO updateCard(final Long id, final BusinessCardUpdateRequest request) {
        final UserAccount user = currentUser();
        final BusinessCard card = businessCardRepository
                .findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
        apply(card, request);
        return BusinessCardDTO.from(businessCardRepository.save(card));
    }

    public void reRecognize(final Long id, final String sessionId) {
        final UserAccount user = currentUser();
        final BusinessCard card = businessCardRepository
                .findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
        final byte[] imageBytes;
        try {
            imageBytes = imageStorageService.read(card.getImagePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read stored image", exception);
        }
        channels.emit(sessionId, BusinessCardDTO.from(card, "重新辨識中"));
        workerExecutor.submit(
                () -> recognizeExisting(user.getId(), id, mimeType(card.getImagePath()), imageBytes, sessionId));
    }

    private void recognize(
            final UserAccount user,
            final String imageId,
            final String imagePath,
            final String mimeType,
            final byte[] imageBytes,
            final String sessionId) {
        try {
            final UserMessage message = UserMessage.builder()
                    .text(AI_PROMPT)
                    .media(new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                    .build();
            BusinessCardRecognition result =
                    chatClient.prompt(new Prompt(message)).call().entity(BusinessCardRecognition.class);
            if (result == null) {
                result = new BusinessCardRecognition();
            }
            final BusinessCard card = new BusinessCard(user, imageId, imagePath);
            card.setCompanyName(result.companyName);
            card.setName(result.name);
            card.setJobTitle(result.jobTitle);
            card.setTelephone(result.telephone);
            card.setMobilePhone(result.mobilePhone);
            card.setFax(result.fax);
            card.setEmail(result.email);
            card.setAddress(result.address);
            card.setNotes(result.notes);
            channels.emit(sessionId, BusinessCardDTO.from(businessCardRepository.save(card)));
        } catch (Exception exception) {
            log.error("Business card recognition failed", exception);
            channels.emit(
                    sessionId,
                    BusinessCardDTO.error(
                            imageId,
                            exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage()));
        }
    }

    private void recognizeExisting(
            final Long userId,
            final Long cardId,
            final String mimeType,
            final byte[] imageBytes,
            final String sessionId) {
        try {
            final BusinessCard card = businessCardRepository
                    .findByIdAndUser_Id(cardId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
            final UserMessage message = UserMessage.builder()
                    .text(AI_PROMPT)
                    .media(new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                    .build();
            BusinessCardRecognition result =
                    chatClient.prompt(new Prompt(message)).call().entity(BusinessCardRecognition.class);
            if (result == null) {
                result = new BusinessCardRecognition();
            }
            apply(card, result);
            channels.emit(sessionId, BusinessCardDTO.from(businessCardRepository.save(card)));
        } catch (Exception exception) {
            log.error("Business card re-recognition failed", exception);
            businessCardRepository
                    .findByIdAndUser_Id(cardId, userId)
                    .ifPresent(card -> channels.emit(sessionId, BusinessCardDTO.from(card, "重新辨識失敗")));
        }
    }

    private static void apply(final BusinessCard card, final BusinessCardUpdateRequest request) {
        card.setCompanyName(request.companyName());
        card.setName(request.name());
        card.setJobTitle(request.jobTitle());
        card.setTelephone(request.telephone());
        card.setMobilePhone(request.mobilePhone());
        card.setFax(request.fax());
        card.setEmail(request.email());
        card.setAddress(request.address());
        card.setNotes(request.notes());
    }

    private static void apply(final BusinessCard card, final BusinessCardRecognition result) {
        card.setCompanyName(result.companyName);
        card.setName(result.name);
        card.setJobTitle(result.jobTitle);
        card.setTelephone(result.telephone);
        card.setMobilePhone(result.mobilePhone);
        card.setFax(result.fax);
        card.setEmail(result.email);
        card.setAddress(result.address);
        card.setNotes(result.notes);
    }

    private static String mimeType(final String imagePath) {
        final String lowerPath = imagePath.toLowerCase();
        if (lowerPath.endsWith(".png")) return "image/png";
        if (lowerPath.endsWith(".webp")) return "image/webp";
        if (lowerPath.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private UserAccount currentUser() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new IllegalStateException("User is not authenticated");
        }
        return userAccountRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User account not found"));
    }

    public static class BusinessCardRecognition {
        public String companyName = "";
        public String name = "";
        public String jobTitle = "";
        public String telephone = "";
        public String mobilePhone = "";
        public String fax = "";
        public String email = "";
        public String address = "";
        public String notes = "";
    }
}
