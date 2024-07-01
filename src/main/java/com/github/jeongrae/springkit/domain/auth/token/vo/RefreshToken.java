package com.github.jeongrae.springkit.domain.auth.token.vo;

public record RefreshToken(
        String token
) {
    public static RefreshToken of(String token) {
        return new RefreshToken(token);
    }
}
