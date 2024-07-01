package com.github.jeongrae.springkit.domain.auth.token;

import com.github.jeongrae.springkit.domain.auth.token.vo.AccessToken;
import com.github.jeongrae.springkit.domain.auth.token.vo.RefreshToken;
import com.github.jeongrae.springkit.domain.member.domain.Member;
import com.github.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.github.jeongrae.springkit.global.exception.BusinessException;
import com.github.jeongrae.springkit.global.exception.ErrorCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Getter
@RequiredArgsConstructor
@Component
public class JwtProvider implements TokenProvider {
    @Value("${jwt.secret}")
    private String JWT_SECRET = "";
    private SecretKey SECRET_KEY;
    private final String ISS = "github.com/jeongrae";
    private final Long ACCESS_VALIDITY_TIME = 60 * 60 * 1000L;
    private final Long REFRESH_VALIDITY_TIME = 7 * 24 * 60 * 60 * 1000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(JwtProvider.class);

    @PostConstruct
    protected void init() {
        this.SECRET_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
    }

    public AccessToken generateAccessToken(Member member) {
        if (member.getEmail() == null || member.getEmail().isBlank()) {
            return AccessToken.of("");
        }
        return this.generateAccessToken(member.getEmail());
    }
    public AccessToken generateAccessToken(MemberDTO memberDTO) {
        if (memberDTO.email() == null || memberDTO.email().isBlank()) {
            return AccessToken.of("");
        }
        return this.generateAccessToken(memberDTO.email());
    }

    private AccessToken generateAccessToken(String email) {
        String token = Jwts.builder()
                .claim("type", "access")
                .issuer(ISS)
                .audience().add(email).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + ACCESS_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateAccessToken] {}", token);
        return AccessToken.of(token);
    }

    public RefreshToken generateRefreshToken(Member member) {
        if (member.getEmail() == null || member.getEmail().isBlank()) {
            return RefreshToken.of("");
        }
        return this.generateRefreshToken(member.getEmail());
    }
    public RefreshToken generateRefreshToken(MemberDTO memberDTO) {
        if (memberDTO.email() == null || memberDTO.email().isBlank()) {
            return RefreshToken.of("");
        }
        return this.generateRefreshToken(memberDTO.email());
    }

    private RefreshToken generateRefreshToken(String email) {
        String token = Jwts.builder()
                .claim("type", "refresh")
                .issuer(ISS)
                .audience().add(email).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + REFRESH_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateRefreshToken] {}", token);
        return RefreshToken.of(token);
    }

    public String parseAudience(String token) {
        try {
            Jws<Claims> claims = Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token);

            if (claims.getPayload()
                    .getExpiration()
                    .before(new Date())) {
                throw new BusinessException(ErrorCode.EXPIRED_ACCESS_TOKEN, HttpStatus.UNAUTHORIZED);
            }

            String aud = claims.getPayload()
                    .getAudience()
                    .iterator()
                    .next();

            return aud;
        } catch (JwtException | IllegalArgumentException e) {
            LOGGER.warn("[parseAudience] {} :{}", ErrorCode.INVALID_TOKEN, token);
            throw new BusinessException(ErrorCode.INVALID_TOKEN, HttpStatus.UNAUTHORIZED);
        } catch (BusinessException e) {
            LOGGER.warn("[parseAudience] {} :{}", ErrorCode.EXPIRED_ACCESS_TOKEN, token);
            throw new BusinessException(ErrorCode.EXPIRED_ACCESS_TOKEN, HttpStatus.UNAUTHORIZED);
        }
    }

    public boolean validateToken(String token) {
        try {
            Jws<Claims> claims = Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token);

            return !claims.getPayload().getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            LOGGER.warn("[validateToken] {}: {}", ErrorCode.INVALID_TOKEN, token);
            return false;
        }
    }
}
