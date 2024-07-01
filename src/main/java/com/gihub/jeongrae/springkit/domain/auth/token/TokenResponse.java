package com.gihub.jeongrae.springkit.domain.auth.token;

import com.gihub.jeongrae.springkit.domain.auth.token.vo.AccessToken;
import com.gihub.jeongrae.springkit.domain.auth.token.vo.RefreshToken;

public record TokenResponse(
        AccessToken accessToken,
        RefreshToken refreshToken
) {
    public static TokenResponse of(AccessToken accessToken, RefreshToken refreshToken) {
        return new TokenResponse(accessToken, refreshToken);
    }
}
