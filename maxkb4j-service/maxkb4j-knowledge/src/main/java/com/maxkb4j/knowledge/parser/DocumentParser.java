package com.maxkb4j.knowledge.parser;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析器接口 —— 将不同格式的文件解析为纯文本
 * <p>
 * 知识库入库链路的第一步：用户上传文件后，根据文件扩展名匹配对应的解析器实现，
 * 调用 {@link #handle(InputStream)} 提取文本内容，供后续切片使用。
 * </p>
 *
 * <h3>支持的解析器实现</h3>
 * <ul>
 *   <li>{@code PdfParser} —— .pdf（含扫描件 OCR）</li>
 *   <li>{@code DocParser} —— .doc / .docx</li>
 *   <li>{@code TxtParser} —— .txt</li>
 *   <li>{@code HtmlParser} —— .html / .htm</li>
 *   <li>{@code MDParser} —— .md</li>
 *   <li>{@code ExcelParser} —— .xls / .xlsx</li>
 *   <li>{@code CsvParser} —— .csv</li>
 *   <li>{@code PptParser} —— .ppt / .pptx</li>
 * </ul>
 *
 * <h3>匹配机制</h3>
 * 通过 {@link #support(String)} 按文件扩展名匹配，第一个匹配的解析器处理文件。
 */
public interface DocumentParser {

    /**
     * 获取该解析器支持的文件扩展名列表（小写，含点号，如 ".pdf"）
     *
     * @return 支持的扩展名列表
     */
    List<String> getExtensions();

    /**
     * 检查是否支持解析指定文件
     *
     * @param fileName 文件名（大小写不敏感）
     * @return true=支持解析
     */
    default boolean support(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return getExtensions().stream().anyMatch(lowerName::endsWith);
    }

    /**
     * 解析文件输入流，提取纯文本内容
     *
     * @param inputStream 文件的输入流（调用方负责关闭）
     * @return 提取的纯文本字符串
     */
    String handle(InputStream inputStream);
}
