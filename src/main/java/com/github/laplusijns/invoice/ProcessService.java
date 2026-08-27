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
import com.github.laplusijns.ocr.DocumentInput;
import com.github.laplusijns.recognition.BusinessCardRecognition;
import com.github.laplusijns.recognition.BusinessCardRecognitionPipeline;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.hilla.Endpoint;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.security.PermitAll;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

@Endpoint
@PermitAll
public class ProcessService {
    private static final int MAX_BATCH_DOCUMENTS = 20;
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final String RE_RECOGNIZING = "重新辨識中";
    private static final String RE_RECOGNITION_FAILED = "重新辨識失敗";
    private static final Set<String> SUPPORTED_DOCUMENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf");
    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    private final BusinessCardRecognitionPipeline recognitionPipeline;
    private final UserAccountRepository userAccountRepository;
    private final BusinessCardRepository businessCardRepository;
    private final BusinessCardChannels channels;
    private final ImageStorageService imageStorageService;
    private final ImageCache imageCache;
    private final ExecutorService workerExecutor;
    private final Map<Long, String> reRecognitionStatuses = new ConcurrentHashMap<>();

    @Autowired
    public ProcessService(
            final BusinessCardRecognitionPipeline recognitionPipeline,
            final UserAccountRepository userAccountRepository,
            final BusinessCardRepository businessCardRepository,
            final BusinessCardChannels channels,
            final ImageStorageService imageStorageService,
            final ImageCache imageCache) {
        this(
                recognitionPipeline,
                userAccountRepository,
                businessCardRepository,
                channels,
                imageStorageService,
                imageCache,
                Executors.newSingleThreadExecutor());
    }

    ProcessService(
            final BusinessCardRecognitionPipeline recognitionPipeline,
            final UserAccountRepository userAccountRepository,
            final BusinessCardRepository businessCardRepository,
            final BusinessCardChannels channels,
            final ImageStorageService imageStorageService,
            final ImageCache imageCache,
            final ExecutorService workerExecutor) {
        this.recognitionPipeline = recognitionPipeline;
        this.userAccountRepository = userAccountRepository;
        this.businessCardRepository = businessCardRepository;
        this.channels = channels;
        this.imageStorageService = imageStorageService;
        this.imageCache = imageCache;
        this.workerExecutor = workerExecutor;
    }

    @PreDestroy
    void shutdownExecutors() {
        workerExecutor.shutdown();
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
                .map(this::toDto)
                .toList();
    }

    public void process(final String base64Document, final String sessionId) {
        processImages(Collections.singletonList(base64Document), sessionId);
    }

    // The method name is retained for Hilla client compatibility; each item may now also be a PDF.
    public int processImages(final List<String> base64Documents, final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session id is required");
        }
        if (base64Documents == null || base64Documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }
        if (base64Documents.size() > MAX_BATCH_DOCUMENTS) {
            throw new IllegalArgumentException(
                    "A maximum of " + MAX_BATCH_DOCUMENTS + " documents can be uploaded at once");
        }

        final UserAccount user = currentUser();
        final List<DocumentUpload> documents = new ArrayList<>(base64Documents.size());
        for (final String base64Document : base64Documents) documents.add(parseDocument(base64Document));
        for (final DocumentUpload document : documents) enqueue(user, document, sessionId);
        return documents.size();
    }

    private void enqueue(final UserAccount user, final DocumentUpload document, final String sessionId) {
        final String imageId = UUID.randomUUID().toString();
        final String imagePath;
        try {
            imagePath = imageStorageService.store(user.getId(), imageId, document.mimeType(), document.bytes());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to store document", exception);
        }

        imageCache.put(imageId, document.bytes());
        channels.emit(sessionId, BusinessCardDTO.progress(imageId));
        workerExecutor.submit(
                () -> recognize(user, imageId, imagePath, document.mimeType(), document.bytes(), sessionId));
    }

    private static DocumentUpload parseDocument(final String base64Document) {
        if (base64Document == null || base64Document.isBlank()) {
            throw new IllegalArgumentException("Document data is required");
        }
        final String[] parts = base64Document.split(";base64,", 2);
        if (parts.length != 2 || !parts[0].startsWith("data:")) {
            throw new IllegalArgumentException("Invalid document data URL");
        }
        final String mimeType = parts[0].substring("data:".length()).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_DOCUMENT_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported document type: " + mimeType);
        }
        final int maxBase64Length = ((MAX_DOCUMENT_BYTES + 2) / 3) * 4;
        if (parts[1].length() > maxBase64Length) {
            throw new IllegalArgumentException("Each document must be 10 MB or smaller");
        }
        final byte[] documentBytes;
        try {
            documentBytes = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 document data", exception);
        }
        if (documentBytes.length == 0) throw new IllegalArgumentException("Document is empty");
        if (documentBytes.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Each document must be 10 MB or smaller");
        }
        return new DocumentUpload(mimeType, documentBytes);
    }

    public void deleteCard(final Long id) {
        final UserAccount user = currentUser();
        businessCardRepository.findByIdAndUser_Id(id, user.getId()).ifPresent(card -> {
            reRecognitionStatuses.remove(id);
            businessCardRepository.delete(card);
            imageCache.delete(card.getImageId());
            try {
                imageStorageService.delete(card.getImagePath());
            } catch (IOException exception) {
                log.warn("Unable to delete document {}", card.getImagePath(), exception);
            }
        });
    }

    public BusinessCardDTO updateCard(final Long id, final BusinessCardUpdateRequest request) {
        final UserAccount user = currentUser();
        final BusinessCard card = businessCardRepository
                .findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
        apply(card, request);
        return toDto(businessCardRepository.save(card));
    }

    public void reRecognize(final Long id, final String sessionId) {
        final UserAccount user = currentUser();
        final BusinessCard card = businessCardRepository
                .findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
        final byte[] documentBytes;
        try {
            documentBytes = imageStorageService.read(card.getImagePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read stored document", exception);
        }
        if (RE_RECOGNIZING.equals(reRecognitionStatuses.put(id, RE_RECOGNIZING))) {
            channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNIZING));
            return;
        }
        channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNIZING));
        try {
            workerExecutor.submit(
                    () -> recognizeExisting(user.getId(), id, mimeType(card.getImagePath()), documentBytes, sessionId));
        } catch (RuntimeException exception) {
            reRecognitionStatuses.remove(id, RE_RECOGNIZING);
            throw exception;
        }
    }

    private void recognize(
            final UserAccount user,
            final String imageId,
            final String imagePath,
            final String mimeType,
            final byte[] documentBytes,
            final String sessionId) {
        try {
            final BusinessCardRecognition result = recognizeDocument(mimeType, documentBytes);
            final BusinessCard card = new BusinessCard(user, imageId, imagePath);
            apply(card, result);
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
            final byte[] documentBytes,
            final String sessionId) {
        try {
            final BusinessCard card = businessCardRepository
                    .findByIdAndUser_Id(cardId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Business card not found"));
            apply(card, recognizeDocument(mimeType, documentBytes));
            final BusinessCard savedCard = businessCardRepository.save(card);
            reRecognitionStatuses.remove(cardId);
            channels.emit(sessionId, BusinessCardDTO.from(savedCard));
        } catch (Exception exception) {
            log.error("Business card re-recognition failed", exception);
            businessCardRepository
                    .findByIdAndUser_Id(cardId, userId)
                    .ifPresentOrElse(
                            card -> {
                                reRecognitionStatuses.put(cardId, RE_RECOGNITION_FAILED);
                                channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNITION_FAILED));
                            },
                            () -> reRecognitionStatuses.remove(cardId));
        }
    }

    BusinessCardRecognition recognizeDocument(final String mimeType, final byte[] documentBytes) {
        return recognitionPipeline
                .recognize(new DocumentInput(mimeType, documentBytes))
                .businessCard();
    }

    private BusinessCardDTO toDto(final BusinessCard card) {
        final String status = reRecognitionStatuses.get(card.getId());
        return status == null ? BusinessCardDTO.from(card) : BusinessCardDTO.from(card, status);
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
        card.setNotes(formatNotes(result));
    }

    static String formatNotes(final BusinessCardRecognition result) {
        final List<String> notes = new ArrayList<>(3);
        addNote(notes, "統編", result.businessNumber);
        addNote(notes, "股票代號", result.stockCode);
        addNote(notes, "公司網址", result.companyWebsite);
        return String.join("、", notes);
    }

    private static void addNote(final List<String> notes, final String label, final String value) {
        if (value != null && !value.isBlank()) notes.add(label + "：" + value.strip());
    }

    private static String mimeType(final String documentPath) {
        final String lowerPath = documentPath.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".pdf")) return "application/pdf";
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

    private record DocumentUpload(String mimeType, byte[] bytes) {
        private DocumentUpload {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
