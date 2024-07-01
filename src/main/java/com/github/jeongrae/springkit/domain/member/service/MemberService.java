package com.github.jeongrae.springkit.domain.member.service;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import com.github.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.github.jeongrae.springkit.domain.member.dto.MemberRegisterRequestDto;

public interface MemberService {
    MemberDTO createMember(MemberRegisterRequestDto request);

    Member findMemberByOAuthId(String oauthId, OAuthProviderType providerType);
}
