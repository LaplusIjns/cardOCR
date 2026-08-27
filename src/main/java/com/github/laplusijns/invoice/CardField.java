package com.github.laplusijns.invoice;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

enum CardField {
    COMPANY_NAME("companyName", result -> result.companyName, (result, value) -> result.companyName = value),
    NAME("name", result -> result.name, (result, value) -> result.name = value),
    JOB_TITLE("jobTitle", result -> result.jobTitle, (result, value) -> result.jobTitle = value),
    TELEPHONE("telephone", result -> result.telephone, (result, value) -> result.telephone = value),
    MOBILE_PHONE("mobilePhone", result -> result.mobilePhone, (result, value) -> result.mobilePhone = value),
    FAX("fax", result -> result.fax, (result, value) -> result.fax = value),
    EMAIL("email", result -> result.email, (result, value) -> result.email = value),
    ADDRESS("address", result -> result.address, (result, value) -> result.address = value),
    BUSINESS_NUMBER(
            "businessNumber", result -> result.businessNumber, (result, value) -> result.businessNumber = value),
    STOCK_CODE("stockCode", result -> result.stockCode, (result, value) -> result.stockCode = value),
    COMPANY_WEBSITE(
            "companyWebsite", result -> result.companyWebsite, (result, value) -> result.companyWebsite = value);

    private final String apiName;
    private final Function<BusinessCardRecognition, String> reader;
    private final BiConsumer<BusinessCardRecognition, String> writer;

    CardField(
            final String apiName,
            final Function<BusinessCardRecognition, String> reader,
            final BiConsumer<BusinessCardRecognition, String> writer) {
        this.apiName = apiName;
        this.reader = reader;
        this.writer = writer;
    }

    String apiName() {
        return apiName;
    }

    String read(final BusinessCardRecognition result) {
        final String value = reader.apply(result);
        return value == null ? "" : value;
    }

    void write(final BusinessCardRecognition result, final String value) {
        writer.accept(result, value == null ? "" : value.strip());
    }

    void copy(final BusinessCardRecognition source, final BusinessCardRecognition target) {
        write(target, read(source));
    }

    static Optional<CardField> fromApiName(final String apiName) {
        return Arrays.stream(values()).filter(field -> field.apiName.equals(apiName)).findFirst();
    }
}
