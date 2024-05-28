package com.gihub.jeongrae.springkit.domain.member.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter @ToString
@Entity
@Table(name = "member_oauth")
public class MemberOAuth {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_oauth_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id") @NotNull
    private Member member;

    @Column(name = "oauth_id") @NotNull
    private Long oauthId;

    @Column(name = "provider_type") @NotNull
    @Enumerated(value = EnumType.STRING)
    private OAuthProviderType oAuthProviderType;

    @Builder
    public MemberOAuth(Long id, Member member, Long oauthId, OAuthProviderType oAuthProviderType) {
        this.id = id;
        this.member = member;
        this.oauthId = oauthId;
        this.oAuthProviderType = oAuthProviderType;
    }

    public MemberOAuth() {

    }
}
