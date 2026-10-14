package com.zhiji.common.asset;

import com.zhiji.common.exception.BizException;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片本地化/上传接口（B 端员工会话，/api/admin/assets）。
 *
 * 为什么需要：进标准库时会自动本地化外链图，但可能失败（图床防盗链/超时）。
 * 让这一步在编辑阶段就可**显式执行**并拿到明细报告——人工看到哪些转存失败，
 * 可以删掉该图，或把自己下载到的图上传替换；提交时的自动本地化退化为兜底。
 */
@RestController
@RequestMapping("/api/admin/assets")
@Validated
public class MediaAssetAdminController {

    private final MediaAssetService mediaAssetService;

    public MediaAssetAdminController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    public record LocalizeReq(@NotBlank(message = "正文不能为空") String contentMd) {
    }

    /** 本地化正文里的外链图片，**不落库**，返回改写后正文 + 逐条明细（供人工查看与补救）。 */
    @PostMapping("/localize")
    public MediaAssetService.LocalizeReport localize(@Validated @RequestBody LocalizeReq req) {
        return mediaAssetService.localize(req.contentMd());
    }

    public record UploadResp(String url, String name) {
    }

    /** 人工上传替换：把（人工下载到的）图片收进公共图片库，返回可引用的公开地址。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResp upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BizException.validation("请选择要上传的图片");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String url = mediaAssetService.storeUploaded(file.getBytes(), fileName);
        if (url == null) {
            throw BizException.validation("仅支持图片文件（png / jpg / jpeg / gif / webp / svg）");
        }
        return new UploadResp(url, url.substring(url.lastIndexOf('/') + 1));
    }
}
