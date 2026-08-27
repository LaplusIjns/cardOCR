package com.github.laplusijns.recognition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessCardValidatorTest {
    private final BusinessCardValidator validator = new BusinessCardValidator();

    @Test
    void trimsDeduplicatesAndRejectsInvalidStructuredValues() {
        final BusinessCardRecognition input = new BusinessCardRecognition();
        input.email = " alice@example.com 、alice@example.com、not-an-email ";
        input.businessNumber = "12345678、123";
        input.fax = "abc";

        final BusinessCardRecognition result = validator.normalize(input);

        assertThat(result.email).isEqualTo("alice@example.com");
        assertThat(result.businessNumber).isEqualTo("12345678");
        assertThat(result.fax).isEmpty();
    }
}
