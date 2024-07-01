package com.github.jeongrae.springkit.domain.member.service;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.dto.MemberDetails;
import com.github.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class MemberDetailsService implements UserDetailsService {
    private final MemberRepository memberRepository;

    private final Logger LOGGER = LoggerFactory.getLogger(MemberDetailsService.class);
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Member member = memberRepository.findMemberByEmail(username)
                .orElseThrow(() -> {
                    LOGGER.info("[loadUserByUsername] username:{}, {}", username, ErrorCode.MEMBER_NOT_FOUND);
                    return new BusinessException(ErrorCode.MEMBER_NOT_FOUND, HttpStatus.NOT_FOUND);
                });
        return new MemberDetails(member);
    }
}
