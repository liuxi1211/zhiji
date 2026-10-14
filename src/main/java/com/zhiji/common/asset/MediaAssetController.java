package com.zhiji.common.asset;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共图片库读取接口（{@code GET /api/assets/{name}}）：无需鉴权，任何人可查询。
 *
 * 为什么公开：图片是素材/文章正文里的引用资源，B 端编辑预览、C 端上线内容、分享页
 * 都指向同一地址；若挂鉴权，则分享页与 C 端都必须带会话才看得见图，链路会被卡死。
 * 安全性由「内容寻址 + 文件名白名单」保证：只能取到 sha256 命名的图片，无目录遍历面。
 */
@RestController
@RequestMapping("/api/assets")
public class MediaAssetController {

    private final MediaAssetService mediaAssetService;

    public MediaAssetController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @GetMapping("/{name}")
    public ResponseEntity<FileSystemResource> read(@PathVariable String name) {
        var path = mediaAssetService.resolve(name);
        MediaType mediaType = switch (MediaAssetService.extOfPublicName(name)) {
            case ".png" -> MediaType.IMAGE_PNG;
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG;
            case ".gif" -> MediaType.IMAGE_GIF;
            case ".svg" -> MediaType.valueOf("image/svg+xml");
            case ".webp" -> MediaType.valueOf("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(path));
    }
}
