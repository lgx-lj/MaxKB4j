package com.maxkb4j.knowledge.service;

import com.maxkb4j.knowledge.parser.DocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析服务 —— 根据文件扩展名自动匹配合适的解析器
 * <p>
 * 知识库入库链路的"解析"环节。Spring 自动注入所有 {@link DocumentParser} 实现类，
 * 按文件名扩展名匹配第一个支持的解析器，调用其 handle() 方法提取文本。
 * </p>
 *
 * <p>
 * 使用示例：
 * </p>
 * <pre>
 *   String text = documentParseService.extractText("report.pdf", inputStream);
 *   // text 为 PDF 文件提取的纯文本内容
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParseService implements IDocumentParseService {

    /**
     * Spring 自动注入的所有 DocumentParser 实现类列表
     */
    private final List<DocumentParser> parsers;

    /**
     * 根据文件名自动选择合适的解析器，提取文本内容
     *
     * @param fileName    原始文件名（用于扩展名匹配）
     * @param inputStream 文件输入流
     * @return 提取的纯文本，未找到匹配解析器时返回空字符串
     */
    public String extractText(String fileName, InputStream inputStream) {
        for (DocumentParser parser : parsers) {
            if (parser.support(fileName)) {
                return parser.handle(inputStream);
            }
        }
        return "";
    }

}
