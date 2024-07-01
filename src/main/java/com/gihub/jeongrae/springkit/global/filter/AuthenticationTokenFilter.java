package com.gihub.jeongrae.springkit.global.filter;

import com.gihub.jeongrae.springkit.domain.member.service.MemberDetailsService;
import com.gihub.jeongrae.springkit.global.exception.BusinessException;
import com.gihub.jeongrae.springkit.global.exception.ErrorCode;
import com.gihub.jeongrae.springkit.domain.auth.token.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Component
public class AuthenticationTokenFilter extends OncePerRequestFilter {
    private final JwtProvider jwtProvider;
    private final MemberDetailsService memberDetailsService;

    private final Logger LOGGER = LoggerFactory.getLogger(AuthenticationTokenFilter.class);

    @Value("${host.develop.api.ant-match.uri}")
    private List<String> antMatchURIs = new ArrayList<>();


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        // 토큰 검증을 하지 않아야 하는 Path에 대한 예외 처리
        String path = request.getRequestURI();
        for (String antMatchURI : antMatchURIs) {
            if (path.startsWith(antMatchURI)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String token = this.resolveToken(request);

        String aud = jwtProvider.parseAudience(token); // 토큰 Aud에 Member email을 기록하고 있음

        UserDetails userDetails = memberDetailsService.loadUserByUsername(aud); // memberId를 기반으로 조회

        UsernamePasswordAuthenticationToken authenticationToken
                = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());


        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        filterChain.doFilter(request, response);

    }

    private String resolveToken(HttpServletRequest httpServletRequest) {
        String authorization = httpServletRequest.getHeader("Authorization");
        if (authorization == null) {
            throw new BusinessException(ErrorCode.EMPTY_TOKEN_PROVIDED, HttpStatus.UNAUTHORIZED);
        }

        if (authorization.startsWith("Bearer ")) { // Bearer 기반 토큰 인증을 함
            return authorization.substring(7);
        }

        throw new BusinessException(ErrorCode.EMPTY_TOKEN_PROVIDED, HttpStatus.UNAUTHORIZED);
    }
}
