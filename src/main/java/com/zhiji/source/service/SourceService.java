package com.zhiji.source.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiji.common.asset.MarkdownImages;
import com.zhiji.common.asset.MediaAssetService;
import com.zhiji.common.constant.ConstantRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.common.web.PageResult;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.source.domain.SourceItem;
import com.zhiji.source.repo.SourceItemMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材库核心服务（核心模块定义 5.1）：手写新增、txt/md 导入、URL 抓取、
 * 轻量整理（编辑/AI 改写由 SourceAiService 产出后回写）、状态推进与提交入库。
 * 多主题长文由人工在素材层拆分（复制删减成多条素材），不再提供系统拆分。
 *
 * 导入语义（txt / md / zip）：
 * - 单文件（txt/md）→ 一条素材，正文原文保留；
 * - zip 压缩包 → 包内**每个文档各成一条素材**（一份文档一条，不再把多文档拼成一条）；
 *   包内图片经 {@link MediaAssetService} 收入公共图片库，正文里的本地图片引用改写为
 *   公共地址 {@code /api/assets/{sha256}.{ext}}（无鉴权、任何人可查，B/C 端与分享页共用）；
 *   未被任何文档引用的图片不入库。
 */
@Service
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final List<String> IMAGE_EXTS = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");

    private final SourceItemMapper mapper;
    private final ConstantRegistry constantRegistry;
    private final KbTopicService kbTopicService;
    private final KbContentService kbContentService;
    private final MediaAssetService mediaAssetService;

    public SourceService(SourceItemMapper mapper, ConstantRegistry constantRegistry,
            KbTopicService kbTopicService, KbContentService kbContentService,
            MediaAssetService mediaAssetService) {
        this.mapper = mapper;
        this.constantRegistry = constantRegistry;
        this.kbTopicService = kbTopicService;
        this.kbContentService = kbContentService;
        this.mediaAssetService = mediaAssetService;
    }

    public record SourceItemView(long id, Long parentId, String title, String contentMd, String summary,
            String contentType, String difficulty, String domainCode, String domainName, String topicName,
            String sourceType, String sourceUrl, String status, String submittedAt,
            int childCount, String createdAt, String updatedAt) {
    }

    /** 素材库分页响应：items/total 之外带全库 facets——状态分段计数与领域候选不随筛选变化（与原全量口径一致）。 */
    public record SourcePage(List<SourceItemView> items, long total,
            Map<String, Long> statusCounts, List<String> domains) {
    }

    /**
     * 素材库分页查询（只列母素材）：状态 / 领域 / 关键词（标题或概述 LIKE）过滤，
     * updatedAt 倒序。领域 OTHER 是展示层的伪领域（主题名以 OTHER· 开头），SQL 侧用同规则展开。
     */
    public SourcePage page(String status, String domainCode, String keyword, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int size = PageResult.normalizePageSize(pageSize);
        LambdaQueryWrapper<SourceItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNull(SourceItem::getParentId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SourceItem::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.strip() + "%";
            wrapper.and(w -> w.like(SourceItem::getTitle, like).or().like(SourceItem::getSummary, like));
        }
        if (domainCode != null && !domainCode.isBlank()) {
            if ("OTHER".equals(domainCode)) {
                wrapper.and(w -> w.eq(SourceItem::getDomainCode, "OTHER")
                        .or().likeRight(SourceItem::getTopicName, "OTHER·"));
            } else {
                wrapper.eq(SourceItem::getDomainCode, domainCode);
            }
        }
        wrapper.orderByDesc(SourceItem::getUpdatedAt);
        Page<SourceItem> result = mapper.selectPage(Page.of(p, size), wrapper);
        Map<Long, Long> childCounts = childCounts();
        Map<String, String> domainNames = domainNames();
        List<SourceItemView> items = result.getRecords().stream()
                .map(item -> toView(item, childCounts, domainNames))
                .toList();
        SourceFacets facets = facets();
        return new SourcePage(items, result.getTotal(), facets.statusCounts(), facets.domains());
    }

    /** 全库 facets：状态分段计数 + 领域候选（含 OTHER 伪领域），供筛选区渲染。 */
    private SourceFacets facets() {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<SourceItem>()
                .select("status", "COUNT(*) AS cnt")
                .isNull("parent_id")
                .groupBy("status"))) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status != null && cnt instanceof Number number) {
                statusCounts.put(String.valueOf(status), number.longValue());
            }
        }
        List<String> domains = new ArrayList<>();
        for (Object code : mapper.selectObjs(new QueryWrapper<SourceItem>()
                .select("DISTINCT domain_code")
                .isNull("parent_id")
                .isNotNull("domain_code"))) {
            if (code != null && !domains.contains(String.valueOf(code))) {
                domains.add(String.valueOf(code));
            }
        }
        Long otherCount = mapper.selectCount(new QueryWrapper<SourceItem>()
                .isNull("parent_id")
                .and(w -> w.eq("domain_code", "OTHER").or().likeRight("topic_name", "OTHER·")));
        if (otherCount != null && otherCount > 0 && !domains.contains("OTHER")) {
            domains.add("OTHER");
        }
        return new SourceFacets(statusCounts, domains);
    }

    public record SourceFacets(Map<String, Long> statusCounts, List<String> domains) {
    }

    public SourceItemView get(long id) {
        return toView(requireItem(id), childCounts(), domainNames());
    }

    public SourceItemView create(String title, String contentMd, String sourceUrl, String sourceType,
            String contentType) {
        if (title == null || title.isBlank()) {
            throw BizException.validation("标题不能为空");
        }
        String type = sourceType == null || sourceType.isBlank() ? "manual" : sourceType;
        if (!List.of("manual", "import", "url", "inferred").contains(type)) {
            throw BizException.validation("来源类型取值不合法");
        }
        SourceItem item = new SourceItem();
        item.setTitle(title.strip());
        item.setContentMd(contentMd == null ? "" : contentMd);
        item.setContentType(normalizeContentType(contentType));
        item.setSourceType(type);
        item.setSourceUrl((sourceUrl == null || sourceUrl.isBlank()) ? null : sourceUrl.strip());
        item.setStatus("pending");
        item.setCreatedBy(requireStaffId());
        mapper.insert(item);
        return toView(item, Map.of(), domainNames());
    }

    /** 目标类型标记规整：空为未定（null），只接受 kp/doc。 */
    public static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String value = contentType.strip();
        if (!"kp".equals(value) && !"doc".equals(value)) {
            throw BizException.validation("类型取值不合法：知识点(kp)或资料讲解(doc)");
        }
        return value;
    }

    /**
     * 导入 txt / md 单文件或含图片资源的 zip 压缩包，返回本次导入的素材列表。
     * 单文件 → 一条素材；zip → 包内每个文档各成一条素材，图片收入公共图片库并改写正文引用。
     */
    public List<SourceItemView> importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.validation("请选择要导入的文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".zip")) {
                return importZip(name, file.getInputStream());
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
                byte[] bytes = file.getBytes();
                if (bytes.length > MAX_TEXT_BYTES) {
                    throw BizException.validation("文本文件超过 2MB 上限");
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                String title = stripExt(name);
                return List.of(create(title, text, null, "import", null));
            }
            throw BizException.validation("仅支持 txt / md 文本与 zip 压缩包（含打包图片资源）");
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.of(500, "SOURCE_IMPORT_FAILED", "文件读取失败：" + e.getMessage());
        }
    }

    /**
     * zip 导入：包内每个文本文件各成一条素材（包内几个文档就导入几篇），图片按正文引用收入公共图片库。
     * 文档以「包内完整路径」为键——不同目录下的同名文档各成一条、互不覆盖；
     * 图片按文件名与正文引用匹配（引用 {@code ./images/x.png} 命中包内 {@code x.png}），
     * 命中即入库并把引用改写为公共地址；未被引用的图片不入库（落盘即真相的收料口径）。
     */
    private List<SourceItemView> importZip(String name, InputStream in) throws IOException {
        byte[] all = in.readAllBytes();
        if (all.length > MAX_ZIP_BYTES) {
            throw BizException.validation("压缩包超过 50MB 上限");
        }
        // 包内文档按「完整路径」为键（LinkedHashMap 保序）：同名不同目录各成一条，几个文档就导入几篇
        Map<String, String> docs = new LinkedHashMap<>();
        // 图片按文件名索引供正文引用匹配
        Map<String, byte[]> images = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(all))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryPath = normalizeEntry(entry.getName());
                if (entryPath.isEmpty()) {
                    continue;
                }
                String entryName = fileName(entryPath);
                String entryLower = entryName.toLowerCase(Locale.ROOT);
                byte[] bytes = zip.readAllBytes();
                if (IMAGE_EXTS.stream().anyMatch(entryLower::endsWith)) {
                    if (bytes.length <= MAX_IMAGE_BYTES) {
                        images.putIfAbsent(entryName, bytes);
                    }
                } else if (entryLower.endsWith(".txt") || entryLower.endsWith(".md")
                        || entryLower.endsWith(".markdown")) {
                    docs.put(entryPath, new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }
        if (docs.isEmpty() && images.isEmpty()) {
            throw BizException.validation("压缩包内没有可导入的文本或图片");
        }
        List<SourceItemView> created = new ArrayList<>();
        if (docs.isEmpty()) {
            // 只有图片没有正文：保留空稿素材（图片无处引用，不入库），供人工补录
            created.add(create(stripExt(name), "", null, "import", null));
            return created;
        }
        // 一个文档一条素材：包内几个文档就导入几篇，标题取文档文件名（去扩展名）
        for (Map.Entry<String, String> doc : docs.entrySet()) {
            String rewritten = rewriteImageRefs(doc.getValue(), images);
            created.add(create(stripExt(fileName(doc.getKey())), rewritten, null, "import", null));
        }
        return created;
    }

    /** 规整 zip 内条目路径：统一分隔符、去前导斜杠（Windows 打包软件可能写反斜杠）。 */
    private static String normalizeEntry(String entryName) {
        if (entryName == null) {
            return "";
        }
        return entryName.replace('\\', '/').replaceAll("^/+", "").strip();
    }

    /** 把正文里指向包内图片的本地引用改写为公共图片库地址；未命中的引用保持原样。 */
    private String rewriteImageRefs(String contentMd, Map<String, byte[]> images) {
        if (contentMd == null || contentMd.isBlank() || images.isEmpty()) {
            return contentMd;
        }
        String afterMd = MarkdownImages.replace(MarkdownImages.MD, contentMd, m -> {
            String url = toPublicUrl(MarkdownImages.firstToken(m.group(2)), images);
            return url == null ? null : "![" + m.group(1) + "](" + url + ")";
        });
        return MarkdownImages.replace(MarkdownImages.HTML_SRC, afterMd, m -> {
            String url = toPublicUrl(MarkdownImages.firstToken(m.group(2)), images);
            return url == null ? null : m.group(1) + url + m.group(3);
        });
    }

    /** 本地引用 → 公共图片库地址；外链/data:/已是公共地址的引用一律不动（返回 null）。 */
    private String toPublicUrl(String ref, Map<String, byte[]> images) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")
                || lower.startsWith("asset:") || lower.startsWith("/api/assets/")) {
            return null;
        }
        String base;
        try {
            base = java.nio.file.Path.of(ref).getFileName().toString();
        } catch (RuntimeException e) {
            return null;
        }
        byte[] bytes = images.get(base);
        if (bytes == null) {
            return null;
        }
        String stored = mediaAssetService.store(bytes, base);
        return stored == null ? null : mediaAssetService.publicUrl(stored);
    }

    /** 取路径的最后一段文件名（兼容 / 与 \ 两种分隔符）。 */
    private static String fileName(String path) {
        try {
            return java.nio.file.Path.of(path).getFileName().toString();
        } catch (RuntimeException e) {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** URL 抓取：正文转 Markdown；失败回退空稿人工补（正文为空、URL 进出处字段）。 */
    public SourceItemView fetchUrl(String url) {
        if (url == null || url.isBlank()) {
            throw BizException.validation("URL 不能为空");
        }
        String trimmed = url.strip();
        if (!trimmed.matches("^https?://.*")) {
            throw BizException.validation("仅支持 http/https 链接");
        }
        String title = trimmed;
        String markdown = "";
        try {
            org.jsoup.nodes.Document doc = Jsoup.connect(trimmed)
                    .userAgent("Mozilla/5.0 (compatible; zhiji-bot/0.1)")
                    .timeout(15_000)
                    .get();
            title = doc.title() == null || doc.title().isBlank() ? trimmed : doc.title().strip();
            markdown = htmlToMarkdown(doc.body());
        } catch (IOException e) {
            log.warn("URL 抓取失败，回退空稿：{} - {}", trimmed, e.getMessage());
        }
        SourceItemView created = create(title, markdown, trimmed, "url", null);
        return get(created.id());
    }

    /**
     * 极简 HTML → Markdown：去 script/style/nav 等噪音节点，标题/段落/列表按结构转写，
     * 链接保留 [text](href)。不追求完美还原——抓回来的稿子还要过 AI 改写或人工整理。
     */
    private String htmlToMarkdown(org.jsoup.nodes.Element body) {
        body.select("script,style,noscript,iframe,nav,header,footer,aside,form,button").remove();
        StringBuilder md = new StringBuilder();
        for (org.jsoup.nodes.Element el : body.children()) {
            String tag = el.tagName();
            switch (tag) {
                case "h1" -> md.append("\n# ").append(el.text()).append("\n");
                case "h2" -> md.append("\n## ").append(el.text()).append("\n");
                case "h3" -> md.append("\n### ").append(el.text()).append("\n");
                case "h4", "h5", "h6" -> md.append("\n#### ").append(el.text()).append("\n");
                case "p", "blockquote" -> {
                    if (!el.text().isBlank()) {
                        md.append("\n").append(el.text()).append("\n");
                    }
                }
                case "pre" -> md.append("\n```\n").append(el.text()).append("\n```\n");
                case "ul", "ol" -> {
                    int index = 1;
                    for (org.jsoup.nodes.Element li : el.select("> li")) {
                        md.append(tag.equals("ol") ? "\n" + index++ + ". " : "\n- ").append(li.text());
                    }
                    md.append("\n");
                }
                case "img" -> {
                    String src = el.absUrl("src");
                    if (!src.isBlank()) {
                        md.append("\n![").append(el.attr("alt")).append("](").append(src).append(")\n");
                    }
                }
                default -> {
                    if (!el.text().isBlank() && el.children().isEmpty()) {
                        md.append("\n").append(el.text()).append("\n");
                    } else if (el.children().size() > 0 && el.select("h1,h2,h3,h4,p,li,pre").size() > 0) {
                        md.append(htmlToMarkdown(el));
                    }
                }
            }
        }
        return md.toString().replace("\n{3,}", "\n\n").strip();
    }

    public SourceItemView update(long id, String title, String contentMd, String summary, String contentType,
            String difficulty, String domainCode, String topicName, String sourceUrl) {
        SourceItem item = requireEditable(id);
        if (title != null) {
            if (title.isBlank()) {
                throw BizException.validation("标题不能为空");
            }
            item.setTitle(title.strip());
        }
        if (contentMd != null) {
            item.setContentMd(contentMd);
        }
        if (summary != null) {
            if (summary.length() > 200) {
                throw BizException.validation("概述控制在 100 字内");
            }
            item.setSummary(summary.isBlank() ? null : summary.strip());
        }
        if (contentType != null) {
            item.setContentType(normalizeContentType(contentType));
        }
        if (difficulty != null) {
            if (!difficulty.isBlank() && !List.of("junior", "mid", "senior", "expert").contains(difficulty)) {
                throw BizException.validation("难度取值不合法");
            }
            item.setDifficulty(difficulty.isBlank() ? null : difficulty);
        }
        if (domainCode != null) {
            if (!domainCode.isBlank() && constantRegistry.items("domain") != null
                    && constantRegistry.items("domain").stream().noneMatch(i -> i.code().equals(domainCode))) {
                throw BizException.validation("领域取值不在清单内");
            }
            item.setDomainCode(domainCode.isBlank() ? null : domainCode);
        }
        if (topicName != null) {
            item.setTopicName(topicName.isBlank() ? null : topicName.strip());
        }
        if (sourceUrl != null) {
            item.setSourceUrl(sourceUrl.isBlank() ? null : sourceUrl.strip());
        }
        item.setUpdatedAt(now());
        mapper.updateById(item);
        return toView(item, childCounts(), domainNames());
    }

    /**
     * 提交入库（核心模块定义 5.1 → 5.2 的衔接）：概述与难度齐备才可提交（两者都是入库门槛）；
     * 素材在标准库创建 pending 内容并进审核队列（不校不上线），素材标 submitted 长期保留；
     * followups 为向导内确认的追问链草稿（仅面试题线消费，id 已在前端分配）。
     * 标准库内容与素材进门即断联（红线 7）。
     */
    public List<SourceItemView> submit(long id, String domainCode, Long topicId, String contentType,
            List<com.zhiji.kb.service.KbContentService.Followup> followups) {
        SourceItem item = requireItem(id);
        if (item.getSummary() == null || item.getSummary().isBlank()) {
            throw BizException.validation("概述齐备才能进入标准库：请先补齐概述");
        }
        if (item.getDifficulty() == null || item.getDifficulty().isBlank()) {
            throw BizException.validation("难度齐备才能进入标准库：请先拍板难度");
        }
        if (domainCode == null || domainCode.isBlank()) {
            throw BizException.validation("领域必须人工点选");
        }
        if (constantRegistry.items("domain") != null
                && constantRegistry.items("domain").stream().noneMatch(i -> i.code().equals(domainCode))) {
            throw BizException.validation("领域取值不在清单内");
        }
        if (topicId == null) {
            throw BizException.validation("主题位置必须确认后提交");
        }
        if (!"kp".equals(contentType) && !"doc".equals(contentType)) {
            throw BizException.validation("定类取值不合法：面试题线(kp)或文章线(doc)");
        }
        // 主题位置人工确认：主题必须存在且属于所选领域（AI 只在领域内建议，位置由人定）
        com.zhiji.kb.domain.KbTopic topic = kbTopicService.requireActive(topicId);
        if (!topic.getDomainCode().equals(domainCode)) {
            throw BizException.validation("主题不属于所选领域");
        }
        String stamp = now();
        // 每条素材在标准库成稿：pending + 审核留痕（kb 侧自行校验主题与领域匹配）
        kbContentService.createPending(contentType, domainCode, topicId, item.getTitle(),
                item.getSummary(), item.getContentMd(), item.getDifficulty(),
                "doc".equals(contentType) ? item.getSourceUrl() : null, "wizard", null, followups);
        item.setStatus("submitted");
        item.setContentType(contentType);
        item.setDomainCode(domainCode);
        item.setTopicName(topic.getName());
        item.setSubmittedAt(stamp);
        item.setUpdatedAt(stamp);
        mapper.updateById(item);
        return List.of(toView(item, childCounts(), domainNames()));
    }

    public SourceItemView discard(long id) {
        SourceItem item = requireItem(id);
        if ("submitted".equals(item.getStatus())) {
            throw BizException.validation("已入库素材长期保留（溯源资产），不提供放弃/删除");
        }
        item.setStatus("discarded");
        item.setUpdatedAt(now());
        mapper.updateById(item);
        return toView(item, childCounts(), domainNames());
    }

    /** 删除：仅限未入库素材。图片已进公共图片库（内容寻址共享），随素材删除不联动清理。 */
    public void delete(long id) {
        SourceItem item = requireItem(id);
        if ("submitted".equals(item.getStatus())) {
            throw BizException.validation("已入库素材保留不删（B 端溯源资产）");
        }
        if (item.getParentId() == null) {
            long children = mapper.selectCount(new LambdaQueryWrapper<SourceItem>()
                    .eq(SourceItem::getParentId, item.getId())
                    .ne(SourceItem::getStatus, "discarded"));
            if (children > 0) {
                throw BizException.validation("存在未处理完的子素材，先处理子素材再删除");
            }
        }
        mapper.deleteById(id);
    }

    /**
     * 领域内主题候选（AI 分类建议的边界，5.1/5.2）：T4 起主题清单以 kb_topic 表为准，
     * DomainTopic 常量组退化为种子数据（KbBootstrap 幂等种子）。
     */
    public List<String> topicCandidates(String domainCode) {
        return kbTopicService.listNames(domainCode);
    }

    private SourceItem requireItem(long id) {
        SourceItem item = mapper.selectById(id);
        if (item == null) {
            throw BizException.notFound("素材不存在");
        }
        return item;
    }

    private SourceItem requireEditable(long id) {
        SourceItem item = requireItem(id);
        if ("submitted".equals(item.getStatus())) {
            throw BizException.validation("已入库素材不再编辑：后续修改在标准库内进行（进门即断联）");
        }
        return item;
    }

    private long requireStaffId() {
        Long staffId = AuthContext.staffId();
        if (staffId == null) {
            throw BizException.forbidden();
        }
        return staffId;
    }

    private Map<Long, Long> childCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<SourceItem>()
                .select("parent_id", "COUNT(*) AS cnt")
                .isNotNull("parent_id")
                .groupBy("parent_id"))) {
            Object parentId = row.get("parent_id");
            Object cnt = row.get("cnt");
            if (parentId != null && cnt instanceof Number number) {
                counts.put(Long.valueOf(String.valueOf(parentId)), number.longValue());
            }
        }
        return counts;
    }

    private Map<String, String> domainNames() {
        List<ConstantRegistry.Item> items = constantRegistry.items("domain");
        if (items == null) {
            return Map.of();
        }
        return items.stream().collect(Collectors.toMap(ConstantRegistry.Item::code, ConstantRegistry.Item::name));
    }

    private SourceItemView toView(SourceItem item, Map<Long, Long> childCounts, Map<String, String> domainNames) {
        return new SourceItemView(item.getId(), item.getParentId(), item.getTitle(), item.getContentMd(),
                item.getSummary(), item.getContentType(), item.getDifficulty(), item.getDomainCode(),
                domainNames.getOrDefault(item.getDomainCode(), item.getDomainCode()),
                item.getTopicName(), item.getSourceType(), item.getSourceUrl(), item.getStatus(),
                item.getSubmittedAt(),
                childCounts.getOrDefault(item.getId(), 0L).intValue(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
