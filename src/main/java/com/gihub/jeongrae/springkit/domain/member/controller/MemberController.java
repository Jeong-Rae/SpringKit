package com.gihub.jeongrae.springkit.domain.member.controller;

import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.domain.member.dto.RegisterRequest;
import com.gihub.jeongrae.springkit.domain.member.service.MemberDetailsService;
import com.gihub.jeongrae.springkit.domain.member.service.MemberService;
import com.gihub.jeongrae.springkit.global.jwt.JwtProvider;
import com.gihub.jeongrae.springkit.global.jwt.TokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("api/member")
public class MemberController {
    private final MemberService memberService;
    private final JwtProvider jwtProvider;

    @PostMapping("register")
    public ResponseEntity<TokenResponse> resister(RegisterRequest request, HttpServletResponse httpServletResponse) {
        MemberDTO memberDTO = memberService.createMember(request);

        String accessToken = jwtProvider.generateAccessToken(memberDTO);
        String refreshToken = jwtProvider.generateRefreshToken(memberDTO);
        TokenResponse tokenResponse = TokenResponse.of(accessToken, refreshToken);

        this.addAccessTokenToCookie(tokenResponse, httpServletResponse);
        this.addRefreshTokenToCookie(tokenResponse, httpServletResponse);

        return ResponseEntity.ok(tokenResponse);
    }

    private void addAccessTokenToCookie(TokenResponse tokenResponse, HttpServletResponse httpServletResponse) {
        Cookie accessToken = new Cookie("ACCESS_TOKEN", tokenResponse.accessToken());
        accessToken.setHttpOnly(true);
        accessToken.setSecure(true);
        accessToken.setPath("/");
        accessToken.setMaxAge(60 * 60 * 24);
        httpServletResponse.addCookie(accessToken);
    }private void addRefreshTokenToCookie(TokenResponse tokenResponse, HttpServletResponse httpServletResponse) {
        Cookie refreshCookie = new Cookie("REFRESH_TOKEN", tokenResponse.refreshToken());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(60 * 60 * 24);
        httpServletResponse.addCookie(refreshCookie);
    }
}
