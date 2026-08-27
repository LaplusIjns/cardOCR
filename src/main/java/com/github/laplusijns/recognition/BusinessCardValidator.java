package com.github.laplusijns.recognition;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BusinessCardValidator {
    private static final Pattern EMAIL = Pattern.compile("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile(".*(?:\\d.*){6,}");
    private static final Pattern BUSINESS_NUMBER = Pattern.compile("^\\d{8}$");
    private static final Pattern WEBSITE = Pattern.compile("(?i)^(?:https?://|www\\.).+");

    public BusinessCardRecognition normalize(final BusinessCardRecognition input) {
        final BusinessCardRecognition result = input == null ? new BusinessCardRecognition() : input.copy();
        result.companyName = clean(result.companyName, 200, value -> true);
        result.name = clean(result.name, 100, value -> true);
        result.jobTitle = clean(result.jobTitle, 100, value -> true);
        result.telephone = clean(result.telephone, 100, PHONE.asMatchPredicate());
        result.mobilePhone = clean(result.mobilePhone, 100, PHONE.asMatchPredicate());
        result.fax = clean(result.fax, 100, PHONE.asMatchPredicate());
        result.email = clean(result.email, 320, EMAIL.asMatchPredicate());
        result.address = clean(result.address, 500, value -> true);
        result.businessNumber = clean(result.businessNumber, 100, BUSINESS_NUMBER.asMatchPredicate());
        result.stockCode = clean(result.stockCode, 100, value -> value.matches("(?i)[A-Z0-9]{2,10}"));
        result.companyWebsite = clean(result.companyWebsite, 500, WEBSITE.asMatchPredicate());
        return result;
    }

    private static String clean(final String raw, final int maxLength, final Predicate<String> validator) {
        if (raw == null || raw.isBlank()) return "";
        final Set<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split("[、;\\n]+"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .filter(validator)
                .forEach(values::add);
        final String joined = String.join("、", values);
        return joined.length() <= maxLength ? joined : joined.substring(0, maxLength);
    }
}
