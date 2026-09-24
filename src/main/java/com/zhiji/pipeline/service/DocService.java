package com.zhiji.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.pipeline.domain.CandidateStatus;
import com.zhiji.pipeline.domain.KpCandidate;
import com.zhiji.pipeline.domain.KpSourceDoc;
import com.zhiji.pipeline.domain.SourceDocStatus;
import com.zhiji.pipeline.repo.KpCandidateMapper;
import com.zhiji.pipeline.repo.KpSourceDocMapper;
import com.zhiji.pipeline.repo.KpSourceDocMapper.BatchSummaryRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材文档管理：导入（多文件 + zip 解包，B端管理台PRD 6.1）、批次聚合视图（6.2）、
 * 形态修正、删除（6.4：有在途候选的先处理完候选才允许删）。
 * 批次是进度与失败重试的单位，batch_id 为查询维度不建实体表（附录 A 23 张表无 batch 表）。
 */
@Service
public class DocService {

    /** 单文件大小上限（5MB）：素材是文本类，超大文件意味着格式误传。 */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KpSourceDocMapper docMapper;
    private final KpCandidateMapper candidateMapper;

    public DocService(KpSourceDocMapper docMapper, KpCandidateMapper candidateMapper) {
        this.docMapper = docMapper;
        this.candidateMapper = candidateMapper;
    }

    public record ImportedDoc(long id, String fileName, String status) {
    }

    public record ImportResult(String batchId, List<ImportedDoc> docs) {
    }

    /** 多文件导入：zip 自动解包（目录结构进 file_name 作来源线索），逐文件落 pending。 */
    @Transactional
    public ImportResult importFiles(List<MultipartFile> files, String sourceRef) {
        if (files == null || files.isEmpty()) {
            throw BizException.validation("请选择要导入的文件");
        }
        String batchId = newBatchId();
        List<ImportedDoc> docs = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) {
                continue;
            }
            name = name.replace('\\', '/');
            if (name.toLowerCase().endsWith(".zip")) {
                docs.addAll(importZip(batchId, name, file, sourceRef));
                continue;
            }
            docs.add(importOne(batchId, name, readContent(name, file), sourceRef));
        }
        if (docs.isEmpty()) {
            throw BizException.validation("没有可导入的文件");
        }
        return new ImportResult(batchId, docs);
    }

    private List<ImportedDoc> importZip(String batchId, String zipName, MultipartFile file, String sourceRef) {
        List<ImportedDoc> docs = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("__MACOSX") || entryName.endsWith("/.DS_Store")) {
                    continue;
                }
                byte[] bytes = zip.readAllBytes();
                if (bytes.length > MAX_FILE_SIZE) {
                    throw BizException.validation("zip 内文件过大（>5MB）：" + entryName);
                }
                docs.add(importOne(batchId, entryName, new String(bytes, StandardCharsets.UTF_8), sourceRef));
            }
        } catch (IOException e) {
            throw BizException.validation("zip 解包失败：" + zipName + "（" + e.getMessage() + "）");
        }
        return docs;
    }

    private ImportedDoc importOne(String batchId, String fileName, String content, String sourceRef) {
        KpSourceDoc doc = new KpSourceDoc();
        doc.setBatchId(batchId);
        doc.setFileName(fileName);
        doc.setSourceRef(sourceRef == null || sourceRef.isBlank() ? null : sourceRef.strip());
        doc.setStatus(SourceDocStatus.PENDING.getCode());
        doc.setRawText(content);
        docMapper.insert(doc);
        return new ImportedDoc(doc.getId(), fileName, doc.getStatus());
    }

    private String readContent(String name, MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw BizException.validation("文件过大（>5MB）：" + name);
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.validation("读取文件失败：" + name);
        }
    }

    /** 批次聚合（B端管理台PRD 6.2 批次面板）：文件数、各状态计数、失败清单。 */
    public List<Map<String, Object>> batches() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BatchSummaryRow row : docMapper.selectBatchSummaries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("batchId", row.getBatchId());
            item.put("fileCount", row.getFileCount());
            item.put("pendingCount", row.getPendingCount());
            item.put("parsedCount", row.getParsedCount());
            item.put("failedCount", row.getFailedCount());
            item.put("splitCount", row.getSplitCount());
            item.put("createdAt", row.getCreatedAt());
            item.put("failures", docMapper.selectFailures(row.getBatchId()).stream()
                    .map(f -> Map.of("id", f.getId(), "fileName", f.getFileName(),
                            "error", f.getParseError() == null ? "" : f.getParseError()))
                    .toList());
            result.add(item);
        }
        return result;
    }

    /** 形态修正（B端管理台PRD 6.3 片段级/文件级标签人工修正）。 */
    @Transactional
    public void changeForm(long docId, String form) {
        KpSourceDoc doc = requireDoc(docId);
        if (!SourceDocStatus.PARSED.getCode().equals(doc.getStatus())
                && !SourceDocStatus.SPLIT.getCode().equals(doc.getStatus())) {
            throw BizException.conflict("DOC_FORM_INVALID", "解析成功后才能修正形态，请先执行解析");
        }
        doc.setForm(form);
        docMapper.updateById(doc);
    }

    /** 来源出处修正：拆题前可补录（D17 出处随管道向下传递）。 */
    @Transactional
    public void updateSourceRef(long docId, String sourceRef) {
        KpSourceDoc doc = requireDoc(docId);
        doc.setSourceRef(sourceRef == null || sourceRef.isBlank() ? null : sourceRef.strip());
        docMapper.updateById(doc);
    }

    /**
     * 删除素材文档（B端管理台PRD 6.4）：连带解析文本一起删；
     * 名下候选条目全部处于 pending（尚未研判）时才允许删，有在途候选的先处理完候选。
     */
    @Transactional
    public void delete(long docId) {
        requireDoc(docId);
        int inFlight = candidateMapper.countInFlight(docId);
        if (inFlight > 0) {
            throw BizException.conflict("DOC_CANDIDATES_IN_FLIGHT",
                    "该文件名下有 " + inFlight + " 条已研判/成稿中的候选条目，先处理完候选再删除");
        }
        LambdaQueryWrapper<KpCandidate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KpCandidate::getDocId, docId);
        candidateMapper.delete(wrapper);
        docMapper.deleteById(docId);
    }

    private KpSourceDoc requireDoc(long docId) {
        KpSourceDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("素材文档不存在：" + docId);
        }
        return doc;
    }

    private String newBatchId() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return "B" + Long.toHexString(System.currentTimeMillis()) + HexFormat.of().formatHex(bytes);
    }
}
