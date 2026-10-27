package com.nexusmind.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SSRF 防护校验器
 * <p>
 * 防御服务端请求伪造：回环 / 内网 / 链路本地 / 通配地址、DNS 重绑（多 A 记录 fail-closed）、
 * IPv6 ULA 与 IPv4-Mapped IPv6 内网掩码、非法协议。
 * <p>
 * 设计要点（与 Fast / Render 抓取器协作）：
 * <ul>
 *   <li>{@link #validateUrl(String)} 供「重定向 / 网关入口」做一次性准入校验；</li>
 *   <li>{@link #resolveAllowedAddresses(String)} 供抓取器的自定义 {@code Dns} 在「连接建立时」钉死已核准的公网 IP，
 *       从根本上消除 DNS 重绑（检查期与连接期解析到不同 IP）的绕过窗口。</li>
 * </ul>
 */
@Slf4j
@Component
public class SsrfProtectionValidator {

    private static final String[] ALLOWED_SCHEMES = {"http", "https"};

    /**
     * 校验目标 URL 是否安全（协议白名单 + 所有解析 IP 审计）。调用即代表「准入」，不安全直接抛异常。
     */
    public void validateUrl(String targetUrl) throws SecurityException {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new SecurityException("SecurityGuard 拦截: 目标 URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("SecurityGuard 拦截: URL 格式畸形 -> " + targetUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || Arrays.stream(ALLOWED_SCHEMES).noneMatch(s -> s.equalsIgnoreCase(scheme))) {
            throw new SecurityException("SecurityGuard 拦截: 仅允许 http/https 协议 -> " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("SecurityGuard 拦截: 非法主机名 -> " + targetUrl);
        }
        // 解析并审计该主机名下全部 IP（fail-closed：任一内网即整体拒绝）
        resolveAllowedAddresses(host);
    }

    /**
     * 解析主机名并返回「全部通过安全审计」的 IP 列表。
     * <p>
     * 任一解析地址命中内网 / 回环 / 保留段即整体拒绝（fail-closed）。
     * 供抓取器自定义 {@code Dns} 调用——连接时直接钉死这些已核准的 {@link InetAddress} 对象，
     * 不会再重新解析，因此 DNS 重绑无法把连接期解析偷换成内网 IP。
     */
    public List<InetAddress> resolveAllowedAddresses(String host) throws SecurityException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SecurityException("SecurityGuard 拦截: 域名解析失败 -> " + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SecurityException("SecurityGuard 拦截: 域名无法解析 -> " + host);
        }
        List<InetAddress> safe = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            verifyIpSafety(address); // 不安全立即抛 SecurityException（fail-closed）
            safe.add(address);
        }
        return safe;
    }

    /**
     * 兼容旧式「校验并返回首个安全 IP」入口；仅供需要显式 IP 的调用方使用。
     * 真实抓取请改用 {@link #resolveAllowedAddresses(String)} + 自定义 Dns，避免返回值被丢弃导致重绑绕过。
     */
    public InetAddress validateAndResolve(String targetUrl) throws SecurityException {
        validateUrl(targetUrl);
        try {
            return InetAddress.getAllByName(URI.create(targetUrl).getHost())[0];
        } catch (UnknownHostException e) {
            throw new SecurityException("SecurityGuard 拦截: 无法解析目标域名", e);
        }
    }

    private void verifyIpSafety(InetAddress address) {
        if (address.isLoopbackAddress()) {
            throw new SecurityException("SecurityGuard 拦截: 禁止访问回环地址 (Loopback) -> " + address.getHostAddress());
        }
        if (address.isSiteLocalAddress()) {
            throw new SecurityException("SecurityGuard 拦截: 禁止访问局域网私有网段 (SiteLocal) -> " + address.getHostAddress());
        }
        if (address.isLinkLocalAddress()) {
            throw new SecurityException("SecurityGuard 拦截: 禁止访问链路本地地址 (LinkLocal) -> " + address.getHostAddress());
        }
        if (address.isAnyLocalAddress()) {
            throw new SecurityException("SecurityGuard 拦截: 禁止访问通配地址 (AnyLocal) -> " + address.getHostAddress());
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (isUniqueLocalAddress(bytes) || isIPv4MappedPrivate(bytes)) {
                throw new SecurityException("SecurityGuard 拦截: 非法 IPv6 内网掩码 -> " + address.getHostAddress());
            }
        }
    }

    private boolean isUniqueLocalAddress(byte[] bytes) {
        // fc00::/7
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean isIPv4MappedPrivate(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        int b12 = bytes[12] & 0xff;
        int b13 = bytes[13] & 0xff;
        return b12 == 127 || b12 == 10
                || (b12 == 172 && b13 >= 16 && b13 <= 31)
                || (b12 == 192 && b13 == 168);
    }
}
