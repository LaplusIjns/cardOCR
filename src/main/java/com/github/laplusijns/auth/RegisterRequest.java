package com.github.laplusijns.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "帳號只能包含英文字母、數字、句點、底線或連字號")
        String username,

        @NotBlank @Size(min = 8, max = 72) String password) {}
