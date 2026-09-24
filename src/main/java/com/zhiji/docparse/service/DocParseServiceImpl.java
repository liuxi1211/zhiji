package com.zhiji.docparse.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.docparse.api.DocParseService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 文档解析实现（决策清单 D31）：按扩展名路由——md 走 Markdown 解析（标题树还原 + 导航噪音剔除），
 * txt 走纯文本启发式解析；docx / doc / pdf 显式拒收并给出前置转换指引，扫描件不静默。
 * 本模块不引入任何第三方解析库，改动收敛在包内。
 */
@Service
public class DocParseServiceImpl implements DocParseService {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*)$");

    private static final Pattern MD_LINK_LINE = Pattern.compile("^\\s*(?:\\[([^\\]]*)\\]\\([^)]*\\)\\s*)+$");

    private static final Set<String> NOISE_LINES = Set.of("[toc]", "[[toc]]", "[TOC]", "<!-- toc -->");

    /** txt 启发式标题：行首「第N章/节」或「N.」且行长受限，视作层级 2 标题。 */
    private static final Pattern TXT_HEADING =
            Pattern.compile("^\\s*(第[一二三四五六七八九十百\\d]+[章节部分讲]|[一二三四五六七八九十]+[、.]|\\d{1,2}[、.])\\s*\\S.{0,60}$");

    private static final Set<String> UNSUPPORTED =
            Set.of("doc", "docx", "pdf", "xls", "xlsx", "ppt", "pptx");

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md", "markdown", "txt");
    }

    @Override
    public ParsedDoc parse(String fileName, String content) {
        String ext = extensionOf(fileName);
        if (UNSUPPORTED.contains(ext)) {
            throw BizException.of(400, "DOC_FORMAT_UNSUPPORTED",
                    "不支持直接解析 " + ext.toUpperCase(Locale.ROOT)
                            + " 文件（决策清单 D31）：docx 请用 pandoc 预转为 md 后上传；"
                            + "pdf 文字版请用专用工具（marker / MinerU）转 md，扫描版无文本层不支持");
        }
        if (!supportedExtensions().contains(ext)) {
            throw BizException.of(400, "DOC_FORMAT_UNSUPPORTED",
                    "不支持的文件格式：" + fileName + "（仅支持 md / txt）");
        }
        if (content == null || content.isBlank()) {
            throw BizException.of(400, "DOC_EMPTY", "文件内容为空：" + fileName);
        }
        List<Heading> headings = new ArrayList<>();
        String plainText = "txt".equals(ext) ? parsePlainText(content, headings) : parseMarkdown(content, headings);
        return new ParsedDoc(plainText, List.copyOf(headings), plainText.length());
    }

    /** md 解析：还原标题树（记录行号），剔除目录 / 导航 / 纯链接行等噪音。 */
    private String parseMarkdown(String content, List<Heading> headings) {
        String[] lines = content.split("\n", -1);
        boolean inCodeFence = false;
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeFence = !inCodeFence;
                kept.add(line);
                continue;
            }
            if (!inCodeFence) {
                if (NOISE_LINES.contains(trimmed.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Matcher matcher = MD_HEADING.matcher(line);
                if (matcher.matches()) {
                    headings.add(new Heading(matcher.group(1).length(), matcher.group(2).trim(), kept.size()));
                    kept.add(line);
                    continue;
                }
                if (MD_LINK_LINE.matcher(line).matches()) {
                    // 连续纯链接行视为目录 / 导航区整体剔除（含此前残留的单行链接）
                    if (!kept.isEmpty() && MD_LINK_LINE.matcher(kept.get(kept.size() - 1)).matches()) {
                        kept.remove(kept.size() - 1);
                    }
                    continue;
                }
            }
            kept.add(line);
        }
        return String.join("\n", kept).stripTrailing();
    }

    /** txt 解析：空行分组，序号 / 章节行启发式识别标题（层级固定 2）。 */
    private String parsePlainText(String content, List<Heading> headings) {
        String[] lines = content.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                kept.add("");
                continue;
            }
            if (TXT_HEADING.matcher(line).matches() && !trimmed.endsWith("，") && !trimmed.endsWith("。")) {
                headings.add(new Heading(2, trimmed, kept.size()));
            }
            kept.add(line);
        }
        return String.join("\n", kept).stripTrailing();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
