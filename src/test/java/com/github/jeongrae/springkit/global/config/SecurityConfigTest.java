package com.github.jeongrae.springkit.global.config;

import com.github.jeongrae.springkit.domain.auth.handler.DefaultAuthenticationFailureHandler;
import com.github.jeongrae.springkit.domain.auth.handler.DefaultAuthenticationSuccessHandler;
import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.dto.MemberDetails;
import com.github.jeongrae.springkit.domain.member.repository.MemberRepository;
import com.github.jeongrae.springkit.domain.member.service.impl.MemberServiceImpl;
import com.github.jeongrae.springkit.domain.member.vo.EncodedPassword;
import com.github.jeongrae.springkit.global.filter.AuthenticationTokenFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("시큐리티 로그인 테스트")
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemberServiceImpl memberService;

    @MockBean
    private MemberRepository memberRepository;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private DefaultAuthenticationSuccessHandler defaultAuthenticationSuccessHandler;

    @MockBean
    private DefaultAuthenticationFailureHandler defaultAuthenticationFailureHandler;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AuthenticationTokenFilter authenticationTokenFilter;



    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(this.context)
                .apply(springSecurity())
                .build();

        // Mock Member 데이터 생성
        String username = "correct";
        String password = "correct";

        Member mockMember = Member.builder()
                .email(username)
                .encodedPassword(new EncodedPassword(passwordEncoder.encode(password)))
                .build();
        UserDetails userDetails = new MemberDetails(mockMember);

        // Mock  동작 정의
        when(memberRepository.findMemberByEmail(username))
                .thenReturn(Optional.of(mockMember));
        when(userDetailsService.loadUserByUsername(username))
                .thenReturn(userDetails);
        when(authenticationManager.authenticate(Mockito.any()))
                .thenAnswer(invocation -> {
                    UsernamePasswordAuthenticationToken token = invocation.getArgument(0);
                    if (username.equals(token.getPrincipal()) && password.equals(token.getCredentials())) {
                        return new UsernamePasswordAuthenticationToken(userDetails, password, userDetails.getAuthorities());
                    } else {
                        throw new BadCredentialsException("Bad credentials");
                    }
                });
    }

    @Test
    @DisplayName("로그인 성공")
    public void 로그인_성공() throws Exception {
        String username = "correct";
        String password = "correct";

        mockMvc.perform(
                        formLogin("/login")
                                .user(username)
                                .password(password))
                .andDo(print())
                .andExpect(authenticated());
    }

    @Test
    @DisplayName("아이디 오류 실패")
    public void 아이디_오류_실패() throws Exception {
        String wrongUsername = "wrong";
        String password = "correct";

        mockMvc.perform(
                        formLogin("/login")
                                .user(wrongUsername)
                                .password(password))
                .andDo(print())
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("비밀번호 오류 실패")
    public void 비밀번호_오류_실패() throws Exception {
        String username = "correct";
        String wrongPassword = "wrong";

        mockMvc.perform(
                        formLogin("/login")
                                .user(username)
                                .password(wrongPassword))
                .andDo(print())
                .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("중복 회원가입 실패")
    public void 중복_회원가입_실패() throws Exception {
        String username = "correct";
        String password = "correct";

        mockMvc.perform(
                        formLogin("/login")
                                .user(username)
                                .password(password))
                .andDo(print())
                .andExpect(unauthenticated());
    }

}
