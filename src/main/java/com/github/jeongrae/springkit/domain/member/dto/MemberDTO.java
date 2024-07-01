package com.github.jeongrae.springkit.domain.member.dto;

import com.github.jeongrae.springkit.domain.member.domain.Member;

public record MemberDTO(
        Long id,
        String email
) {
    public static MemberDTO from(Member member) {
        return new MemberDTO(member.getId(), member.getEmail());
    }
}
