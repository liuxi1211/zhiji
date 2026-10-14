package com.zhiji.common.asset;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 正文里的图片引用解析：Markdown 语法 {@code ![alt](ref)} 与 HTML {@code <img src="ref">} 两种形态。
 *
 * 素材导入时「zip 内图片改写」与进标准库时「外链图片本地化」是同一件事的两面——都要先认出引用、
 * 再决定替换成什么地址，所以解析规则收敛在这里，避免两处各写一份正则而渐渐走样。
 */
public final class MarkdownImages {

    /** Markdown 图片语法。 */
    public static final Pattern MD = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");

    /** HTML img 的 src 属性。 */
    public static final Pattern HTML_SRC = Pattern.compile(
            "(<img\\b[^>]*\\bsrc\\s*=\\s*[\"'])([^\"']+)([\"'])", Pattern.CASE_INSENSITIVE);

    private MarkdownImages() {
    }

    /** 取引用路径的首个 token（丢弃 Markdown 的可选标题部分，如 {@code x.png "标题"}）。 */
    public static String firstToken(String ref) {
        String trimmed = ref == null ? "" : ref.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /** 逐匹配替换：回调返回 null 表示保留原文片段，否则用返回值替换。 */
    public static String replace(Pattern pattern, String input, Function<Matcher, String> fn) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = fn.apply(matcher);
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
