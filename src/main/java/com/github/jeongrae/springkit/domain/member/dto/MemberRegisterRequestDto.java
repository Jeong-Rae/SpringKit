package com.github.jeongrae.springkit.domain.member.dto;

import com.github.jeongrae.springkit.domain.member.vo.RawPassword;
import jakarta.validation.constraints.Email;

public record MemberRegisterRequestDto(
        @Email String email,
        RawPassword rawPassword
) {
}
