package com.gihub.jeongrae.springkit.domain.member.service.impl;

import com.gihub.jeongrae.springkit.domain.member.converter.MemberConverter;
import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import com.gihub.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.domain.member.dto.RegisterRequest;
import com.gihub.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.gihub.jeongrae.springkit.domain.member.service.MemberService;
import com.gihub.jeongrae.springkit.global.exception.BusinessException;
import com.gihub.jeongrae.springkit.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class MemberServiceImpl implements MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final Logger LOGGER = LoggerFactory.getLogger(MemberServiceImpl.class);
    @Override
    @Transactional
    public MemberDTO createMember(RegisterRequest request) {
        Member member = Member.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();

        member = memberRepository.save(member);

        return MemberConverter.convert(member);
    }

    @Override
    @Transactional
    public Member findMemberByOAuthId(String oauthId, OAuthProviderType providerType) {
        Member member = memberRepository.findMemberByOAuthIdAndProviderType(oauthId, providerType)
                .orElseThrow(() -> {
                    LOGGER.warn("[findMemberByOAuthId] id:{}, {}", oauthId, ErrorCode.MEMBER_NOT_FOUND);
                    return new BusinessException(ErrorCode.MEMBER_NOT_FOUND, HttpStatus.NOT_FOUND);
                });

        return member;
    }
}
