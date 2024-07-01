package com.github.jeongrae.springkit.domain.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
import com.github.jeongrae.springkit.global.util.BasicResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@RequiredArgsConstructor
@Component
public class OAuthFailureHandler implements AuthenticationFailureHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        BasicResponse basicResponse = BasicResponse
                .of(new BusinessException(ErrorCode.INVALID_EMAIL_OR_PASSWORD, HttpStatus.BAD_REQUEST));

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(basicResponse));
    }
}
