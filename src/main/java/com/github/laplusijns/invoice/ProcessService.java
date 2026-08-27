package com.github.laplusijns.invoice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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
import reactor.core.publisher.Flux;

@Endpoint
@PermitAll
public class ProcessService {
	private static final int MAX_BATCH_IMAGES = 20;
	private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
	private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp",
			"image/gif");
	private static final Logger log = LoggerFactory.getLogger(ProcessService.class);
	private static final String AI_PROMPT = """
			你是專業的繁體中文名片 OCR 辨識系統。

			請仔細辨識圖片中的名片資訊。

			規則：
			1. 圖片可能旋轉、傾斜或方向錯誤，請先判斷正確閱讀方向。
			2. 只能使用圖片中明確可見的資訊，禁止猜測、補字或臆測。
			3. 看不清楚或無法可靠確認的內容，回傳空字串。
			4. 保留繁體中文及原始字元，不要自行修正文字。
			5. Email、網址、電話、統編必須逐字核對。
			6. 特別注意容易混淆的字元：0/O、1/I/l、5/S、8/B、2/Z。
			7. 判斷欄位時應同時參考文字標示及版面位置。
			""";

	private final ChatClient chatClient;
	private final UserAccountRepository userAccountRepository;
	private final BusinessCardRepository businessCardRepository;
	private final BusinessCardChannels channels;
	private final ImageStorageService imageStorageService;
	private final ImageCache imageCache;
	private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

	public ProcessService(final ChatClient chatClient, final UserAccountRepository userAccountRepository,
			final BusinessCardRepository businessCardRepository, final BusinessCardChannels channels,
			final ImageStorageService imageStorageService, final ImageCache imageCache) {
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
				.map(BusinessCardDTO::from).toList();
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
		return BusinessCardDTO.from(businessCardRepository.save(card));
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
		channels.emit(sessionId, BusinessCardDTO.from(card, "重新辨識中"));
		workerExecutor.submit(
				() -> recognizeExisting(user.getId(), id, mimeType(card.getImagePath()), imageBytes, sessionId));
	}

	private void recognize(final UserAccount user, final String imageId, final String imagePath, final String mimeType,
			final byte[] imageBytes, final String sessionId) {
		try {
			BusinessCardRecognition result = chatClient.prompt().system(AI_PROMPT)
					.user(u -> u.text("請辨識這張名片。").media(MimeTypeUtils.parseMimeType(mimeType),
							new ByteArrayResource(imageBytes)))
					.call()
					.entity(BusinessCardRecognition.class, spec -> spec.useProviderStructuredOutput().validateSchema());
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
			channels.emit(sessionId, BusinessCardDTO.error(imageId,
					exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
		}
	}

	private void recognizeExisting(final Long userId, final Long cardId, final String mimeType, final byte[] imageBytes,
			final String sessionId) {
		try {
			final BusinessCard card = businessCardRepository.findByIdAndUser_Id(cardId, userId)
					.orElseThrow(() -> new IllegalArgumentException("Business card not found"));
			BusinessCardRecognition result = chatClient.prompt().system(AI_PROMPT)
					.user(u -> u.text("請辨識這張名片。").media(MimeTypeUtils.parseMimeType(mimeType),
							new ByteArrayResource(imageBytes)))
					.call()
					.entity(BusinessCardRecognition.class, spec -> spec.useProviderStructuredOutput().validateSchema());
			if (result == null) {
				result = new BusinessCardRecognition();
			}
			apply(card, result);
			channels.emit(sessionId, BusinessCardDTO.from(businessCardRepository.save(card)));
		} catch (Exception exception) {
			log.error("Business card re-recognition failed", exception);
			businessCardRepository.findByIdAndUser_Id(cardId, userId)
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

	@JsonClassDescription("名片 OCR 辨識結果。所有內容只能來自圖片中明確可見的資訊，無法確認時回傳空字串。")
	public static class BusinessCardRecognition {

		@JsonPropertyDescription("公司完整名稱。保留名片上的原始文字；無法確認時回傳空字串。")
		public String companyName = "";

		@JsonPropertyDescription("""
				名片持有人的姓名。
				中文姓名優先。
				若同時有中文名及英文名，格式為「中文姓名（英文姓名）」。
				無法確認時回傳空字串。
				""")
		public String name = "";

		@JsonPropertyDescription("""
				名片持有人的完整職稱。
				若名片上有明確屬於持有人的部門、處、組、單位等資訊，也一併包含。
				不可將公司名稱、產品名稱或公司介紹誤認為職稱。
				無法確認時回傳空字串。
				""")
		public String jobTitle = "";

		@JsonPropertyDescription("""
				一般電話或公司電話。
				只有明確標示為 T、Tel、TEL、Telephone、電話等才填入。
				保留國碼、括號、空格、連字號及分機等原始格式。
				多個值以「、」連接。
				無法確認時回傳空字串。
				""")
		public String telephone = "";

		@JsonPropertyDescription("""
				行動電話或手機號碼。
				只有明確標示為 M、Mobile、Cell、手機、行動電話等才填入。
				保留國碼、括號、空格及連字號等原始格式。
				多個值以「、」連接。
				無法確認時回傳空字串。
				""")
		public String mobilePhone = "";

		@JsonPropertyDescription("""
				傳真號碼。
				只有名片上明確標示 F、Fax、FAX、傳真時才可填入。
				不可因為某個電話號碼未分類就推測為傳真。
				保留國碼、括號、空格、連字號及分機等原始格式。
				多個值以「、」連接。
				無法確認時回傳空字串。
				""")
		public String fax = "";

		@JsonPropertyDescription("""
				電子郵件地址。
				通常標示為 E、Email、E-mail 等。
				必須逐字辨識，不可自行修正常見拼法。
				多個值以「、」連接。
				無法確認時回傳空字串。
				""")
		public String email = "";

		@JsonPropertyDescription("""
				名片上的公司、辦公室或聯絡地址。
				保留原始地址文字與郵遞區號。
				多個地址以「、」連接。
				無法確認時回傳空字串。
				""")
		public String address = "";

		@JsonPropertyDescription("""
				沒有專屬欄位但值得保留的名片資訊。
				包含公司網址、統一編號、統編、公司股票代號等。
				只能填入圖片中明確可見的資訊，不可自行推測。
				多個資訊以「、」連接。
				沒有其他資訊時回傳空字串。
				""")
		public String notes = "";
	}

	private record ImageUpload(String mimeType, byte[] bytes) {
	}
}
