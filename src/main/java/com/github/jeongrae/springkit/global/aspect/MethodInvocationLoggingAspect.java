package com.github.jeongrae.springkit.global.aspect;


import com.github.jeongrae.springkit.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

@Aspect
@Component
@RequiredArgsConstructor
public class MethodInvocationLoggingAspect {
    private final Logger LOGGER = LoggerFactory.getLogger(MethodInvocationLoggingAspect.class);
    @Around("@annotation(com.github.jeongrae.springkit.global.aspect.LogMethodInvocation) || @annotation(com.github.jeongrae.springkit.global.aspect.LogMonitoring)")
    public Object logMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String requestId = (String) request.getAttribute("RequestID");

        LOGGER.info("[{}] RequestID: {}, Parameter: {}", signature.getMethod().getName(), requestId, Arrays.toString(joinPoint.getArgs()));

        Object result = ErrorCode.FAIL_PROCEED;
        try {
            result = joinPoint.proceed();
        } finally {
            LOGGER.info("[{}] RequestID: {}, Result: {}", signature.getMethod().getName(), requestId, result);
        }

        return result;
    }
}