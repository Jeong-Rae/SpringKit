package com.github.jeongrae.springkit.domain.auth.token;

import com.github.jeongrae.springkit.domain.auth.token.vo.AccessToken;
import com.github.jeongrae.springkit.domain.auth.token.vo.RefreshToken;

public record TokenResponse(
        AccessToken accessToken,
        RefreshToken refreshToken
) {
    public static TokenResponse of(AccessToken accessToken, RefreshToken refreshToken) {
        return new TokenResponse(accessToken, refreshToken);
    }
}
