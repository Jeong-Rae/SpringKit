package com.gihub.jeongrae.springkit.domain.member.controller;

import com.gihub.jeongrae.springkit.domain.auth.token.JwtProvider;
import com.gihub.jeongrae.springkit.domain.auth.token.TokenResponse;
import com.gihub.jeongrae.springkit.domain.auth.token.vo.AccessToken;
import com.gihub.jeongrae.springkit.domain.auth.token.vo.RefreshToken;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberRegisterRequestDto;
import com.gihub.jeongrae.springkit.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@Tag(name = "Member")
@RequestMapping("api/member")
public class MemberController {
    private final MemberService memberService;
    private final JwtProvider jwtProvider;

    @PostMapping("register")
    public ResponseEntity<TokenResponse> resister(@RequestBody MemberRegisterRequestDto request, HttpServletResponse httpServletResponse) {
        MemberDTO memberDTO = memberService.createMember(request);

        AccessToken accessToken = jwtProvider.generateAccessToken(memberDTO);
        RefreshToken refreshToken = jwtProvider.generateRefreshToken(memberDTO);
        TokenResponse tokenResponse = TokenResponse.of(accessToken, refreshToken);

        this.addAccessTokenToCookie(tokenResponse, httpServletResponse);
        this.addRefreshTokenToCookie(tokenResponse, httpServletResponse);

        return ResponseEntity.ok(tokenResponse);
    }

    private void addAccessTokenToCookie(TokenResponse tokenResponse, HttpServletResponse httpServletResponse) {
        Cookie accessToken = new Cookie("ACCESS_TOKEN", tokenResponse.accessToken().token());
        accessToken.setHttpOnly(true);
        accessToken.setSecure(true);
        accessToken.setPath("/");
        accessToken.setMaxAge(jwtProvider.getACCESS_VALIDITY_TIME().intValue());
        httpServletResponse.addCookie(accessToken);
    }
    private void addRefreshTokenToCookie(TokenResponse tokenResponse, HttpServletResponse httpServletResponse) {
        Cookie refreshCookie = new Cookie("REFRESH_TOKEN", tokenResponse.refreshToken().token());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(jwtProvider.getREFRESH_VALIDITY_TIME().intValue());
        httpServletResponse.addCookie(refreshCookie);
    }
}
