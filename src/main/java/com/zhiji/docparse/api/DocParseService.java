package com.zhiji.docparse.api;

import java.util.List;

/**
 * 文档解析对外契约（docparse 包）：把 md / txt 变成干净纯文本并还原标题树。
 * 消费方：pipeline（素材解析）与后续 profile（简历解析）。格式拒收口径见决策清单 D31——
 * 后端解析器只做 md / txt，docx / pdf 由管理员前置转换后导入，不引入 POI / PDFBox。
 */
public interface DocParseService {

    /** 支持的扩展名（小写）。 */
    java.util.Set<String> supportedExtensions();

    /**
     * 解析文档。fileName 仅用于按扩展名路由解析器与格式拒收（docx / pdf 抛
     * DOC_FORMAT_UNSUPPORTED，文案给 D31 前置转换指引），不参与内容识别。
     */
    ParsedDoc parse(String fileName, String content);

    /**
     * 解析产物：plainText 保留原文语义（md 不剥代码块与表格，AI 重写输入需要）；
     * headings 还原标题树并记录行号，供章节切分与预览大纲。
     */
    record ParsedDoc(String plainText, List<Heading> headings, int charCount) {
    }

    /** 标题节点：level 1~6（txt 启发式固定为 2），line 为 0 起始行号。 */
    record Heading(int level, String title, int line) {
    }
}
