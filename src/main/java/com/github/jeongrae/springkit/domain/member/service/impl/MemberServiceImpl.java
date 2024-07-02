package com.github.jeongrae.springkit.domain.member.service.impl;

import com.github.jeongrae.springkit.domain.member.converter.MemberConverter;
import com.github.jeongrae.springkit.domain.member.vo.EncodedPassword;
import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import com.github.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.github.jeongrae.springkit.domain.member.dto.MemberRegisterRequestDto;
import com.github.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.github.jeongrae.springkit.domain.member.service.MemberService;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(MemberServiceImpl.class);
    @Override
    @Transactional
    public MemberDTO createMember(MemberRegisterRequestDto request) {
        if (memberRepository.existsByEmail(request.email())) {
            LOGGER.warn("[createMember] email: {}, {}", request.email(), ErrorCode.EMAIL_ALREADY_EXISTS);
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, HttpStatus.BAD_REQUEST);
        }

        Member member = Member.builder()
                .email(request.email())
                .encodedPassword(new EncodedPassword(passwordEncoder.encode(request.rawPassword().password())))
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
