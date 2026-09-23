package com.zhiji.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 自实现 HS256 三段式签名 token（JWT 兼容格式），刻意不引 jjwt：只为一个签名引依赖不值当。
 * payload 只包含内部固定字段（typ/uid/role/exp），值全部来自内部枚举与自增 id，
 * 因此解析用正则提取是安全的；签名校验保证内容不可篡改。
 */
@Component
public class JwtSupport {

    private static final String FIELD_TEMPLATE = "\"%s\":(\"?[^\",}]*\"?)";

    private final byte[] secret;

    public JwtSupport(AuthProperties properties) {
        this.secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String typ, long uid, String role, long ttlSeconds) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = "{\"typ\":\"" + typ + "\",\"uid\":" + uid
                + ",\"role\":\"" + role + "\",\"exp\":" + exp + "}";
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = b64(payload);
        return header + "." + body + "." + b64(hmac(header + "." + body));
    }

    public Payload parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("token 结构不完整");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("token 签名编码非法");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("token 签名不匹配");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        long exp = Long.parseLong(extractValue(payload, "exp"));
        if (exp < System.currentTimeMillis() / 1000) {
            throw new IllegalArgumentException("token 已过期");
        }
        return new Payload(
                extractValue(payload, "typ"),
                Long.parseLong(extractValue(payload, "uid")),
                extractValue(payload, "role"));
    }

    private String extractValue(String json, String key) {
        var matcher = Pattern.compile(FIELD_TEMPLATE.formatted(key)).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("payload 缺少字段: " + key);
        }
        String value = matcher.group(1);
        return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String b64(String data) {
        return b64(data.getBytes(StandardCharsets.UTF_8));
    }

    public record Payload(String typ, long uid, String role) {
    }
}
