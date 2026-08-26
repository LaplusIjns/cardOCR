package com.github.laplusijns.auth;

public record SessionInfo(boolean authenticated, String username) {

    public static SessionInfo anonymous() {
        return new SessionInfo(false, null);
    }
}
