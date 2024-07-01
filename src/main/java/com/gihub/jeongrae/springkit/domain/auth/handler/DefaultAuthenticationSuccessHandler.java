package com.gihub.jeongrae.springkit.domain.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDetails;
import com.gihub.jeongrae.springkit.global.jwt.JwtProvider;
import com.gihub.jeongrae.springkit.global.jwt.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@RequiredArgsConstructor
@Component
public class DefaultAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtProvider jwtProvider;
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        MemberDetails memberDetails = (MemberDetails) authentication.getPrincipal();

        String accessToken = jwtProvider.generateAccessToken(memberDetails.getMember());
        String refreshToken = jwtProvider.generateRefreshToken(memberDetails.getMember());
        TokenResponse tokenResponse = TokenResponse.of(accessToken, refreshToken);

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(tokenResponse));
    }
}
