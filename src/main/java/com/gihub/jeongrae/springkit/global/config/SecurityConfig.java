package com.gihub.jeongrae.springkit.global.config;

import com.gihub.jeongrae.springkit.domain.auth.handler.DefaultAuthenticationFailureHandler;
import com.gihub.jeongrae.springkit.domain.auth.handler.DefaultAuthenticationSuccessHandler;
import com.gihub.jeongrae.springkit.global.filter.*;
import com.gihub.jeongrae.springkit.global.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.CorsFilter;

@RequiredArgsConstructor
@EnableWebSecurity
@Configuration
public class SecurityConfig {
    private final DefaultCorsFilter defaultCorsFilter;
    private final DefaultServletFilter defaultServletFilter;
    private final AuthenticationTokenFilter authenticationTokenFilter;
    private final BusinessExceptionHandlerFilter businessExceptionHandlerFilter;
    private final JwtProvider jwtProvider;
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(defaultCorsFilter, CorsFilter.class)
                .addFilterBefore(defaultServletFilter, DefaultCorsFilter.class)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/")
                        .permitAll())
                .logout(logout -> logout.permitAll())
                .addFilterBefore(
                        defaultAuthenticationFilter(authenticationManager(http.getSharedObject(AuthenticationConfiguration.class))),
                        UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/", "/home/**", "/index/**", "/index.js", "/favicon.ico", "/swagger-ui/**", "/v3/**").permitAll()
                .requestMatchers("/api/auth/**", "/api/member/register").permitAll()
                .anyRequest().authenticated())


                .addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(businessExceptionHandlerFilter, AuthenticationTokenFilter.class);

        return http.build();
    }
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DefaultAuthenticationFilter defaultAuthenticationFilter(AuthenticationManager authenticationManager) {
        DefaultAuthenticationFilter filter = new DefaultAuthenticationFilter(authenticationManager,
                new DefaultAuthenticationSuccessHandler(jwtProvider),
                new DefaultAuthenticationFailureHandler());
        filter.setFilterProcessesUrl("/login");
        return filter;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
