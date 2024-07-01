package com.github.jeongrae.springkit.global.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.Map;

public class DefaultAuthenticationFilter extends UsernamePasswordAuthenticationFilter {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultAuthenticationFilter(AuthenticationManager authenticationManager,
                                       AuthenticationSuccessHandler successHandler,
                                       AuthenticationFailureHandler failureHandler) {
        super(authenticationManager);
        this.setAuthenticationSuccessHandler(successHandler);
        this.setAuthenticationFailureHandler(failureHandler);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request,
                                                HttpServletResponse response) throws AuthenticationException {
        if ("application/json".equals(request.getContentType())) {
            try {
                Map<String, String> authRequest = objectMapper.readValue(request.getInputStream(), Map.class);
                String username = authRequest.get("username");
                String password = authRequest.get("password");

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, password);

                return this.getAuthenticationManager().authenticate(authToken);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InternalAuthenticationServiceException e) {
                if (e.getCause() instanceof BusinessException) {
                    throw new BusinessException(ErrorCode.INVALID_EMAIL_OR_PASSWORD, HttpStatus.BAD_REQUEST);
                }
                throw new BusinessException(ErrorCode.FAIL_PROCEED, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            return super.attemptAuthentication(request, response);
        }
    }
}