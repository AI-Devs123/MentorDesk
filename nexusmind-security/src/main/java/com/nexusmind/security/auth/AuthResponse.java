package com.nexusmind.security.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应对象
 */
@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String token_type;
    private Long expires_in;
    private String username;
    private String role;
}
