package com.github.jeongrae.springkit.domain.auth.token.vo;

public record AccessToken(
        String token
) {
    public static AccessToken of(String token) {
        return new AccessToken(token);
    }
}
