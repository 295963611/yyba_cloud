package com.yyba.cloud.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 令牌服务。
 * <p>负责生成与解析登录 Token，供 Android / Web 客户端携带访问受保护接口。</p>
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final int expireDays;

    public JwtService(
            @Value("${yyba.jwt.secret}") String secret,
            @Value("${yyba.jwt.expire-days:7}") int expireDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireDays = expireDays;
    }

    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireDays, ChronoUnit.DAYS)))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(Claims claims) {
        return claims.get("userId", Long.class);
    }
}
