package com.gihub.jeongrae.springkit.domain.member.domain;

import lombok.Getter;

@Getter
public enum OAuthProviderType {
    GOOGLE("google"),
    KAKAO("kakao"),
    NAVER("naver");

    private final String provider;

    OAuthProviderType(String provider) {
        this.provider = provider;
    }
}
