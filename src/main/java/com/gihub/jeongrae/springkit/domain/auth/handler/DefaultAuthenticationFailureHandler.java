package com.gihub.jeongrae.springkit.domain.auth.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gihub.jeongrae.springkit.global.exception.BusinessException;
import com.gihub.jeongrae.springkit.global.exception.ErrorCode;
import com.gihub.jeongrae.springkit.global.util.BasicResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

public class DefaultAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {


        BasicResponse basicResponse = BasicResponse
                .of(new BusinessException(ErrorCode.INVALID_EMAIL_OR_PASSWORD, HttpStatus.BAD_REQUEST));

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(basicResponse));

    }

}
