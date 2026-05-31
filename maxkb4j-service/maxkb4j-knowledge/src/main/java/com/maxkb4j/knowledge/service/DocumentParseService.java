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
 * 知识库入库链路的"解析"环节。Spring 自动注入所有 {@link DocumentParser} 实现类
 * （PdfParser、ExcelParser、DocParser 等），按文件名扩展名匹配第一个支持的解析器，
 * 调用其 handle() 方法提取文本。
 * </p>
 *
 * <h3>匹配机制</h3>
 * <p>
 * 遍历所有 DocumentParser Bean，调用 {@code parser.support(fileName)} 判断是否匹配。
 * 匹配方式为检查文件名（小写）是否以解析器声明的扩展名结尾，例如：
 * </p>
 * <ul>
 *   <li>{@code report.pdf} → PdfParser（扩展名 {@code .pdf}）</li>
 *   <li>{@code data.xlsx} → ExcelParser（扩展名 {@code .xlsx}）</li>
 *   <li>{@code readme.md} → MDParser（扩展名 {@code .md}）</li>
 * </ul>
 * <p>
 * 采用<b>纯粹基于文件扩展名</b>的匹配策略，不读取文件内容魔数。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   String text = documentParseService.extractText("report.pdf", inputStream);
 *   // text 为 PDF 文件提取的纯文本内容
 * </pre>
 *
 * @see DocumentParser
 * @see com.maxkb4j.knowledge.parser.impl.PdfParser
 * @see com.maxkb4j.knowledge.parser.impl.ExcelParser
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParseService implements IDocumentParseService {

    /**
     * Spring 自动注入的所有 DocumentParser 实现类列表
     * <p>
     * 包括 PdfParser、ExcelParser、DocParser、TxtParser、HtmlParser、MDParser、CsvParser、PptParser。
     * 遍历顺序由 Spring Bean 的初始化顺序决定，由于各解析器扩展名互不重叠，顺序不影响结果。
     * </p>
     */
    private final List<DocumentParser> parsers;

    /**
     * 根据文件名自动选择合适的解析器，提取文本内容
     * <p>
     * 遍历逻辑：遍历所有解析器，第一个 {@code support(fileName)} 返回 true 的即被使用。
     * 一旦匹配成功立即返回，不再继续遍历后续解析器。
     * 如果没有任何解析器支持该文件格式，返回空字符串（不会抛异常）。
     * </p>
     *
     * @param fileName    原始文件名（用于扩展名匹配，大小写不敏感）
     * @param inputStream 文件输入流
     * @return 提取的纯文本，未找到匹配解析器时返回空字符串
     */
    public String extractText(String fileName, InputStream inputStream) {
        // 遍历所有解析器，按扩展名匹配
        for (DocumentParser parser : parsers) {
            if (parser.support(fileName)) {
                // 例如 "report.pdf" 会匹配到 PdfParser：
                //   - PdfParser.support() 检查 ".pdf".equals(文件名后缀) → true
                //   - ExcelParser.support() 检查 ".xlsx"/".xls" → false，跳过
                return parser.handle(inputStream);
            }
        }
        // 无匹配的解析器：如 ".epub"、".wps" 等未支持的格式，返回空字符串
        return "";
    }

}
