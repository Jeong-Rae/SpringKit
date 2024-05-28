package com.gihub.jeongrae.springkit.domain.member.dto;

import jakarta.validation.constraints.Email;

public record RegisterRequest(
        @Email String email,
        String password
) {
}
