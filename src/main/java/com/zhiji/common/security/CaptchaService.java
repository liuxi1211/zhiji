package com.zhiji.common.security;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 自研 SVG 图形验证码：4 位随机字符，内存 Map 存储（TTL 内一次性消费）。
 * 刻意不引第三方验证码库；captcha-enabled=false 时直接放行（测试环境用）。
 */
@Service
public class CaptchaService {

    private static final String CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final long TTL_MS = 5 * 60_000L;
    private static final String[] COLORS = {"#2b6cf6", "#e6772e", "#1f9d55", "#8a5cf6", "#c2255c"};

    private final AuthProperties properties;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public CaptchaService(AuthProperties properties) {
        this.properties = properties;
    }

    public record Captcha(String captchaId, String svg) {
    }

    public Captcha generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new Entry(code.toString(), System.currentTimeMillis() + TTL_MS));
        return new Captcha(id, render(code.toString(), random));
    }

    public boolean verify(String captchaId, String code) {
        if (!properties.isCaptchaEnabled()) {
            return true;
        }
        if (captchaId == null || code == null) {
            return false;
        }
        Entry entry = store.remove(captchaId);
        return entry != null
                && entry.expireAt() > System.currentTimeMillis()
                && entry.code().equalsIgnoreCase(code);
    }

    private String render(String code, ThreadLocalRandom random) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\" viewBox=\"0 0 120 40\">");
        svg.append("<rect width=\"120\" height=\"40\" fill=\"#f5f7fa\"/>");
        for (int i = 0; i < 4; i++) {
            int x = 14 + i * 26;
            int y = 28 + random.nextInt(-4, 5);
            int rotate = random.nextInt(-25, 26);
            svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" font-size=\"24\" font-family=\"monospace\" font-weight=\"bold\" fill=\"")
                    .append(COLORS[random.nextInt(COLORS.length)])
                    .append("\" transform=\"rotate(").append(rotate).append(' ').append(x).append(' ').append(y)
                    .append(")\">").append(code.charAt(i)).append("</text>");
        }
        for (int i = 0; i < 3; i++) {
            svg.append("<line x1=\"").append(random.nextInt(120)).append("\" y1=\"").append(random.nextInt(40))
                    .append("\" x2=\"").append(random.nextInt(120)).append("\" y2=\"").append(random.nextInt(40))
                    .append("\" stroke=\"").append(COLORS[random.nextInt(COLORS.length)])
                    .append("\" stroke-width=\"1\"/>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private record Entry(String code, long expireAt) {
    }
}
