package com.maxkb4j.knowledge.service;

import com.maxkb4j.core.util.SentenceSplitter;
import com.maxkb4j.core.util.TextSplitter;
import com.maxkb4j.knowledge.dto.ParagraphSimple;
import org.jetbrains.annotations.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档切片服务 —— 将解析后的文本按策略拆分为段落（切片）
 * <p>
 * 这是知识库入库链路中"切片"环节的核心实现。负责将 {@link DocumentParser} 解析出的
 * 长文本按 Markdown 标题层级、句子边界等规则拆分为多个 ParagraphSimple，每个切片
 * 最终对应 paragraph 表中的一条记录，是 RAG 检索的最小数据单元。
 * </p>
 *
 * <h3>两种切片策略</h3>
 * <ul>
 *   <li><b>智能模式（smartSplit）</b>：默认策略，自动检测 Markdown 标题层级切分，
 *       超长段落（>512字符）按句子切分，保留表格完整性</li>
 *   <li><b>自定义模式（recursive）</b>：用户指定分隔符列表（如 #, ##, ### 等），
 *       按指定模式递归切分</li>
 * </ul>
 *
 * <h3>表格保护</h3>
 * 超长段落切分时，通过 {@link #splitContentPreserveTable} 方法识别表格行
 * （以 | 开头的行），保证表格内容不会被从中间拆开。
 */
@Component
public class DocumentSplitService implements IDocumentSplitService {

    // 预编译正则表达式以提高性能
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" +");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{2,}");
    // 统一移除 Markdown 标题前缀（支持 1~6 个 # 后跟空格）
    private static final Pattern MARKDOWN_HEADER = Pattern.compile("#{1,6} ");

    // 统一标题正则：一次匹配所有 h1~h6 标题，替代原来 6 次扫描
    private static final Pattern UNIFIED_HEADING_PATTERN = Pattern.compile("(?m)^((#{1,6})\\s+(.+))$");

    private static final String[] DEFAULT_PATTERNS = {
            "(?<=^)# .*|(?<=\\n)# .*",
            "(?<=\\n)(?<!#)## (?!#).*|(?<=^)(?<!#)## (?!#).*",
            "(?<=\\n)(?<!#)### (?!#).*|(?<=^)(?<!#)### (?!#).*",
            "(?<=\\n)(?<!#)#### (?!#).*|(?<=^)(?<!#)#### (?!#).*",
            "(?<=\\n)(?<!#)##### (?!#).*|(?<=^)(?<!#)##### (?!#).*",
            "(?<=\\n)(?<!#)###### (?!#).*|(?<=^)(?<!#)###### (?!#).*"
    };

    // 预编译 DEFAULT_PATTERNS，避免 recursive 中循环内重复编译
    private static final Pattern[] COMPILED_DEFAULT_PATTERNS;
    static {
        COMPILED_DEFAULT_PATTERNS = new Pattern[DEFAULT_PATTERNS.length];
        for (int i = 0; i < DEFAULT_PATTERNS.length; i++) {
            COMPILED_DEFAULT_PATTERNS[i] = Pattern.compile(DEFAULT_PATTERNS[i]);
        }
    }

    private static final int DEFAULT_LIMIT = 512;

    /**
     * 切片主入口
     * <p>
     * 根据是否指定自定义分割模式，分派到不同的处理策略：
     * </p>
     * <ul>
     *   <li>指定了 patterns → 走 {@link #recursive(String, String[], int, Boolean)} 自定义递归分割</li>
     *   <li>未指定 patterns → 走 {@link #smartSplit(String)} 智能分割</li>
     * </ul>
     *
     * @param docText    待切分的原始文本（已由解析器从文件中提取）
     * @param patterns   自定义分隔模式数组，为 null 或空时走智能模式
     * @param limit      段落最大长度限制（字符数），超长段落会被进一步切分
     * @param withFilter 是否对结果进行清洗（去除多余空格、空行、Markdown 标题符号）
     * @return 切片后的段落列表，每个段落包含 title（继承的标题）和 content（文本片段）
     */
    public List<ParagraphSimple> split(String docText, String[] patterns, Integer limit, Boolean withFilter) {
        if (patterns != null && patterns.length > 0) {
            return recursive(docText, patterns, limit, withFilter);
        } else {
            return smartSplit(docText);
        }
    }

    /**
     * 智能切片（默认策略）
     * <p>
     * 分三个阶段处理：
     * </p>
     * <ol>
     *   <li><b>标题切分</b>：检测 Markdown 标题（# ~ ######），按标题层级拆分</li>
     *   <li><b>超长切分</b>：超过 512 字符的段落用 splitContentPreserveTable 切分（保护表格）</li>
     *   <li><b>清洗过滤</b>：去除多余空格/空行/Markdown 标题符号后输出</li>
     * </ol>
     *
     * @param text 待切分文本
     * @return 切片后的段落列表
     */
    public List<ParagraphSimple> smartSplit(String text) {
        List<ParagraphSimple> result = new ArrayList<>();

        // 阶段1：按标题切分（跳过6次正则扫描，无标题时直接作为整体）
        boolean hasHeadings = text.startsWith("# ") || text.contains("\n# ");
        List<ParagraphSimple> parts;
        if (!hasHeadings) {
            // 无标题：跳过正则扫描，整体作为一段
            parts = Collections.singletonList(ParagraphSimple.builder().title("").content(text).build());
        } else {
            // 有标题：单次正则扫描切分（替代原来 6 次扫描）
            parts = splitByHeadings(text);
        }

        // 阶段2：超长段落走 splitContentPreserveTable（与原 recursive 行为一致）
        List<ParagraphSimple> splitParts = new ArrayList<>();
        for (ParagraphSimple part : parts) {
            if (StringUtils.isNotBlank(part.getContent())) {
                if (part.getContent().length() <= DEFAULT_LIMIT) {
                    splitParts.add(part);
                } else {
                    splitParts.addAll(splitContentPreserveTable(part, DEFAULT_LIMIT));
                }
            }
        }

        // 阶段3：cleanAndFilter + lineSplit（与原 smartSplit 行为一致）
        for (ParagraphSimple part : splitParts) {
            if (StringUtils.isNotBlank(part.getContent())) {
                String cleaned = cleanAndFilter(part.getContent());
                List<String> lines = lineSplit(cleaned, DEFAULT_LIMIT);
                for (String line : lines) {
                    if (StringUtils.isNotBlank(line)) {
                        result.add(ParagraphSimple.builder().title(part.getTitle()).content(line).build());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 单次正则扫描按标题层级切分，替代原来 6 次 pattern 循环。
     * 通过 heading stack 维护标题层级关系，输出与原 recursive(DEFAULT_PATTERNS) 等价。
     */
    private List<ParagraphSimple> splitByHeadings(String text) {
        List<ParagraphSimple> result = new ArrayList<>();
        // headingStack[0..5] 对应 h1~h6 的标题文本
        String[] headingStack = new String[6];

        Matcher matcher = UNIFIED_HEADING_PATTERN.matcher(text);
        int lastEnd = 0;
        String currentTitle = "";

        while (matcher.find()) {
            // 本标题之前的内容
            String contentBefore = text.substring(lastEnd, matcher.start()).trim();
            if (!contentBefore.isEmpty()) {
                result.add(ParagraphSimple.builder().title(currentTitle).content(contentBefore).build());
            }

            // 更新标题层级栈
            int level = matcher.group(2).length(); // # 的个数
            String headingText = matcher.group(3).trim();
            headingStack[level - 1] = headingText;
            // 低层级标题清空
            for (int i = level; i < 6; i++) {
                headingStack[i] = null;
            }
            currentTitle = buildTitleFromStack(headingStack);

            lastEnd = matcher.end();
        }

        // 最后一段内容
        String endContent = text.substring(lastEnd).trim();
        if (!endContent.isEmpty()) {
            result.add(ParagraphSimple.builder().title(currentTitle).content(endContent).build());
        }

        return result;
    }

    private static String buildTitleFromStack(String[] headingStack) {
        // 格式与原 recursive 一致："" + " " + heading → " Introduction Background"
        StringBuilder sb = new StringBuilder();
        for (String h : headingStack) {
            if (h != null) {
                sb.append(" ").append(h);
            }
        }
        return sb.toString();
    }

    public static List<String> lineSplit(String text, int limit) {
        String[] texts = text.split("\n");
        return TextSplitter.mergeChunksIntoParts(Arrays.asList(texts), limit, "\n");
    }

    /**
     * 清理字符串中的多余空格和空行，并移除 Markdown 标题符号
     */
    public static String cleanAndFilter(String input) {
        if (StringUtils.isEmpty(input)) {
            return "";
        }
        String result = MULTIPLE_SPACES.matcher(input).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n");
        result = MARKDOWN_HEADER.matcher(result).replaceAll("");
        return result.trim();
    }

    private static String cleanTitle(String input) {
        String result = MARKDOWN_HEADER.matcher(input).replaceAll("");
        return result.trim();
    }

    /**
     * 自定义递归切片
     * <p>
     * 按用户指定的分隔模式列表，从粗到细逐层切分。例如传入 ["#", "##", "###"] 时，
     * 先用 # 切分，再用 ## 对每个切片继续切分，最后用 ### 切分。
     * 所有 pattern 遍历完成后，对超长段落（>limit）按句子 Split 进一步切分。
     * </p>
     *
     * @param docText    原始文本
     * @param patterns   用户指定的分隔正则模式数组
     * @param limit      段落最大长度
     * @param withFilter 是否清洗结果
     * @return 切片后的段落列表
     */
    public List<ParagraphSimple> recursive(String docText, String[] patterns, int limit, Boolean withFilter) {
        if (docText == null || docText.isEmpty()) {
            return Collections.emptyList();
        }

        // 使用预编译 pattern（DEFAULT_PATTERNS 用缓存，自定义 patterns 按需编译）
        Pattern[] compiledPatterns;
        if (patterns == DEFAULT_PATTERNS) {
            compiledPatterns = COMPILED_DEFAULT_PATTERNS;
        } else {
            compiledPatterns = new Pattern[patterns.length];
            for (int i = 0; i < patterns.length; i++) {
                if (patterns[i] != null && !patterns[i].isEmpty()) {
                    compiledPatterns[i] = Pattern.compile(patterns[i]);
                }
            }
        }

        // 初始只有一个完整文本
        List<ParagraphSimple> parts = Collections.singletonList(ParagraphSimple.builder().title("").content(docText).build());
        // 按照标题层级循环切分
        for (Pattern pattern : compiledPatterns) {
            if (pattern == null) continue;
            List<ParagraphSimple> titleParts = new ArrayList<>();
            for (ParagraphSimple part : parts) {
                Matcher matcher = pattern.matcher(part.getContent());
                int lastEnd = 0;
                String lastTitle = part.getTitle();
                while (matcher.find()) {
                    String lastContent = part.getContent().substring(lastEnd, matcher.start()).trim();
                    titleParts.add(ParagraphSimple.builder().title(lastTitle).content(lastContent).build());
                    lastTitle = part.getTitle() + " " + cleanTitle(matcher.group());
                    lastEnd = matcher.end();
                }
                String endContent = part.getContent().substring(lastEnd).trim();
                if (!endContent.isEmpty()) {
                    titleParts.add(ParagraphSimple.builder().title(lastTitle).content(endContent).build());
                }
            }
            parts = titleParts;
        }
        // 所有 pattern 分割完成后，处理超长片段
        List<ParagraphSimple> result = new ArrayList<>();
        for (ParagraphSimple part : parts) {
            if (StringUtils.isNotBlank(part.getContent())) {
                // 内容已不超过 limit，无需再次切分
                if (part.getContent().length() <= limit) {
                    result.add(part);
                } else {
                    List<ParagraphSimple> splitParts = splitContentPreserveTable(part, limit);
                    result.addAll(splitParts);
                }
            }
        }
        if (Boolean.TRUE.equals(withFilter)) {
            return result.stream()
                    .filter(e -> StringUtils.isNotBlank(e.getContent()))
                    .peek(e -> e.setContent(cleanAndFilter(e.getContent())))
                    .toList();
        }
        return result.stream()
                .filter(e -> StringUtils.isNotBlank(e.getContent()))
                .toList();
    }

    /**
     * 超长段落切分（保护表格完整性）
     * <p>
     * 对于超过 limit 的段落，先将文本中的表格行（以 | 开头的行）识别出来用
     * {{TABLE}}...{{/TABLE}} 标记包裹，非表格部分用 SentenceSplitter 按句子切分。
     * 这样可以保证表格内容不会被从中间拆开。
     * </p>
     *
     * @param part  待切分的段落
     * @param limit 段落最大长度限制
     * @return 切分后的段落列表
     */
    public static List<ParagraphSimple> splitContentPreserveTable(ParagraphSimple part, int limit) {
        String content = part.getContent();

        if (StringUtils.isBlank(content)) {
            return Collections.emptyList();
        }

        // 内容已不超过 limit，无需切分（避免调用昂贵的 SentenceSplitter）
        if (content.length() <= limit) {
            return Collections.singletonList(part);
        }

        // 查找所有表格块
        List<String> segments = getStringList(content);

        // 遍历 segments，对非表格段切分，表格段保留
        List<ParagraphSimple> result = new ArrayList<>();
        for (String seg : segments) {
            if (seg.startsWith("{{TABLE}}") && seg.endsWith("{{/TABLE}}")) {
                String tableContent = seg.substring("{{TABLE}}".length(), seg.length() - "{{/TABLE}}".length());
                result.add(ParagraphSimple.builder()
                        .title(part.getTitle())
                        .content(tableContent)
                        .build());
            } else {
                List<String> texts = SentenceSplitter.split(seg, limit);
                for (String text : texts) {
                    if (StringUtils.isNotBlank(text)) {
                        result.add(ParagraphSimple.builder()
                                .title(part.getTitle())
                                .content(text.trim())
                                .build());
                    }
                }
            }
        }

        return result;
    }

    /**
     * 将文本按表格行/非表格行分段。用逐行检测替代原正则匹配，
     * 避免 (?sm).*? 惰性匹配在大文本上的回溯开销。
     */
    private static @NotNull List<String> getStringList(String content) {
        // 快速检查：文本不含 | 则一定没有表格
        if (!content.contains("|")) {
            return Collections.singletonList(content);
        }

        String[] lines = content.split("\n");
        List<String> segments = new ArrayList<>();
        StringBuilder nonTableBuffer = new StringBuilder();
        StringBuilder tableBuffer = new StringBuilder();
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isTableLine = trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0;
            if (isTableLine) {
                if (!inTable) {
                    // 切换到表格模式，先把前面的非表格内容输出
                    if (nonTableBuffer.length() > 0) {
                        segments.add(nonTableBuffer.toString());
                        nonTableBuffer = new StringBuilder();
                    }
                    inTable = true;
                }
                tableBuffer.append(line).append("\n");
            } else {
                if (inTable) {
                    // 退出表格模式，输出表格块
                    segments.add("{{TABLE}}" + tableBuffer.toString() + "{{/TABLE}}");
                    tableBuffer = new StringBuilder();
                    inTable = false;
                }
                nonTableBuffer.append(line).append("\n");
            }
        }

        // 处理尾部缓冲
        if (inTable) {
            segments.add("{{TABLE}}" + tableBuffer.toString() + "{{/TABLE}}");
        }
        if (nonTableBuffer.length() > 0) {
            segments.add(nonTableBuffer.toString());
        }

        return segments;
    }
}