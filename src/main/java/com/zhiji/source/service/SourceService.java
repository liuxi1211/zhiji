package com.zhiji.source.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.constant.ConstantRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.source.config.SourceProperties;
import com.zhiji.source.domain.SourceItem;
import com.zhiji.source.repo.SourceItemMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * 轻量整理（编辑/AI 改写由 SourceAiService 产出后回写）、拆分、状态推进。
 *
 * 图片资产「落盘即真相」（暂缓清单 1.6 已销账口径）：导入压缩包内的图片按引用路径落盘
 * data/assets/source/{itemId}/{file}，未被正文引用的不落盘；删除素材连带清理图片目录。
 */
@Service
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final Pattern IMAGE_REF = Pattern.compile("asset:source/(\\d+)/([A-Za-z0-9._-]+)");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");
    private static final List<String> IMAGE_EXTS = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");

    private final SourceItemMapper mapper;
    private final ConstantRegistry constantRegistry;
    private final KbTopicService kbTopicService;
    private final KbContentService kbContentService;
    private final Path assetRoot;

    public SourceService(SourceItemMapper mapper, ConstantRegistry constantRegistry,
            KbTopicService kbTopicService, KbContentService kbContentService, SourceProperties properties) {
        this.mapper = mapper;
        this.constantRegistry = constantRegistry;
        this.kbTopicService = kbTopicService;
        this.kbContentService = kbContentService;
        this.assetRoot = Path.of(properties.getDataDir(), "assets");
    }

    public record SourceItemView(long id, Long parentId, String title, String contentMd, String summary,
            String difficulty, String domainCode, String domainName, String topicName,
            String sourceType, String sourceUrl, String status, String submittedAt,
            int childCount, String createdAt, String updatedAt) {
    }

    public record SplitSliceResp(long id, String title) {
    }

    public record SplitResp(List<SplitSliceResp> slices) {
    }

    public List<SourceItemView> list(String status, Long parentId) {
        LambdaQueryWrapper<SourceItem> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(SourceItem::getStatus, status);
        }
        if (parentId != null) {
            wrapper.eq(SourceItem::getParentId, parentId);
        } else {
            // 缺省只列母素材：拆分出的子素材经母素材详情或筛选查看
            wrapper.isNull(SourceItem::getParentId);
        }
        wrapper.orderByDesc(SourceItem::getUpdatedAt);
        Map<Long, Long> childCounts = childCounts();
        Map<String, String> domainNames = domainNames();
        return mapper.selectList(wrapper).stream().map(item -> toView(item, childCounts, domainNames)).toList();
    }

    public List<SourceItemView> children(long parentId) {
        SourceItem parent = requireItem(parentId);
        Map<Long, Long> childCounts = childCounts();
        Map<String, String> domainNames = domainNames();
        return mapper.selectList(new LambdaQueryWrapper<SourceItem>()
                        .eq(SourceItem::getParentId, parent.getId())
                        .orderByAsc(SourceItem::getId))
                .stream()
                .map(item -> toView(item, childCounts, domainNames))
                .toList();
    }

    public SourceItemView get(long id) {
        return toView(requireItem(id), childCounts(), domainNames());
    }

    public SourceItemView create(String title, String contentMd, String sourceUrl, String sourceType) {
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
        item.setSourceType(type);
        item.setSourceUrl((sourceUrl == null || sourceUrl.isBlank()) ? null : sourceUrl.strip());
        item.setStatus("pending");
        item.setCreatedBy(requireStaffId());
        mapper.insert(item);
        return toView(item, Map.of(), domainNames());
    }

    /**
     * 导入 txt / md 单文件或含图片资源的 zip 压缩包。
     * zip 内图片按正文引用落盘（落盘即真相），未引用的图片不落盘——
     * 先导文本建条目，再按条目 id 建图片目录，最后把命中引用的图片写入并回填路径。
     */
    public SourceItemView importFile(MultipartFile file) {
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
                String title = name.substring(0, name.lastIndexOf('.'));
                return create(title, text, null, "import");
            }
            throw BizException.validation("仅支持 txt / md 文本与 zip 压缩包（含打包图片资源）");
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.of(500, "SOURCE_IMPORT_FAILED", "文件读取失败：" + e.getMessage());
        }
    }

    private SourceItemView importZip(String name, InputStream in) throws IOException {
        byte[] all = in.readAllBytes();
        if (all.length > MAX_ZIP_BYTES) {
            throw BizException.validation("压缩包超过 50MB 上限");
        }
        Map<String, byte[]> images = new LinkedHashMap<>();
        StringBuilder textBuilder = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(all))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = Path.of(entry.getName()).getFileName().toString();
                String entryLower = entryName.toLowerCase(Locale.ROOT);
                byte[] bytes = zip.readAllBytes();
                if (IMAGE_EXTS.stream().anyMatch(entryLower::endsWith)) {
                    if (bytes.length <= MAX_IMAGE_BYTES) {
                        images.put(entryName, bytes);
                    }
                } else if (entryLower.endsWith(".txt") || entryLower.endsWith(".md")
                        || entryLower.endsWith(".markdown")) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n\n");
                    }
                    textBuilder.append(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }
        String text = textBuilder.toString().strip();
        if (text.isEmpty() && images.isEmpty()) {
            throw BizException.validation("压缩包内没有可导入的文本或图片");
        }
        String title = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        SourceItem item = new SourceItem();
        item.setTitle(title);
        item.setContentMd(text);
        item.setSourceType("import");
        item.setStatus("pending");
        item.setCreatedBy(requireStaffId());
        mapper.insert(item);
        if (!images.isEmpty()) {
            String rendered = materializeImages(item.getId(), text, images);
            item.setContentMd(rendered);
            mapper.updateById(item);
        }
        return toView(item, Map.of(), domainNames());
    }

    /**
     * 图片落盘即真相：只写正文引用到的图片（MD 图片语法或 asset: 标记），
     * 引用统一规范化为 asset:source/{itemId}/{file}，前端用 /api/admin/sources/{id}/assets/{file} 取图。
     */
    private String materializeImages(long itemId, String contentMd, Map<String, byte[]> images) {
        if (contentMd == null || contentMd.isBlank()) {
            return contentMd;
        }
        Path dir = assetRoot.resolve("source").resolve(String.valueOf(itemId));
        Map<String, String> matched = new HashMap<>();
        Matcher matcher = MD_IMAGE.matcher(contentMd);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String ref = matcher.group(1);
            String fileName = Path.of(ref.strip()).getFileName().toString();
            if (images.containsKey(fileName)) {
                String token = "asset:source/" + itemId + "/" + fileName;
                matched.put(fileName, token);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("![](" + token + ")"));
            }
        }
        matcher.appendTail(sb);
        if (matched.isEmpty()) {
            return contentMd;
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : matched.entrySet()) {
                try (InputStream img = new java.io.ByteArrayInputStream(images.get(e.getKey()))) {
                    Files.copy(img, dir.resolve(e.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw BizException.of(500, "SOURCE_ASSET_FAILED", "图片落盘失败：" + e.getMessage());
        }
        log.info("素材 {} 落盘图片 {} 张", itemId, matched.size());
        return sb.toString();
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
        SourceItemView created = create(title, markdown, trimmed, "url");
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

    public SourceItemView update(long id, String title, String contentMd, String summary, String difficulty,
            String domainCode, String topicName, String sourceUrl) {
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

    /** 拆分请求项：title 必填；contentMd 为空时按母素材标题锚点切段回填。 */
    public record SplitSliceInput(String title, String contentMd) {
    }

    /** 拆分：1 拆多，产出子素材并挂到母素材下；母素材转 discarded 保留原文。 */
    public SplitResp split(long id, List<SplitSliceInput> slices) {
        SourceItem parent = requireEditable(id);
        if (slices == null || slices.size() < 2) {
            throw BizException.validation("拆分至少要产出两条子素材");
        }
        List<SplitSliceResp> created = new ArrayList<>();
        List<String> segments = segmentsOf(parent.getContentMd());
        for (int i = 0; i < slices.size(); i++) {
            SplitSliceInput slice = slices.get(i);
            if (slice.title() == null || slice.title().isBlank()) {
                throw BizException.validation("第 " + (i + 1) + " 条子素材标题不能为空");
            }
            String content = slice.contentMd();
            if (content == null || content.isBlank()) {
                content = i < segments.size() ? segments.get(i) : "";
            }
            SourceItem child = new SourceItem();
            child.setParentId(parent.getId());
            child.setTitle(slice.title().strip());
            child.setContentMd(content);
            child.setSourceType("manual");
            child.setSourceUrl(parent.getSourceUrl());
            child.setStatus("pending");
            child.setCreatedBy(requireStaffId());
            mapper.insert(child);
            created.add(new SplitSliceResp(child.getId(), child.getTitle()));
        }
        parent.setStatus("discarded");
        parent.setUpdatedAt(now());
        mapper.updateById(parent);
        return new SplitResp(created);
    }

    /** AI 一键拆分预填的标题锚点：按 Markdown 标题切正文，供逐条预览调整。 */
    public List<String> splitPreview(long id) {
        return segmentsOf(requireItem(id).getContentMd());
    }

    /**
     * 提交入库（核心模块定义 5.1 → 5.2 的衔接）：概述与难度齐备才可提交（两者都是入库门槛）；
     * 每条素材在标准库创建 pending 内容并进审核队列（不校不上线），素材标 submitted 长期保留；
     * 子素材成组提交到同一主题、同一定类。标准库内容与素材进门即断联（红线 7）。
     */
    public List<SourceItemView> submit(long id, String domainCode, Long topicId, String contentType) {
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
        List<SourceItem> targets = new ArrayList<>();
        targets.add(item);
        if (item.getParentId() == null) {
            targets.addAll(mapper.selectList(new LambdaQueryWrapper<SourceItem>()
                    .eq(SourceItem::getParentId, item.getId())
                    .eq(SourceItem::getStatus, "pending")));
        }
        String stamp = now();
        for (SourceItem target : targets) {
            // 每条素材在标准库成稿：pending + 审核留痕（kb 侧自行校验主题与领域匹配）
            kbContentService.createPending(contentType, domainCode, topicId, target.getTitle(),
                    target.getSummary(), target.getContentMd(), target.getDifficulty(),
                    "doc".equals(contentType) ? target.getSourceUrl() : null, "wizard", null);
            target.setStatus("submitted");
            target.setDomainCode(domainCode);
            target.setTopicName(topic.getName());
            target.setSubmittedAt(stamp);
            target.setUpdatedAt(stamp);
            mapper.updateById(target);
        }
        return targets.stream().map(t -> toView(t, childCounts(), domainNames())).toList();
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

    /** 删除：仅限未入库素材；连带清理图片目录（删除素材连带清理其图片，5.1 边界）。 */
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
        deleteAssetDir(id);
    }

    /** 素材图片资产读取（仅 B 端会话可达；文件名由白名单正则约束，无路径穿越面）。 */
    public Path assetPath(long itemId, String fileName) {
        if (!fileName.matches("[A-Za-z0-9._-]+")) {
            throw BizException.validation("文件名不合法");
        }
        Path path = assetRoot.resolve("source").resolve(String.valueOf(itemId)).resolve(fileName);
        if (!Files.exists(path)) {
            throw BizException.notFound("图片不存在");
        }
        return path;
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

    /** 按标题锚点切正文：一级/二级标题作为切分线，返回与锚点对应的正文段。 */
    private List<String> segmentsOf(String contentMd) {
        List<String> segments = new ArrayList<>();
        if (contentMd == null || contentMd.isBlank()) {
            return segments;
        }
        Pattern heading = Pattern.compile("(?m)^#{1,2}\\s+(.+)$");
        Matcher matcher = heading.matcher(contentMd);
        List<String> anchors = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        while (matcher.find()) {
            anchors.add(matcher.group(1).strip());
            positions.add(matcher.start());
        }
        if (anchors.isEmpty()) {
            segments.add(contentMd.strip());
            return segments;
        }
        for (int i = 0; i < anchors.size(); i++) {
            int start = positions.get(i);
            int end = i + 1 < positions.size() ? positions.get(i + 1) : contentMd.length();
            segments.add(contentMd.substring(start, end).strip());
        }
        return segments;
    }

    private Map<Long, Long> childCounts() {
        return mapper.selectList(new LambdaQueryWrapper<SourceItem>().isNotNull(SourceItem::getParentId))
                .stream()
                .collect(Collectors.groupingBy(SourceItem::getParentId, Collectors.counting()));
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
                item.getSummary(), item.getDifficulty(), item.getDomainCode(),
                domainNames.getOrDefault(item.getDomainCode(), item.getDomainCode()),
                item.getTopicName(), item.getSourceType(), item.getSourceUrl(), item.getStatus(),
                item.getSubmittedAt(),
                childCounts.getOrDefault(item.getId(), 0L).intValue(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    private void deleteAssetDir(long itemId) {
        Path dir = assetRoot.resolve("source").resolve(String.valueOf(itemId));
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("清理素材图片失败：{} - {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("清理素材图片目录失败：{} - {}", dir, e.getMessage());
        }
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
