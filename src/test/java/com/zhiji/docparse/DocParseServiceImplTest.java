package com.zhiji.docparse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.common.exception.BizException;
import com.zhiji.docparse.api.DocParseService.ParsedDoc;
import com.zhiji.docparse.service.DocParseServiceImpl;
import org.junit.jupiter.api.Test;

/**
 * 文档解析纯逻辑单测：md 标题树还原与行号、导航噪音剔除、txt 启发式标题、
 * D31 格式拒收（docx / pdf 报前置转换指引）、空文件拒绝。
 */
class DocParseServiceImplTest {

    private final DocParseServiceImpl parser = new DocParseServiceImpl();

    @Test
    void markdownRestoresHeadingTreeWithLineNumbers() {
        String md = "# 一级标题\n\n正文段落\n\n## 二级标题\n\n更多正文\n```java\ncode ## not heading\n```\n### 三级标题\n";
        ParsedDoc doc = parser.parse("a.md", md);

        assertThat(doc.headings()).hasSize(3);
        assertThat(doc.headings().get(0).level()).isEqualTo(1);
        assertThat(doc.headings().get(0).title()).isEqualTo("一级标题");
        assertThat(doc.headings().get(1).level()).isEqualTo(2);
        assertThat(doc.headings().get(1).title()).isEqualTo("二级标题");
        assertThat(doc.headings().get(2).level()).isEqualTo(3);
        // 代码围栏内的 ## 行不得识别为标题
        assertThat(doc.headings()).noneMatch(h -> h.title().contains("not heading"));
        // 正文保留原文（代码块语义不剥）
        assertThat(doc.plainText()).contains("code ## not heading");
        assertThat(doc.charCount()).isPositive();
    }

    @Test
    void markdownStripsNavigationNoise() {
        String md = "[目录](#a)\n[首页](#b)\n[上一章](#c)\n\n[toc]\n\n# 正文标题\n\n正文内容\n";
        ParsedDoc doc = parser.parse("nav.md", md);

        assertThat(doc.plainText()).doesNotContain("[目录]");
        assertThat(doc.plainText()).doesNotContain("[toc]");
        assertThat(doc.plainText()).contains("# 正文标题");
    }

    @Test
    void plainTextDetectsNumberedHeadings() {
        String txt = "概述\n\n1. Java基础\n\n正文内容，讲基础概念。\n\n第2章 集合框架\n\n更多内容\n";
        ParsedDoc doc = parser.parse("notes.txt", txt);

        assertThat(doc.headings()).hasSize(2);
        assertThat(doc.headings().get(0).title()).isEqualTo("1. Java基础");
        assertThat(doc.headings().get(1).title()).isEqualTo("第2章 集合框架");
        assertThat(doc.plainText()).contains("正文内容，讲基础概念。");
    }

    @Test
    void unsupportedFormatsRejectedWithD31Guidance() {
        assertThatThrownBy(() -> parser.parse("a.docx", "content"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("DOC_FORMAT_UNSUPPORTED");
        assertThatThrownBy(() -> parser.parse("b.pdf", "content"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getMessage())
                .asString().contains("pandoc").contains("marker");
        assertThatThrownBy(() -> parser.parse("c.exe", "content"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void emptyContentRejected() {
        assertThatThrownBy(() -> parser.parse("a.md", "   \n  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("DOC_EMPTY");
    }
}
