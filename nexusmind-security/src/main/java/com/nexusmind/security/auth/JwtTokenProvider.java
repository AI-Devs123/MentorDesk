package com.nexusmind.security.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 生成与验证工具类
 * 支持 1 小时有效期，角色信息写入 Claims
 */
@Component
public class JwtTokenProvider {

    // 至少 256 位的密钥（生产环境建议从 Nacos 配置中心注入）
    private static final String SECRET = "nexusmind-supervisor-jwt-secret-key-2026-very-secure-key";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    // 1 小时有效期 (毫秒)
    private static final long EXPIRATION_TIME = 60 * 60 * 1000L;

    /**
     * 生成 JWT Token
     * @param username 用户名
     * @param role 角色信息
     * @return JWT Token 字符串
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + EXPIRATION_TIME);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 验证并解析 Token
     * @param token JWT Token 字符串
     * @return Token 中的 Claims 信息
     * @throws io.jsonwebtoken.JwtException 如果 Token 验证失败
     */
    public Claims validateAndParseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中提取用户名
     * @param token JWT Token 字符串
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        return validateAndParseToken(token).getSubject();
    }

    /**
     * 从 Token 中提取角色
     * @param token JWT Token 字符串
     * @return 角色信息
     */
    public String getRoleFromToken(String token) {
        return validateAndParseToken(token).get("role", String.class);
    }
}
