package com.gihub.jeongrae.springkit.domain.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gihub.jeongrae.springkit.domain.auth.token.vo.AccessToken;
import com.gihub.jeongrae.springkit.domain.auth.token.vo.RefreshToken;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDetails;
import com.gihub.jeongrae.springkit.domain.auth.token.JwtProvider;
import com.gihub.jeongrae.springkit.domain.auth.token.TokenResponse;
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

        AccessToken accessToken = jwtProvider.generateAccessToken(memberDetails.getMember());
        RefreshToken refreshToken = jwtProvider.generateRefreshToken(memberDetails.getMember());
        TokenResponse tokenResponse = TokenResponse.of(accessToken, refreshToken);

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(tokenResponse));
    }
}
