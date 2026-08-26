package com.github.laplusijns.card;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

public record BusinessCardDTO(
        String key,
        @Nullable Long id,
        String companyName,
        String name,
        String jobTitle,
        String telephone,
        String mobilePhone,
        String fax,
        String email,
        String address,
        String notes,
        String status,
        String imageUrl)
        implements Serializable {
    public static BusinessCardDTO progress(final String key) {
        return new BusinessCardDTO(key, null, "", "", "", "", "", "", "", "", "", "處理中", key);
    }

    public static BusinessCardDTO error(final String key, final String message) {
        return new BusinessCardDTO(key, null, "", "", "", "", "", "", "", "", message, "辨識失敗", key);
    }

    public static BusinessCardDTO from(final BusinessCard card) {
        return from(card, "辨識完成");
    }

    public static BusinessCardDTO from(final BusinessCard card, final String status) {
        return new BusinessCardDTO(
                card.getImageId(),
                card.getId(),
                value(card.getCompanyName()),
                value(card.getName()),
                value(card.getJobTitle()),
                value(card.getTelephone()),
                value(card.getMobilePhone()),
                value(card.getFax()),
                value(card.getEmail()),
                value(card.getAddress()),
                value(card.getNotes()),
                status,
                card.getImageId());
    }

    private static String value(final String value) {
        return value == null ? "" : value;
    }
}
