package com.gihub.jeongrae.springkit.global.jwt;

import com.gihub.jeongrae.springkit.domain.member.domain.Member;
import com.gihub.jeongrae.springkit.domain.member.dto.MemberDTO;
import com.gihub.jeongrae.springkit.global.exception.BusinessException;
import com.gihub.jeongrae.springkit.global.exception.ErrorCode;
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
import java.util.Set;

@Getter
@RequiredArgsConstructor
@Component
public class JwtProvider {
    @Value("${jwt.secret}")
    private String JWT_SECRET = "";
    private SecretKey SECRET_KEY;
    private final String ISS = "github.com/jeongrae";
    private final Long ACCESS_VALIDITY_TIME = 60 * 60 * 1000L;
    private final Long REFRESH_VALIDITY_TIME = 7 * 24 * 60 * 60 * 1000L;
    private final Logger LOGGER = LoggerFactory.getLogger(JwtProvider.class);

    @PostConstruct
    protected void init() {
        this.SECRET_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
    }

    public String generateAccessToken(Member member) {
        String token = Jwts.builder()
                .issuer(ISS)
                .audience().add(member.getId().toString()).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + ACCESS_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateAccessToken] {}", token);
        return token;
    }
    public String generateAccessToken(MemberDTO memberDTO) {
        String token = Jwts.builder()
                .issuer(ISS)
                .audience().add(memberDTO.id().toString()).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + ACCESS_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateAccessToken] {}", token);
        return token;
    }

    public String generateRefreshToken(Member member) {
        String token = Jwts.builder()
                .issuer(ISS)
                .audience().add(member.getId().toString()).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + REFRESH_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateRefreshToken] {}", token);
        return token;
    }
    public String generateRefreshToken(MemberDTO memberDTO) {
        String token = Jwts.builder()
                .issuer(ISS)
                .audience().add(memberDTO.id().toString()).and()
                .issuedAt(new Date())
                .expiration(new Date(new Date().getTime() + REFRESH_VALIDITY_TIME))
                .signWith(SECRET_KEY)
                .compact();

        LOGGER.info("[generateRefreshToken] {}", token);
        return token;
    }

    public Long parseAudience(String token) {
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

            return Long.parseLong(aud);
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
