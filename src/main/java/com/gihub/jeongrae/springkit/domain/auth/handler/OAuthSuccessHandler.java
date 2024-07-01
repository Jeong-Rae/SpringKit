package com.gihub.jeongrae.springkit.domain.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import com.gihub.jeongrae.springkit.domain.member.domain.OAuthProviderType;
import com.gihub.jeongrae.springkit.domain.member.service.MemberService;
import com.gihub.jeongrae.springkit.domain.auth.jwt.JwtProvider;
import com.gihub.jeongrae.springkit.domain.auth.jwt.TokenResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {
    private final MemberService memberService;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String registrationId = oAuth2User.getAttributes().get("registrationId").toString();

        String oauthId = null;
        OAuthProviderType oAuthProviderType = null;
        if (registrationId.equals(OAuthProviderType.GOOGLE.getProvider())) {
            oauthId = attributes.get("id").toString();
            oAuthProviderType = OAuthProviderType.GOOGLE;
        }

        Member member = memberService.findMemberByOAuthId(oauthId, oAuthProviderType);

        String accessToken = jwtProvider.generateAccessToken(member);
        String refreshToken = jwtProvider.generateRefreshToken(member);
        TokenResponse tokenResponse = TokenResponse.of(accessToken, refreshToken);

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(tokenResponse));
    }
}
