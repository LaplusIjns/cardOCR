package com.github.laplusijns.card;

public record BusinessCardUpdateRequest(
        String companyName,
        String name,
        String jobTitle,
        String telephone,
        String mobilePhone,
        String fax,
        String email,
        String address,
        String notes) {}
