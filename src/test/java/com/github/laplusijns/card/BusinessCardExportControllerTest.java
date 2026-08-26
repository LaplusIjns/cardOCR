package com.github.laplusijns.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.laplusijns.auth.UserAccount;
import com.github.laplusijns.auth.UserAccountRepository;
import com.github.laplusijns.image.ImageStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class BusinessCardExportControllerTest {

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BusinessCardRepository businessCardRepository = mock(BusinessCardRepository.class);
    private final ImageStorageService imageStorageService = mock(ImageStorageService.class);
    private final BusinessCardExportController controller =
            new BusinessCardExportController(userAccountRepository, businessCardRepository, imageStorageService);

    @Test
    void archiveKeepsZipStreamOpenAfterWritingJson() throws Exception {
        final UserAccount user = new UserAccount("alice", "hash");
        final var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password", List.of(() -> "ROLE_USER"));
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(null)).thenReturn(List.of());

        final var response = controller.archive(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("cards.json");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("[ ]");
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void excelOmitsCreatedAtAndSortsByIdAscending() throws Exception {
        final UserAccount user = new UserAccount("alice", "hash");
        final var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "password", List.of(() -> "ROLE_USER"));
        final BusinessCard card2 = mock(BusinessCard.class);
        final BusinessCard card1 = mock(BusinessCard.class);
        when(card2.getId()).thenReturn(2L);
        when(card1.getId()).thenReturn(1L);
        when(userAccountRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(businessCardRepository.findAllByUser_IdOrderByCreatedAtDesc(null)).thenReturn(List.of(card2, card1));

        final var response = controller.excel(authentication);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            final var sheet = workbook.getSheet("名片資料");
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 10);
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("備註");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("2");
        }
    }
}
