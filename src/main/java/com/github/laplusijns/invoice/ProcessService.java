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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
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
	private static final String AI_PROMPT = """
			你是專業的繁體中文名片 OCR 辨識系統。

			你的任務不是只辨識主要聯絡資訊，而是完整掃描整張名片所有可見文字，
			再將資訊分類到對應欄位。

			辨識流程：
			1. 先判斷圖片正確閱讀方向。
			2. 從左到右、從上到下完整掃描整張名片。
			3. 不可只閱讀姓名、公司、電話等大型文字。
			4. 必須特別檢查姓名附近的小字、職稱、部門、單位。
			5. 必須特別檢查名片四周、底部及小字區域是否包含：
			   統一編號、統編、Tax ID、公司網址、股票代號等資訊。
			6. 完成欄位分類後，在輸出前再次檢查：
			   - 是否漏掉姓名旁的職稱或部門
			   - 是否出現「統一編號」「統編」「Tax ID」等標示
			   - 是否出現公司網址或股票代號

			辨識規則：
			1. 圖片可能旋轉、傾斜或方向錯誤，請先判斷正確閱讀方向。
			2. 只能使用圖片中明確可見的資訊，禁止猜測、補字或臆測。
			3. 看不清楚或無法可靠確認的內容才回傳空字串。
			4. 不要因為文字較小就省略，只要能可靠辨識就必須回傳。
			5. 保留繁體中文及原始字元，不要自行修正文字。
			6. Email、網址、電話、統編必須逐字核對。
			7. 特別注意容易混淆的字元：0/O、1/I/l、5/S、8/B、2/Z。
			8. 判斷欄位時同時參考文字內容、標示及版面位置。
			9. 備註只允許統編、股票代號、公司網址三類資訊；其他未分類文字一律不要放入備註。
			""";
	private static final String FIELD_VERIFICATION_PROMPT = """
			你是專業的繁體中文名片 OCR 複核系統。
			你的任務是重新檢查圖片中的單一指定欄位，確認初次辨識的空白是否正確。

			複核規則：
			1. 只尋找使用者指定的欄位，不要回傳其他欄位的內容。
			2. 仔細檢查整張圖片、姓名附近、小字、邊緣與不同閱讀方向。
			3. 只能使用圖片中明確可見的資訊，禁止猜測、補字或臆測。
			4. 保留繁體中文及原始字元；Email、網址與號碼必須逐字核對。
			5. 確認圖片中沒有該欄位，或無法可靠辨識時，回傳空字串。
			""";
	private static final List<RecognitionField> RECOGNITION_FIELDS = List.of(
			new RecognitionField(
					"companyName",
					"公司名稱",
					"公司完整名稱；保留名片上的原始文字。",
					result -> result.companyName,
					(result, value) -> result.companyName = value),
			new RecognitionField(
					"name",
					"姓名",
					"持卡人的姓名；中文姓名優先，同時有中英文姓名時使用「中文姓名（英文姓名）」格式。",
					result -> result.name,
					(result, value) -> result.name = value),
			new RecognitionField(
					"jobTitle",
					"職稱與部門",
					"持卡人的完整職稱及所屬部門或單位；特別檢查姓名附近的小字。",
					result -> result.jobTitle,
					(result, value) -> result.jobTitle = value),
			new RecognitionField(
					"telephone",
					"一般電話或公司電話",
					"只接受明確標示為 T、Tel、Telephone 或電話的號碼，保留國碼、分機及原始格式。",
					result -> result.telephone,
					(result, value) -> result.telephone = value),
			new RecognitionField(
					"mobilePhone",
					"行動電話或手機",
					"只接受明確標示為 M、Mobile、Cell、手機或行動電話的號碼，保留原始格式。",
					result -> result.mobilePhone,
					(result, value) -> result.mobilePhone = value),
			new RecognitionField(
					"fax",
					"傳真",
					"只接受明確標示為 F、Fax、FAX 或傳真的號碼，不可用未分類電話猜測。",
					result -> result.fax,
					(result, value) -> result.fax = value),
			new RecognitionField(
					"email",
					"Email",
					"電子郵件地址；必須逐字辨識，不可自行修正常見拼法。",
					result -> result.email,
					(result, value) -> result.email = value),
			new RecognitionField(
					"address",
					"地址",
					"公司、辦公室或聯絡地址；保留郵遞區號與原始地址文字。",
					result -> result.address,
					(result, value) -> result.address = value),
			new RecognitionField(
					"businessNumber",
					"統編",
					"只尋找統一編號、統編、Unified Business Number 或 Tax ID；只回傳號碼，不含標籤。必須有文字標示或明確語意依據，不可把其他 8 位數字當成統編。",
					result -> result.businessNumber,
					(result, value) -> result.businessNumber = value),
			new RecognitionField(
					"stockCode",
					"股票代號",
					"只尋找名片上明確標示的股票代號；只回傳代號，不含標籤，不可根據公司名稱猜測。",
					result -> result.stockCode,
					(result, value) -> result.stockCode = value),
			new RecognitionField(
					"companyWebsite",
					"公司網址",
					"只尋找名片上明確可見的公司官方網站網址；只回傳網址，不含標籤，不可回傳社群帳號、Email 或其他文字。",
					result -> result.companyWebsite,
					(result, value) -> result.companyWebsite = value));

	private final ChatClient chatClient;
	private final UserAccountRepository userAccountRepository;
	private final BusinessCardRepository businessCardRepository;
	private final BusinessCardChannels channels;
	private final ImageStorageService imageStorageService;
	private final ImageCache imageCache;
	private final ExecutorService workerExecutor;
	private final ExecutorService fieldRecognitionExecutor;
	private final Map<Long, String> reRecognitionStatuses = new ConcurrentHashMap<>();

	@Autowired
	public ProcessService(final ChatClient chatClient, final UserAccountRepository userAccountRepository,
			final BusinessCardRepository businessCardRepository, final BusinessCardChannels channels,
			final ImageStorageService imageStorageService, final ImageCache imageCache) {
		this(chatClient, userAccountRepository, businessCardRepository, channels, imageStorageService, imageCache,
				Executors.newSingleThreadExecutor(), Executors.newVirtualThreadPerTaskExecutor());
	}

	ProcessService(final ChatClient chatClient, final UserAccountRepository userAccountRepository,
			final BusinessCardRepository businessCardRepository, final BusinessCardChannels channels,
			final ImageStorageService imageStorageService, final ImageCache imageCache,
			final ExecutorService workerExecutor, final ExecutorService fieldRecognitionExecutor) {
		this.chatClient = chatClient;
		this.userAccountRepository = userAccountRepository;
		this.businessCardRepository = businessCardRepository;
		this.channels = channels;
		this.imageStorageService = imageStorageService;
		this.imageCache = imageCache;
		this.workerExecutor = workerExecutor;
		this.fieldRecognitionExecutor = fieldRecognitionExecutor;
	}

	@PreDestroy
	void shutdownExecutors() {
		workerExecutor.shutdown();
		fieldRecognitionExecutor.shutdown();
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
		BusinessCardRecognition result = chatClient.prompt().system(AI_PROMPT).user(u -> u.text("""
				請完整辨識這張名片。
				請務必掃描整張名片所有可見文字，
				特別檢查姓名附近的完整職稱，以及小字區域是否有統一編號、統編、公司網址或股票代號。
				""").media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes))).call()
				.entity(BusinessCardRecognition.class, spec -> spec.useProviderStructuredOutput().validateSchema());
		if (result == null) {
			result = new BusinessCardRecognition();
		}
		return verifyMissingFields(result, mimeType, imageBytes);
	}

	BusinessCardRecognition verifyMissingFields(final BusinessCardRecognition result, final String mimeType,
			final byte[] imageBytes) {
		final List<CompletableFuture<FieldVerification>> verifications = RECOGNITION_FIELDS.stream()
				.filter(field -> isBlank(field.read(result)))
				.map(field -> CompletableFuture.supplyAsync(
							() -> new FieldVerification(field, recognizeField(field, mimeType, imageBytes)),
							fieldRecognitionExecutor)
						.exceptionally(exception -> {
							log.warn("Focused OCR verification failed for field {}", field.apiName(), exception);
							return new FieldVerification(field, "");
						}))
				.toList();

		for (final CompletableFuture<FieldVerification> verificationFuture : verifications) {
			final FieldVerification verification = verificationFuture.join();
			final String verifiedValue = isBlank(verification.value()) ? "" : verification.value().strip();
			verification.field().write(result, verifiedValue);
		}
		return result;
	}

	String recognizeField(final RecognitionField field, final String mimeType, final byte[] imageBytes) {
		final FieldRecognition result = chatClient.prompt().system(FIELD_VERIFICATION_PROMPT).user(u -> u.text("""
				初次完整辨識的「%s」欄位是空白，請重新聚焦檢查圖片，確認該欄位是否真的不存在。

				欄位定義：%s
				若找到多個屬於此欄位的值，使用「、」連接；若確認沒有或無法可靠辨識，value 回傳空字串。
				""".formatted(field.label(), field.instructions()))
				.media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes))).call()
				.entity(FieldRecognition.class, spec -> spec.useProviderStructuredOutput().validateSchema());
		return result == null ? "" : result.value;
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
				名片持有人的完整職稱與所屬部門、單位。
				這是必須主動尋找的重要欄位，不可因字體較小而忽略。
				必須仔細檢查姓名、英文姓名附近及上下方的文字。
				例如「資深技術處長」、「業務部 經理」、「資訊處 協理」等。
				如果部門、處、組、中心等資訊明確屬於持卡人，必須與職稱一起回傳。
				不可將公司名稱、產品名稱或公司介紹誤認為職稱。
				只有確實無法辨識時才回傳空字串。
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
				名片上明確標示的統一編號、統編、Unified Business Number 或 Tax ID。
				只回傳號碼，不要包含「統編」等標籤，也不要包含其他資訊。
				不得僅因看到 8 位數字就自行認定為統編；必須有文字標示或明確語意依據。
				多個值以「、」連接；沒有或無法可靠辨識時回傳空字串。
				""")
		public String businessNumber = "";

		@JsonPropertyDescription("""
				名片上明確標示的股票代號。
				只回傳代號，不要包含「股票代號」等標籤，也不要包含其他資訊；不可根據公司名稱猜測。
				多個值以「、」連接；沒有或無法可靠辨識時回傳空字串。
				""")
		public String stockCode = "";

		@JsonPropertyDescription("""
				名片上明確可見的公司官方網站網址。
				只回傳網址，不要包含「網址」等標籤，也不要包含社群帳號、Email 或其他資訊。
				多個值以「、」連接；沒有或無法可靠辨識時回傳空字串。
				""")
		public String companyWebsite = "";
	}

	@JsonClassDescription("名片單一指定欄位的 OCR 複核結果。")
	public static class FieldRecognition {

		@JsonPropertyDescription("指定欄位在圖片中的完整內容；確認不存在或無法可靠辨識時回傳空字串。")
		public String value = "";
	}

	static final class RecognitionField {
		private final String apiName;
		private final String label;
		private final String instructions;
		private final Function<BusinessCardRecognition, String> reader;
		private final BiConsumer<BusinessCardRecognition, String> writer;

		RecognitionField(final String apiName, final String label, final String instructions,
				final Function<BusinessCardRecognition, String> reader,
				final BiConsumer<BusinessCardRecognition, String> writer) {
			this.apiName = apiName;
			this.label = label;
			this.instructions = instructions;
			this.reader = reader;
			this.writer = writer;
		}

		String apiName() {
			return apiName;
		}

		String label() {
			return label;
		}

		String instructions() {
			return instructions;
		}

		String read(final BusinessCardRecognition result) {
			return reader.apply(result);
		}

		void write(final BusinessCardRecognition result, final String value) {
			writer.accept(result, value);
		}
	}

	private record FieldVerification(RecognitionField field, String value) {
	}

	private record ImageUpload(String mimeType, byte[] bytes) {
	}
}
