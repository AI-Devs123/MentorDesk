package com.nexusmind.supervisor.controller;

import com.nexusmind.security.auth.AuthResponse;
import com.nexusmind.security.auth.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器 - 处理用户登录与 Token 生成
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 用户登录接口
     * @param body 包含 username 和 password 的请求体
     * @return 包含 JWT Token 的响应
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        // 简易角色映射（生产可查库校验）
        String role;
        if ("admin".equalsIgnoreCase(username) && "admin123".equals(password)) {
            role = "supervisor";
        } else if ("user".equalsIgnoreCase(username) && "user123".equals(password)) {
            role = "operator";
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "用户名或密码错误"));
        }

        String token = jwtTokenProvider.generateToken(username, role);

        AuthResponse response = new AuthResponse(
                token,
                "Bearer",
                3600L,
                username,
                role
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Token 刷新接口（可选）
     * @param body 包含 token 的请求体
     * @return 新的 JWT Token
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> body) {
        try {
            String token = body.get("token");
            String username = jwtTokenProvider.getUsernameFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            String newToken = jwtTokenProvider.generateToken(username, role);

            AuthResponse response = new AuthResponse(
                    newToken,
                    "Bearer",
                    3600L,
                    username,
                    role
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Token 无效或已过期"));
        }
    }

    /**
     * Token 验证接口
     * @param authHeader Authorization header (格式: Bearer <token>)
     * @return Token 是否有效及用户信息
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "缺少或无效的 Authorization header"));
            }

            String token = authHeader.substring(7);
            String username = jwtTokenProvider.getUsernameFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            Map<String, Object> response = new HashMap<>();
            response.put("valid", true);
            response.put("username", username);
            response.put("role", role);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Token 验证失败: " + e.getMessage()));
        }
    }
}
