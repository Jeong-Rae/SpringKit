package com.gihub.jeongrae.springkit.domain.member.service.impl;

import com.gihub.jeongrae.springkit.domain.member.converter.MemberConverter;
import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.domain.member.dto.RegisterRequest;
import com.gihub.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.gihub.jeongrae.springkit.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class MemberServiceImpl implements MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    @Override
    public MemberDTO createMember(RegisterRequest request) {
        Member member = Member.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();

        member = memberRepository.save(member);

        return MemberConverter.convert(member);
    }
}
