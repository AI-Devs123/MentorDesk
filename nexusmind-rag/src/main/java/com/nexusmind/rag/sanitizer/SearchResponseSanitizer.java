package com.nexusmind.rag.sanitizer;

import com.nexusmind.api.rag.dto.FusedCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混合检索响应 PII 脱敏器（切片 3）。
 *
 * <p>背景：现有 {@code @Sanitize(sanitizeReturnValue = true)} 在 {@code SanitizationAspect} 中
 * 是<b>空操作</b>——该切面只脱敏 {@code String} 类型<b>入参</b>，从不读取 {@code sanitizeReturnValue}
 * 属性，也从不处理非 String 返回值。因此 HybridSearch 的返回体（含 journal 中可能存在的
 * 私人邮箱 / 手机号 / 身份证 / 姓名）不会被自动脱敏。
 *
 * <p>本组件提供<b>显式</b>响应脱敏：对候选的 {@code content} 与敏感 {@code metadata} 做掩码，
 * 防止私密数据经 Triple 返回网关 / 前端逃逸。默认对全部角色生效（保守姿态——
 * journal 集合对 journalAgent 同样可能含 PII，仅对 tutorAgent 脱敏会留下缺口）。
 */
@Component
public class SearchResponseSanitizer {

    // 邮箱（保留域名，掩码本地名前两位之外）
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // 中国大陆手机号
    private static final Pattern CN_MOBILE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    // 身份证号（15 或 18 位，末位可为 X）
    private static final Pattern CN_ID = Pattern.compile("(?<![0-9])([1-9]\\d{14}|[1-9]\\d{16}[\\dXx])(?![0-9])");

    // 敏感元数据键（命中即掩码其值）
    private static final Set<String> SENSITIVE_META_KEYS = Set.of(
            "phone", "mobile", "tel", "email", "mail",
            "idcard", "id", "idno", "name", "realname", "username", "address"
    );

    public List<FusedCandidate> sanitize(List<FusedCandidate> candidates) {
        if (candidates == null) {
            return null;
        }
        List<FusedCandidate> out = new ArrayList<>(candidates.size());
        for (FusedCandidate f : candidates) {
            out.add(sanitizeOne(f));
        }
        return out;
    }

    private FusedCandidate sanitizeOne(FusedCandidate f) {
        String content = f.content() == null ? null : maskContent(f.content());
        Map<String, String> meta = f.metadata();
        Map<String, String> safeMeta = meta == null ? null : new HashMap<>(meta);
        if (safeMeta != null) {
            for (Map.Entry<String, String> e : safeMeta.entrySet()) {
                if (isSensitiveKey(e.getKey()) && e.getValue() != null) {
                    safeMeta.put(e.getKey(), maskValue(e.getValue()));
                }
            }
        }
        return new FusedCandidate(f.docId(), f.collection(), content, f.rrfScore(),
                f.denseRank(), f.sparseRank(), safeMeta);
    }

    private String maskContent(String text) {
        String s = replaceAll(EMAIL, text, this::maskEmail);
        s = replaceAll(CN_MOBILE, s, this::maskMobile);
        s = replaceAll(CN_ID, s, this::maskId);
        return s;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return SENSITIVE_META_KEYS.contains(lower);
    }

    private String maskValue(String value) {
        if (value.length() <= 2) {
            return "***";
        }
        // 值本身像手机号/身份证也走对应掩码，否则统一尾部打码
        if (CN_MOBILE.matcher(value).matches()) {
            return maskMobile(value);
        }
        if (CN_ID.matcher(value).matches()) {
            return maskId(value);
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 1);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String maskedLocal = local.length() <= 2 ? "***" : local.substring(0, 2) + "***";
        return maskedLocal + domain;
    }

    private String maskMobile(String mobile) {
        if (mobile.length() != 11) {
            return mobile.substring(0, 3) + "****" + mobile.substring(3);
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    private String maskId(String id) {
        if (id.length() <= 6) {
            return "****";
        }
        return id.substring(0, 4) + "****" + id.substring(id.length() - 2);
    }

    /**
     * 用给定掩码函数替换文本中所有匹配（避免 replaceAll 的 ${} 占位符歧义）。
     */
    private String replaceAll(Pattern pattern, String text, Function<String, String> masker) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
