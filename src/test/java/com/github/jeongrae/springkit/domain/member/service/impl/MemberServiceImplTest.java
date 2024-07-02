package com.github.jeongrae.springkit.domain.member.service.impl;

import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.github.jeongrae.springkit.domain.member.dto.MemberRegisterRequestDto;
import com.github.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.github.jeongrae.springkit.domain.member.vo.RawPassword;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberServiceImpl memberService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        String rawPassword = "password";
        String encodedPassword = "encodedPassword";
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);

        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> {
                    Member member = invocation.getArgument(0);
                    return Member.builder()
                            .id(1L)
                            .email(member.getEmail())
                            .encodedPassword(member.getEncodedPassword())
                            .build();
                });
    }

    @Test
    @DisplayName("회원가입 성공")
    void 회원가입_성공() {
        // Given
        String email = "test@example.com";
        String rawPassword = "password";
        MemberRegisterRequestDto request = new MemberRegisterRequestDto(email, new RawPassword(rawPassword));
        MemberDTO memberDTO = new MemberDTO(1L, email);

        when(memberRepository.existsByEmail(email)).thenReturn(false);

        // When
        MemberDTO result = memberService.createMember(request);

        // Then
        assertNotNull(result);
        assertEquals(memberDTO, result);
    }

    @Test
    @DisplayName("회원가입 실패 - 이메일 중복")
    void 회원가입_실패_이메일중복() {
        // Given
        String email = "test@example.com";
        String rawPassword = "password";
        MemberRegisterRequestDto request = new MemberRegisterRequestDto(email, new RawPassword(rawPassword));

        when(memberRepository.existsByEmail(email)).thenReturn(true);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> memberService.createMember(request));
        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
    }
}
