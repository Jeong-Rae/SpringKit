package com.gihub.jeongrae.springkit.domain.member.repository;

import com.gihub.jeongrae.springkit.domain.member.domain.MemberOAuth;
import com.gihub.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberOAuthRepository extends JpaRepository<MemberOAuth, Long> {
    Optional<MemberOAuth> findByOauthIdAndOAuthProviderType(String oauthId, OAuthProviderType oAuthProviderType);
}
