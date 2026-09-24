package com.zhiji.pipeline.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.pipeline.domain.KpSourceDoc;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KpSourceDocMapper extends BaseMapper<KpSourceDoc> {

    /** 批次聚合投影：批次是查询维度非实体，面板按 batch_id 聚合（SQLite 陷阱 E：聚合不进实体）。 */
    @Select("SELECT batch_id, COUNT(*) AS file_count, "
            + "SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) AS pending_count, "
            + "SUM(CASE WHEN status = 'parsed' THEN 1 ELSE 0 END) AS parsed_count, "
            + "SUM(CASE WHEN status = 'parse_failed' THEN 1 ELSE 0 END) AS failed_count, "
            + "SUM(CASE WHEN status = 'split' THEN 1 ELSE 0 END) AS split_count, "
            + "MIN(created_at) AS created_at "
            + "FROM kp_source_doc GROUP BY batch_id ORDER BY created_at DESC")
    List<BatchSummaryRow> selectBatchSummaries();

    /** 指定批次的失败文件清单（批次面板的失败列表）。 */
    @Select("SELECT id, file_name, parse_error FROM kp_source_doc "
            + "WHERE batch_id = #{batchId} AND status = 'parse_failed' ORDER BY id")
    List<FailedFileRow> selectFailures(@Param("batchId") String batchId);

    class BatchSummaryRow {

        private String batchId;
        private Long fileCount;
        private Long pendingCount;
        private Long parsedCount;
        private Long failedCount;
        private Long splitCount;
        private String createdAt;

        public String getBatchId() {
            return batchId;
        }

        public void setBatchId(String batchId) {
            this.batchId = batchId;
        }

        public Long getFileCount() {
            return fileCount;
        }

        public void setFileCount(Long fileCount) {
            this.fileCount = fileCount;
        }

        public Long getPendingCount() {
            return pendingCount;
        }

        public void setPendingCount(Long pendingCount) {
            this.pendingCount = pendingCount;
        }

        public Long getParsedCount() {
            return parsedCount;
        }

        public void setParsedCount(Long parsedCount) {
            this.parsedCount = parsedCount;
        }

        public Long getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(Long failedCount) {
            this.failedCount = failedCount;
        }

        public Long getSplitCount() {
            return splitCount;
        }

        public void setSplitCount(Long splitCount) {
            this.splitCount = splitCount;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }
    }

    class FailedFileRow {

        private Long id;
        private String fileName;
        private String parseError;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getParseError() {
            return parseError;
        }

        public void setParseError(String parseError) {
            this.parseError = parseError;
        }
    }
}
