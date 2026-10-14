package com.zhiji.common.asset;

import com.zhiji.common.exception.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 公共图片库（素材/文章正文里的图片资产）：内容寻址存储，文件名 = sha256(字节) + 扩展名。
 *
 * 为什么这么做：
 * 1. 无鉴权可读（{@code GET /api/assets/{name}}）——B 端素材、C 端上线内容、公开分享页
 *    用的是同一份图片地址，不再有「必须带员工会话才能取图」的死角；
 * 2. 内容寻址天然去重——同样的图落一份，多条素材/文章共享同一地址；
 * 3. 图片与素材行解耦——图片是公共资源，不随某条素材删除而消失（素材只存引用）。
 *
 * 落盘位置 {data-dir}/assets/media/{sha256 前 2 位}/{sha256}{ext}：按内容哈希前 2 位分成 256 个桶，
 * 避免单目录文件数膨胀（10 万张时每桶仍仅约 400 张）；而对外地址保持 /api/assets/{sha256}{ext} 不变——
 * 地址由内容决定、不含目录段，存储布局的调整不波及正文引用（内容寻址存储的通行做法）。
 */
@Service
public class MediaAssetService {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);

    private static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 公开文件名白名单：sha256(64 位十六进制) + 扩展名，杜绝路径穿越与任意文件读取。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[0-9a-f]{64}\\.[a-z0-9]{2,5}$");

    private static final String PUBLIC_PREFIX = "/api/assets/";

    private final Path root;

    public MediaAssetService(@Value("${zhiji.data-dir:./data}") String dataDir) {
        this.root = Path.of(dataDir, "assets", "media");
    }

    /** 是否受支持的图片扩展名。 */
    public static boolean isImageName(String name) {
        return name != null && IMAGE_EXTS.contains(extOf(name));
    }

    /** 取小写扩展名（含点）；无扩展名返回空串。 */
    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** 分片目录名：取内容哈希前 2 位十六进制（256 个桶），避免单目录文件数膨胀。 */
    private static String shardOf(String hash) {
        return hash.substring(0, 2);
    }

    /** 正文里图片统一引用的公开地址。 */
    public String publicUrl(String name) {
        return PUBLIC_PREFIX + name;
    }

    /**
     * 存入图片并返回公开文件名。同内容幂等（同名即同图，跳过重复写入）。
     * 扩展名不在白名单内的直接跳过（返回 null）——公共库只收图片资产。
     */
    public String store(byte[] bytes, String sourceName) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String ext = extOf(sourceName);
        if (!IMAGE_EXTS.contains(ext)) {
            return null;
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            log.warn("图片超过 10MB 上限，跳过：{}（{} 字节）", sourceName, bytes.length);
            return null;
        }
        String hash = sha256(bytes);
        String name = hash + ext;
        Path target = root.resolve(shardOf(hash)).resolve(name);
        if (Files.exists(target)) {
            return name;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw BizException.of(500, "ASSET_STORE_FAILED", "图片落盘失败：" + e.getMessage());
        }
        return name;
    }

    /** 读取公开图片（文件名白名单校验，分片目录由哈希推导，无路径穿越面）。 */
    public Path resolve(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw BizException.validation("图片名不合法");
        }
        Path path = root.resolve(shardOf(name)).resolve(name);
        if (!Files.exists(path)) {
            throw BizException.notFound("图片不存在");
        }
        return path;
    }

    /** 公开文件名末段的扩展名（供 Controller 判 Content-Type）。 */
    public static String extOfPublicName(String name) {
        return extOf(name);
    }

    /** 外链图片下载超时（毫秒）：进标准库是同步动作，单图下载不能拖垮整个提交。 */
    private static final int DOWNLOAD_TIMEOUT_MS = 20_000;

    /** 单张外链图片体积上限：防盗链图床偶有巨型原图，超限不入库、保留外链。 */
    private static final long MAX_DOWNLOAD_BYTES = 15L * 1024 * 1024;

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    /**
     * 防盗链图床的 Referer 映射：多数图床校验 Referer，缺失时返回 403。
     * 按「原站域名 → 该站 Referer」发送可显著提升成功率（实测 25% → 71%）；未命中用通用 Referer 兜底。
     */
    private static final java.util.Map<String, String> REFERERS = java.util.Map.ofEntries(
            java.util.Map.entry("cdn.paicoding.com", "https://www.paicoding.com/"),
            java.util.Map.entry("oss.javaguide.cn", "https://javaguide.cn/"),
            java.util.Map.entry("cdn.tobebetterjavaer.com", "https://tobebetterjavaer.com/"),
            java.util.Map.entry("i.loli.net", "https://sm.ms/"),
            java.util.Map.entry("s2.loli.net", "https://sm.ms/"),
            java.util.Map.entry("tva1.sinaimg.cn", "https://weibo.com/"),
            java.util.Map.entry("ws1.sinaimg.cn", "https://weibo.com/"),
            java.util.Map.entry("ws2.sinaimg.cn", "https://weibo.com/"),
            java.util.Map.entry("ws3.sinaimg.cn", "https://weibo.com/"),
            java.util.Map.entry("ws4.sinaimg.cn", "https://weibo.com/"),
            java.util.Map.entry("mmbiz.qpic.cn", "https://mp.weixin.qq.com/"),
            java.util.Map.entry("files.mdnice.com", "https://mdnice.com/"),
            java.util.Map.entry("upload-images.jianshu.io", "https://www.jianshu.com/"),
            java.util.Map.entry("uploadfiles.nowcoder.com", "https://www.nowcoder.com/"),
            java.util.Map.entry("img-blog.csdnimg.cn", "https://blog.csdn.net/"),
            java.util.Map.entry("static001.geekbang.org", "https://time.geekbang.org/"),
            java.util.Map.entry("cdn-doocs.oss-cn-shenzhen.aliyuncs.com", "https://github.com/"),
            java.util.Map.entry("p0.meituan.net", "https://tech.meituan.com/"));

    /**
     * 把正文里的外链图片引用本地化：能下载到的收进公共图片库并换成 /api/assets/{sha256}.ext，
     * 下载失败的保留原外链（不丢图、不静默删）。已是公共地址或 data: 的一律不动。
     *
     * 这是「进标准库」时的最后一道收口——素材暂存阶段允许挂外链（第三方图床随时可能失效），
     * 一旦入门即成资产，图片必须落回本系统，C 端与分享页才能稳定取图。
     */
    public String localizeExternalImages(String contentMd) {
        return localize(contentMd).contentMd();
    }

    /** 单条外链本地化结果：原文地址 + 结果（成功给本地名，失败给原因）。 */
    public record ImageResult(String url, boolean ok, String localName, String reason) {
    }

    /** 本地化报告：改写后的正文 + 逐条处理明细（供人工查看与补救）+ 成功/失败计数。 */
    public record LocalizeReport(String contentMd, List<ImageResult> images, int success, int failed) {
    }

    /**
     * 本地化并**返回明细报告**（不落库）：供人工在编辑页看到每张外链图转存成功或失败。
     * 失败项交人工处理——删掉该图、或把人工下载到的图上传替换（{@link #storeUploaded}）。
     */
    public LocalizeReport localize(String contentMd) {
        if (contentMd == null || contentMd.isBlank() || contentMd.indexOf("http") < 0) {
            return new LocalizeReport(contentMd, List.of(), 0, 0);
        }
        List<ImageResult> results = new java.util.ArrayList<>();
        String afterMd = MarkdownImages.replace(MarkdownImages.MD, contentMd, m -> {
            ImageResult r = localizeOne(m.group(2));
            results.add(r);
            return r.ok() ? "![" + m.group(1) + "](" + publicUrl(r.localName()) + ")" : null;
        });
        String finalMd = MarkdownImages.replace(MarkdownImages.HTML_SRC, afterMd, m -> {
            ImageResult r = localizeOne(m.group(2));
            results.add(r);
            return r.ok() ? m.group(1) + publicUrl(r.localName()) + m.group(3) : null;
        });
        int success = (int) results.stream().filter(ImageResult::ok).count();
        return new LocalizeReport(finalMd, results, success, results.size() - success);
    }

    /** 单个引用本地化：返回结果明细（失败保留原文由调用方处理）。 */
    private ImageResult localizeOne(String rawRef) {
        String ref = MarkdownImages.firstToken(rawRef);
        if (ref == null || ref.isBlank()) {
            return new ImageResult(ref == null ? "" : ref, false, null, "空引用");
        }
        String lower = ref.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // 非外链（本地相对路径 / 已是公共地址 / data:）：不算失败，原样保留、不进报告
            return new ImageResult(ref, false, null, "非外链，保持原样");
        }
        byte[] bytes;
        try {
            bytes = download(ref);
        } catch (Exception e) {
            log.warn("外链图片下载失败，保留原地址：{} - {}", ref, e.getMessage());
            return new ImageResult(ref, false, null, e.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            return new ImageResult(ref, false, null, "下载内容为空");
        }
        String name = store(bytes, nameGuess(ref));
        if (name == null) {
            // 扩展名不在白名单（如无扩展名的动态图）→ 按响应内容推断后缀再存一次
            String ext = sniffExt(bytes);
            if (ext == null) {
                log.warn("外链图片格式无法识别，保留原地址：{}", ref);
                return new ImageResult(ref, false, null, "图片格式无法识别");
            }
            name = store(bytes, "img" + ext);
        }
        return name == null
                ? new ImageResult(ref, false, null, "入库失败")
                : new ImageResult(ref, true, name, "");
    }

    /** 人工上传替换：把人工下载到的图片收进公共图片库，返回可引用的公开地址。 */
    public String storeUploaded(byte[] bytes, String fileName) {
        String expected = extOf(fileName);
        if (IMAGE_EXTS.contains(expected)) {
            String name = store(bytes, fileName);
            return name == null ? null : publicUrl(name);
        }
        // 文件名后缀不在白名单（或无后缀）：按文件头嗅探
        String ext = sniffExt(bytes);
        if (ext == null) {
            return null;
        }
        String name = store(bytes, "img" + ext);
        return name == null ? null : publicUrl(name);
    }

    /** 下载外链图片（带 Referer 破解防盗链）；非 2xx 或非图片一律放弃。 */
    private byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
        conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Referer", refererFor(url));
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            String contentType = conn.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("非图片响应：" + contentType);
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = in.readNBytes((int) MAX_DOWNLOAD_BYTES + 1);
                if (bytes.length > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("图片超过体积上限");
                }
                return bytes;
            }
        } finally {
            conn.disconnect();
        }
    }

    private String refererFor(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host != null && REFERERS.containsKey(host)) {
                return REFERERS.get(host);
            }
        } catch (RuntimeException ignored) {
            // URL 不规范：走通用 Referer
        }
        return "https://www.google.com/";
    }

    /** 从 URL 猜图片文件名（仅用于取扩展名，实际文件名由内容哈希决定）。 */
    private String nameGuess(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String base = slash < 0 ? path : path.substring(slash + 1);
        return base.isBlank() ? "img" : base;
    }

    /** 按文件头字节嗅探图片类型，返回扩展名（无扩展名 URL 的兜底）；识别不出返回 null。 */
    private String sniffExt(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return ".jpg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return ".gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        String head = new String(bytes, 0, Math.min(256, bytes.length), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))) {
            return ".svg";
        }
        return null;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 实现", e);
        }
    }
}
