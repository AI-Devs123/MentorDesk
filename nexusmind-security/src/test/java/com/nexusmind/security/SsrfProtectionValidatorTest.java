package com.nexusmind.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SSRF 防护校验器单元测试。
 * <p>
 * 全部使用 IP 字面量（离线执行，不依赖 DNS / 外网），覆盖：
 * 回环 / 私有网段 / 链路本地（云元数据）/ 通配 / 非法协议 / IPv6 ULA / IPv4-Mapped 内网 / 公网放行。
 */
class SsrfProtectionValidatorTest {

    private final SsrfProtectionValidator validator = new SsrfProtectionValidator();

    @Test
    @DisplayName("公网 IP 字面量放行（1.1.1.1 为 Cloudflare 公网 DNS）")
    void shouldAllowPublicIpLiteral() {
        assertDoesNotThrow(() -> validator.validateUrl("http://1.1.1.1"));
        assertDoesNotThrow(() -> validator.validateUrl("https://1.1.1.1"));
    }

    @Test
    @DisplayName("拦截回环地址 127.0.0.1 / localhost")
    void shouldBlockLoopbackAddress() {
        SecurityException ex1 = assertThrows(SecurityException.class,
                () -> validator.validateUrl("http://127.0.0.1:8848/nacos"));
        org.junit.jupiter.api.Assertions.assertTrue(ex1.getMessage().contains("回环地址"),
                "期望命中回环地址拦截, 实际: " + ex1.getMessage());

        SecurityException ex2 = assertThrows(SecurityException.class,
                () -> validator.validateUrl("http://localhost:8080"));
        org.junit.jupiter.api.Assertions.assertTrue(ex2.getMessage().contains("回环地址"),
                "期望命中回环地址拦截, 实际: " + ex2.getMessage());
    }

    @Test
    @DisplayName("拦截 IPv6 回环 ::1")
    void shouldBlockIpv6Loopback() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> validator.validateUrl("http://[::1]:8080"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("回环地址"),
                "期望命中回环地址拦截, 实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("拦截内网私有网段 10.x / 172.16.x / 192.168.x")
    void shouldBlockPrivateSubnet() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://192.168.1.1"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://10.0.0.5:6379"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://172.16.0.1:8080"));
    }

    @Test
    @DisplayName("拦截云厂商元数据接口 169.254.169.254（链路本地）")
    void shouldBlockCloudMetadataAddress() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> validator.validateUrl("http://169.254.169.254/latest/meta-data/"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("链路本地地址"),
                "期望命中链路本地拦截, 实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("拦截非法协议 file:// / gopher:// / ftp://")
    void shouldBlockDisallowedSchemes() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("file:///etc/passwd"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("gopher://127.0.0.1:6379/_*"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("ftp://example.com"));
    }

    @Test
    @DisplayName("拦截 IPv6 唯一本地地址 fc00::/7（补齐 isSiteLocalAddress 对 IPv6 盲区）")
    void shouldBlockIpv6UniqueLocal() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://[fc00::1]"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://[fd12:3456::1]"));
    }

    @Test
    @DisplayName("拦截 IPv4-Mapped IPv6 内网掩码 ::ffff:127.0.0.1 / ::ffff:192.168.1.1")
    void shouldBlockIpv4MappedPrivate() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://[::ffff:127.0.0.1]"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://[::ffff:192.168.1.1]"));
    }

    @Test
    @DisplayName("拦截通配地址 0.0.0.0")
    void shouldBlockAnyLocalAddress() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://0.0.0.0:8080"));
    }

    @Test
    @DisplayName("拦截畸形 URL")
    void shouldBlockMalformedUrl() {
        assertThrows(SecurityException.class, () -> validator.validateUrl("not a url ::::"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://"));
    }
}
