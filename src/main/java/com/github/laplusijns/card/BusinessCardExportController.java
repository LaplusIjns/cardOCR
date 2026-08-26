package com.github.laplusijns.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.image.ImageStorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exports")
public class BusinessCardExportController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String[] HEADERS = {
        "編號", "公司名稱", "姓名", "職稱", "電話", "行動電話", "傳真", "EMAIL", "地址", "備註", "建立時間"
    };

    private final UserAccountRepository userAccountRepository;
    private final BusinessCardRepository businessCardRepository;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessCardExportController(
            final UserAccountRepository userAccountRepository,
            final BusinessCardRepository businessCardRepository,
            final ImageStorageService imageStorageService) {
        this.userAccountRepository = userAccountRepository;
        this.businessCardRepository = businessCardRepository;
        this.imageStorageService = imageStorageService;
    }

    @GetMapping(value = "/archive", produces = "application/zip")
    public ResponseEntity<byte[]> archive(final Authentication authentication) throws IOException {
        final List<BusinessCard> cards = cardsFor(authentication);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            final List<Map<String, Object>> records = cards.stream().map(this::record).toList();
            zip.putNextEntry(new ZipEntry("cards.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(records));
            zip.closeEntry();

            for (BusinessCard card : cards) {
                final String extension = extension(card.getImagePath());
                zip.putNextEntry(new ZipEntry("images/" + card.getImageId() + extension));
                zip.write(imageStorageService.read(card.getImagePath()));
                zip.closeEntry();
            }
        }

        return download(output.toByteArray(), "business-cards-" + FILE_TIME.format(java.time.Instant.now()) + ".zip", "application/zip");
    }

    @GetMapping(value = "/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> excel(final Authentication authentication) throws IOException {
        final List<BusinessCard> cards = cardsFor(authentication);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            final Sheet sheet = workbook.createSheet("名片資料");
            sheet.createFreezePane(0, 1);
            final CellStyle headerStyle = workbook.createCellStyle();
            final Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            final Row header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.length; column++) {
                header.createCell(column).setCellValue(HEADERS[column]);
                header.getCell(column).setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (BusinessCard card : cards) {
                final Row row = sheet.createRow(rowIndex++);
                final String[] values = values(card);
                for (int column = 0; column < values.length; column++) {
                    row.createCell(column).setCellValue(values[column]);
                }
            }
            for (int column = 0; column < HEADERS.length; column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 15000));
            }
            workbook.write(output);
        }

        return download(output.toByteArray(), "business-cards-" + FILE_TIME.format(java.time.Instant.now()) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private List<BusinessCard> cardsFor(final Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("User is not authenticated");
        }
        final UserAccount user = userAccountRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("User account not found"));
        return businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(user.getId());
    }

    private Map<String, Object> record(final BusinessCard card) {
        final Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", card.getId());
        value.put("companyName", text(card.getCompanyName()));
        value.put("name", text(card.getName()));
        value.put("jobTitle", text(card.getJobTitle()));
        value.put("telephone", text(card.getTelephone()));
        value.put("mobilePhone", text(card.getMobilePhone()));
        value.put("fax", text(card.getFax()));
        value.put("email", text(card.getEmail()));
        value.put("address", text(card.getAddress()));
        value.put("notes", text(card.getNotes()));
        value.put("createdAt", card.getCreatedAt());
        value.put("image", "images/" + card.getImageId() + extension(card.getImagePath()));
        return value;
    }

    private static String[] values(final BusinessCard card) {
        return new String[] {
            String.valueOf(card.getId()), text(card.getCompanyName()), text(card.getName()), text(card.getJobTitle()),
            text(card.getTelephone()), text(card.getMobilePhone()), text(card.getFax()), text(card.getEmail()),
            text(card.getAddress()), text(card.getNotes()), CELL_TIME.format(card.getCreatedAt())
        };
    }

    private static String extension(final String imagePath) {
        final String name = Path.of(imagePath).getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static String text(final String value) {
        return value == null ? "" : value;
    }

    private static ResponseEntity<byte[]> download(final byte[] body, final String filename, final String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
