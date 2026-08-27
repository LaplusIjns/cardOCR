package com.github.laplusijns.invoice;

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

import jakarta.annotation.PreDestroy;
import jakarta.annotation.security.PermitAll;
import reactor.core.publisher.Flux;

@Endpoint
@PermitAll
public class ProcessService {
	private static final int MAX_BATCH_IMAGES = 20;
	private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
	private static final String RE_RECOGNIZING = "重新辨識中";
	private static final String RE_RECOGNITION_FAILED = "重新辨識失敗";
	private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp",
			"image/gif");
	private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

	private final CardRecognitionPipeline recognitionPipeline;
	private final UserAccountRepository userAccountRepository;
	private final BusinessCardRepository businessCardRepository;
	private final BusinessCardChannels channels;
	private final ImageStorageService imageStorageService;
	private final ImageCache imageCache;
	private final ExecutorService workerExecutor;
	private final Map<Long, String> reRecognitionStatuses = new ConcurrentHashMap<>();

	@Autowired
	public ProcessService(final CardRecognitionPipeline recognitionPipeline,
			final UserAccountRepository userAccountRepository,
			final BusinessCardRepository businessCardRepository, final BusinessCardChannels channels,
			final ImageStorageService imageStorageService, final ImageCache imageCache) {
		this(recognitionPipeline, userAccountRepository, businessCardRepository, channels, imageStorageService,
				imageCache, Executors.newSingleThreadExecutor());
	}

	ProcessService(final CardRecognitionPipeline recognitionPipeline,
			final UserAccountRepository userAccountRepository,
			final BusinessCardRepository businessCardRepository, final BusinessCardChannels channels,
			final ImageStorageService imageStorageService, final ImageCache imageCache,
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
				.map(this::toDto).toList();
	}

	public void process(final String base64Image, final String sessionId) {
		processImages(Collections.singletonList(base64Image), sessionId);
	}

	public int processImages(final List<String> base64Images, final String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("Session id is required");
		}
		if (base64Images == null || base64Images.isEmpty()) {
			throw new IllegalArgumentException("At least one image is required");
		}
		if (base64Images.size() > MAX_BATCH_IMAGES) {
			throw new IllegalArgumentException("A maximum of " + MAX_BATCH_IMAGES + " images can be uploaded at once");
		}

		final UserAccount user = currentUser();
		final List<ImageUpload> images = new ArrayList<>(base64Images.size());
		for (final String base64Image : base64Images) {
			images.add(parseImage(base64Image));
		}
		for (final ImageUpload image : images) {
			enqueue(user, image, sessionId);
		}
		return images.size();
	}

	private void enqueue(final UserAccount user, final ImageUpload image, final String sessionId) {
		final String imageId = UUID.randomUUID().toString();
		final String imagePath;
		try {
			imagePath = imageStorageService.store(user.getId(), imageId, image.mimeType(), image.bytes());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to store image", exception);
		}

		imageCache.put(imageId, image.bytes());
		channels.emit(sessionId, BusinessCardDTO.progress(imageId));
		workerExecutor.submit(() -> recognize(user, imageId, imagePath, image.mimeType(), image.bytes(), sessionId));
	}

	private static ImageUpload parseImage(final String base64Image) {
		if (base64Image == null || base64Image.isBlank()) {
			throw new IllegalArgumentException("Image data is required");
		}
		final String[] parts = base64Image.split(";base64,", 2);
		if (parts.length != 2 || !parts[0].startsWith("data:")) {
			throw new IllegalArgumentException("Invalid image data URL");
		}
		final String mimeType = parts[0].substring("data:".length()).toLowerCase(Locale.ROOT);
		if (!SUPPORTED_IMAGE_TYPES.contains(mimeType)) {
			throw new IllegalArgumentException("Unsupported image type: " + mimeType);
		}
		final int maxBase64Length = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
		if (parts[1].length() > maxBase64Length) {
			throw new IllegalArgumentException("Each image must be 10 MB or smaller");
		}
		final byte[] imageBytes;
		try {
			imageBytes = Base64.getDecoder().decode(parts[1]);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid base64 image data", exception);
		}
		if (imageBytes.length == 0) {
			throw new IllegalArgumentException("Image is empty");
		}
		if (imageBytes.length > MAX_IMAGE_BYTES) {
			throw new IllegalArgumentException("Each image must be 10 MB or smaller");
		}
		return new ImageUpload(mimeType, imageBytes);
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
				log.warn("Unable to delete image {}", card.getImagePath(), exception);
			}
		});
	}

	public BusinessCardDTO updateCard(final Long id, final BusinessCardUpdateRequest request) {
		final UserAccount user = currentUser();
		final BusinessCard card = businessCardRepository.findByIdAndUser_Id(id, user.getId())
				.orElseThrow(() -> new IllegalArgumentException("Business card not found"));
		apply(card, request);
		return toDto(businessCardRepository.save(card));
	}

	public void reRecognize(final Long id, final String sessionId) {
		final UserAccount user = currentUser();
		final BusinessCard card = businessCardRepository.findByIdAndUser_Id(id, user.getId())
				.orElseThrow(() -> new IllegalArgumentException("Business card not found"));
		final byte[] imageBytes;
		try {
			imageBytes = imageStorageService.read(card.getImagePath());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read stored image", exception);
		}
		if (RE_RECOGNIZING.equals(reRecognitionStatuses.put(id, RE_RECOGNIZING))) {
			channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNIZING));
			return;
		}
		channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNIZING));
		try {
			workerExecutor.submit(
					() -> recognizeExisting(user.getId(), id, mimeType(card.getImagePath()), imageBytes, sessionId));
		} catch (RuntimeException exception) {
			reRecognitionStatuses.remove(id, RE_RECOGNIZING);
			throw exception;
		}
	}

	private void recognize(final UserAccount user, final String imageId, final String imagePath, final String mimeType,
			final byte[] imageBytes, final String sessionId) {
		try {
			final BusinessCardRecognition result = recognizeImage(mimeType, imageBytes);
			final BusinessCard card = new BusinessCard(user, imageId, imagePath);
			card.setCompanyName(result.companyName);
			card.setName(result.name);
			card.setJobTitle(result.jobTitle);
			card.setTelephone(result.telephone);
			card.setMobilePhone(result.mobilePhone);
			card.setFax(result.fax);
			card.setEmail(result.email);
			card.setAddress(result.address);
			card.setNotes(formatNotes(result));
			channels.emit(sessionId, BusinessCardDTO.from(businessCardRepository.save(card)));
		} catch (Exception exception) {
			log.error("Business card recognition failed", exception);
			channels.emit(sessionId, BusinessCardDTO.error(imageId,
					exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
		}
	}

	private void recognizeExisting(final Long userId, final Long cardId, final String mimeType, final byte[] imageBytes,
			final String sessionId) {
		try {
			final BusinessCard card = businessCardRepository.findByIdAndUser_Id(cardId, userId)
					.orElseThrow(() -> new IllegalArgumentException("Business card not found"));
			final BusinessCardRecognition result = recognizeImage(mimeType, imageBytes);
			apply(card, result);
			final BusinessCard savedCard = businessCardRepository.save(card);
			reRecognitionStatuses.remove(cardId);
			channels.emit(sessionId, BusinessCardDTO.from(savedCard));
		} catch (Exception exception) {
			log.error("Business card re-recognition failed", exception);
			businessCardRepository.findByIdAndUser_Id(cardId, userId)
					.ifPresentOrElse(card -> {
						reRecognitionStatuses.put(cardId, RE_RECOGNITION_FAILED);
						channels.emit(sessionId, BusinessCardDTO.from(card, RE_RECOGNITION_FAILED));
					}, () -> reRecognitionStatuses.remove(cardId));
		}
	}

	private BusinessCardDTO toDto(final BusinessCard card) {
		final String status = reRecognitionStatuses.get(card.getId());
		return status == null ? BusinessCardDTO.from(card) : BusinessCardDTO.from(card, status);
	}

	BusinessCardRecognition recognizeImage(final String mimeType, final byte[] imageBytes) {
		return recognitionPipeline.recognize(mimeType, imageBytes);
	}

	private static boolean isBlank(final String value) {
		return value == null || value.isBlank();
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
		if (!isBlank(value)) {
			notes.add(label + "：" + value.strip());
		}
	}

	private static String mimeType(final String imagePath) {
		final String lowerPath = imagePath.toLowerCase();
		if (lowerPath.endsWith(".png"))
			return "image/png";
		if (lowerPath.endsWith(".webp"))
			return "image/webp";
		if (lowerPath.endsWith(".gif"))
			return "image/gif";
		return "image/jpeg";
	}

	private UserAccount currentUser() {
		final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| "anonymousUser".equals(authentication.getName())) {
			throw new IllegalStateException("User is not authenticated");
		}
		return userAccountRepository.findByUsernameIgnoreCase(authentication.getName())
				.orElseThrow(() -> new IllegalStateException("User account not found"));
	}

	private record ImageUpload(String mimeType, byte[] bytes) {
	}
}
