package com.github.jeongrae.springkit.domain.member.converter;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.dto.MemberDTO;

public class MemberConverter {
    public static MemberDTO convert(Member member) {
        return new MemberDTO(member.getId(), member.getEmail());
    }
}
