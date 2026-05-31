package com.maxkb4j.knowledge.parser.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.benjaminwan.ocrlibrary.OcrResult;
import com.maxkb4j.common.domain.dto.OssFile;
import com.maxkb4j.knowledge.parser.DocumentParser;
import com.maxkb4j.oss.service.IOssService;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF 文档解析器 —— 将 PDF 文件解析为 Markdown 格式的纯文本
 *
 * <h2>整体处理流程</h2>
 * <ol>
 *   <li><b>类型检测</b>：调用 {@link #isScannedPDF(PDDocument)} 判断是文字型PDF还是扫描件</li>
 *   <li><b>文字型PDF</b>：通过 PDFTextStripper 逐页提取文字，同时拦截嵌入图片</li>
 *   <li><b>扫描件PDF</b>：逐页渲染为图片 → 调用 PaddleOCR (ONNX) 做 OCR 识别</li>
 *   <li><b>图片处理</b>：文字型PDF中的嵌入图片上传到 MongoDB OSS，替换为 Markdown 图片链接</li>
 *   <li><b>标题识别</b>：通过 {@link #buildFontSizeHeadingMap(List)} 统计分析字号/字体，自动识别标题层级</li>
 *   <li><b>Markdown 输出</b>：通过 {@link #toMarkdown(List, HeadingContext)} 将文本行拼接为 Markdown 格式</li>
 * </ol>
 *
 * <h2>扫描件 vs 文字型PDF 的判断</h2>
 * <p>
 * 用 PDFTextStripper 提取前 3 页的文字，去掉所有空白字符后：
 * </p>
 * <ul>
 *   <li><b>纯文字长度 >= 10 字符</b> → 认为是文字型PDF，走直接提取路线</li>
 *   <li><b>纯文字长度 < 10 字符</b> → 认为是扫描件（每页是一张图片），走 OCR 路线</li>
 * </ul>
 *
 * <h2>标题自动识别策略</h2>
 * <p>
 * 统计全文各字号的词频，出现最多的字号 = 正文基准字号（bodyFontSize）。
 * 比基准字号大的按从大到小依次映射为 h1~h6。
 * 如果正文基准字号中存在粗体字体（如 SimHei/黑体/Bold），而主流字体非粗体，
 * 则粗体文本被推断为低一级子标题。
 * </p>
 *
 * @see DocumentParser
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PdfParser extends PDFTextStripper implements DocumentParser {

    /**
     * MongoDB GridFS 文件存储服务，用于上传 PDF 中提取的嵌入图片
     */
    private final IOssService mongoFileService;

    /**
     * 标记文本行为图片占位符的字体类型标识
     */
    private static final String IMAGE_STYLE = "IMAGE";

    /**
     * 全局文本行收集器（跨页累积，最终用于生成 Markdown）
     */
    private List<TextLine> lines;

    /**
     * 当前页面的裁剪高度（用于将 PDF 坐标 Y 值从底部坐标转换为顶部坐标）
     */
    private float currentPageHeight;

    /**
     * 当前页面的文字行集合（每页开始时清空，页结束时合并到 lines 中）
     */
    private List<TextLine> currentPageLines;

    /**
     * 当前页面的图片行集合（图片会被插入到对应 Y 坐标位置的文字行之间）
     */
    private List<TextLine> currentPageImages;

    /**
     * 待上传到 MongoDB OSS 的图片数据（收集完成后并行上传）
     */
    private List<ImageData> pendingImages;

    /**
     * PaddleOCR ONNX 推理引擎（单例，V4 模型，用于扫描件 OCR 识别）
     */
    private static final InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);

    /**
     * 页眉/页脚页码匹配模式：如 "—12—" 格式的页码
     */
    private static final Pattern PAGE_NUM_DASH = Pattern.compile("^—\\d+—$");

    /**
     * 纯数字页码匹配模式：如单独的 "12"
     */
    private static final Pattern PAGE_NUM_PURE = Pattern.compile("^\\d+$");

    /**
     * 字号上限阈值：超过此字号的文本行不受标题识别影响（避免异常大字号干扰统计）
     */
    private static final int MAX_TITLE_SIZE = 100;

    // ==================== DocumentParser 接口实现 ====================

    @Override
    public List<String> getExtensions() {
        return List.of(".pdf");
    }

    // ==================== PDFTextStripper 钩子方法（逐页提取文字） ====================

    /**
     * 每页处理前初始化：记录当前页高度、清空文字行和图片行缓存
     * <p>
     * page.getCropBox().getHeight() 获取的是 PDF 底部坐标系的高度值，
     * 后续计算图片/文字 Y 位置时需要转换为顶部坐标系（currentPageHeight - translateY）。
     * </p>
     */
    @Override
    public void processPage(PDPage page) throws IOException {
        currentPageHeight = page.getCropBox().getHeight();
        currentPageImages = new ArrayList<>();
        currentPageLines = new ArrayList<>();
        super.processPage(page);
    }

    /**
     * 拦截 PDF 操作符（Operator），检测嵌入图片
     * <p>
     * PDF 的图片通过 "Do" 操作符绘制。当检测到 Do 操作且目标为 ImageXObject 时，
     * 提取图片的 Y 坐标位置，生成占位符插入到文字流中，图片本身暂存到 pendingImages
     * 待后续并行上传到 MongoDB OSS。
     * </p>
     */
    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.getFirst() instanceof COSName cosName) {
            PDResources res = getResources();
            // 判断操作对象是否为图片资源
            if (res != null && res.isImageXObject(cosName)) {
                PDImageXObject image = (PDImageXObject) res.getXObject(cosName);
                handleImageInStream(image);
                return; // 图片已处理，跳过默认文字提取逻辑
            }
        }
        super.processOperator(operator, operands);
    }

    /**
     * 每页处理完毕后调用：合并同行文字、清除页码、插入图片占位符到对应位置
     * <p>
     * 处理顺序：
     * <ol>
     *   <li>合并连续同行文字（同一行的文字片段拼接成完整行）</li>
     *   <li>清除页眉/页脚的页码（如 "—12—" 和纯数字页码）</li>
     *   <li>将图片占位符按 Y 坐标插入到文字行之间的正确位置（保持阅读顺序）</li>
     *   <li>将当前页所有行追加到全局 lines 集合</li>
     * </ol>
     * </p>
     */
    @Override
    protected void endPage(PDPage page) throws IOException {
        // 合并同一 Y 坐标（容差 2.2）的连续文字片段为一行
        List<TextLine> mergedLines = mergeConsecutiveLines(currentPageLines);
        // 清除页眉/页脚的页码（"—12—" 和 "12" 两种格式）
        if (CollectionUtils.isNotEmpty(mergedLines)) {
            clearPageNumber(mergedLines, PAGE_NUM_DASH);
            clearPageNumber(mergedLines, PAGE_NUM_PURE);
        }
        // 将图片占位符插入到文字行之间的正确位置（按 Y 坐标排序）
        for (TextLine currentPageImage : currentPageImages) {
            float imgYPos = currentPageImage.yPos();
            for (int i = 0; i < mergedLines.size(); i++) {
                TextLine textLine = mergedLines.get(i);
                float yPos = textLine.yPos();
                // 找到第一个 Y 坐标大于图片的文字行，将图片插入到它前面
                if (yPos > imgYPos) {
                    mergedLines.set(i, currentPageImage);
                    break;
                }
            }
        }
        // 将当前页处理完的行追加到全局集合
        lines.addAll(mergedLines);
    }

    /**
     * 提取每个文字片段的位置和字体信息
     * <p>
     * PDFTextStripper 每遇到一个文字片段就回调此方法。
     * 这里记录了每个片段的字体名称、字号、X/Y 坐标和高度，
     * 后续用于同行合并、标题识别和图片位置对齐。
     * </p>
     */
    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        if (text == null || text.isEmpty() || textPositions == null || textPositions.isEmpty()) {
            return;
        }
        TextPosition first = textPositions.getFirst();
        String fontName = getFontName(first.getFont());
        float fontSize = first.getFontSizeInPt();
        float xPos = first.getXDirAdj();
        float yPos = first.getYDirAdj();
        float maxHeight = first.getHeight();
        // 将字体信息包装为 TextLine 记录
        TextLine textLine = new TextLine(fontName, text, fontSize, maxHeight, xPos, yPos);
        currentPageLines.add(textLine);
    }

    // ==================== 核心处理入口 ====================

    /**
     * 解析 PDF 文件的主入口方法
     *
     * <h3>完整处理链路：</h3>
     * <ol>
     *   <li>读取文件字节数组，用 PDFBox 加载 PDF 文档</li>
     *   <li><b>类型检测</b>：调用 {@link #isScannedPDF(PDDocument)} 判断是文字型还是扫描件
     *     <ul>
     *       <li>提取前 3 页文字，去掉所有空白字符后，长度 < 10 → 扫描件</li>
     *       <li>否则 → 文字型PDF</li>
     *     </ul>
     *   </li>
     *   <li><b>扫描件路线</b>：逐页渲染为图片 → PaddleOCR 识别文字 → 直接返回纯文本</li>
     *   <li><b>文字型路线</b>：设置按坐标排序 → 逐页提取文字和嵌入图片 → 收集到 lines 列表</li>
     *   <li>并行上传提取的嵌入图片到 MongoDB OSS</li>
     *   <li>统计字号频率确定正文基准，构建标题层级映射</li>
     *      *   <li>将文本行转为 Markdown 格式（含标题层级和图片链接）</li>
     * </ol>
     *
     * @param inputStream PDF 文件输入流
     * @return Markdown 格式的纯文本
     */
    @Override
    public String handle(InputStream inputStream) {
        this.lines = new ArrayList<>();
        this.pendingImages = new ArrayList<>();
        try {
            byte[] bytes = inputStream.readAllBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                // 判断是文字型PDF还是扫描件
                if (isScannedPDF(document)) {
                    // 扫描件 → 逐页渲染为图片 → PaddleOCR 识别
                    return extractTextFromScannedPDF(document);
                }
                // 文字型PDF → 按坐标排序提取文字 + 拦截嵌入图片
                this.setSortByPosition(true);
                this.setStartPage(1);
                this.setEndPage(document.getNumberOfPages());
                this.getText(document); // 触发 PDFTextStripper 的逐页提取钩子
            }
            // 并行上传嵌入图片到 MongoDB OSS，并将占位符替换为真实 URL
            uploadImagesInParallel();
            // 统计分析字号/字体，构建标题层级映射
            HeadingContext ctx = buildFontSizeHeadingMap(lines);
            // 将 TextLine 列表转为 Markdown 格式字符串
            return toMarkdown(lines, ctx);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PDF from input stream", e);
        }
    }

    // ==================== 页眉/页脚页码清除 ====================

    /**
     * 清除每页首尾的页码（页眉/页脚）
     * <p>
     * 同时检查第一个和最后一个 TextLine，
     * 如果匹配指定页码模式（"—12—" 或纯数字），则将其移除。
     * 先移除首行再检查尾行，中间可能因首行移除导致列表为空。
     * </p>
     *
     * @param lines   当前页的文本行列表
     * @param pattern 页码匹配的正则模式
     */
    private void clearPageNumber(List<TextLine> lines, Pattern pattern) {
        if (CollectionUtils.isNotEmpty(lines)) {
            TextLine first = lines.getFirst();
            TextLine last = lines.getLast();
            if (pattern.matcher(first.text()).matches()) {
                lines.removeFirst();
            }
            if (lines.isEmpty()) return;
            if (pattern.matcher(last.text()).matches()) {
                lines.removeLast();
            }
        }
    }

    // ==================== PDF 嵌入图片提取 ====================

    /**
     * 提取 PDF 内容流中的嵌入图片（PDImageXObject）
     * <p>
     * 将 PDF 坐标系的 translateY 转换为顶部坐标系（currentPageHeight - translateY），
     * 生成唯一的文件名（格式：pdf_p{页码}_img{序号}.png），
     * 创建图片占位符 TextLine 插入到文字流中，图片字节暂存到 pendingImages 列表。
     * </p>
     *
     * @param image PDF 中的图片对象
     */
    private void handleImageInStream(PDImageXObject image) throws IOException {
        // 获取当前变换矩阵的平移量（PDF 底部坐标系）
        float translateX = getGraphicsState().getCurrentTransformationMatrix().getTranslateX();
        float translateY = getGraphicsState().getCurrentTransformationMatrix().getTranslateY();
        // 转换为顶部坐标系：Y_顶部 = 页面高度 - Y_底部
        float yPos = currentPageHeight - translateY;
        // 提取图片为 BufferedImage，再转为 PNG 字节数组
        BufferedImage bufferedImage = image.getImage();
        byte[] imageBytes = bufferedImageToBytes(bufferedImage);
        int pageNo = getCurrentPageNo();
        int imgIndex = currentPageImages.size();
        String fileName = "pdf_p" + pageNo + "_img" + imgIndex + ".png";
        // 创建图片占位符（fontStyle="IMAGE" 标记为图片类型）
        currentPageImages.add(new TextLine(IMAGE_STYLE, fileName, 0, 0, translateX, yPos));
        // 暂存待上传的图片数据
        pendingImages.add(new ImageData(fileName, imageBytes));
    }

    /**
     * 并行上传所有待处理的嵌入图片到 MongoDB OSS
     * <p>
     * 使用固定线程池（最多 8 线程）并行上传，上传完成后依次替换
     * lines 列表中的图片占位符（将临时文件名替换为 MongoDB 返回的真实 URL）。
     * 即使没有图片也不会出错（isEmpty 检查后直接返回）。
     * </p>
     */
    private void uploadImagesInParallel() {
        if (pendingImages.isEmpty()) return;
        // 线程数不超过图片数量和 8
        int threads = Math.min(pendingImages.size(), 8);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            // 使用 ConcurrentHashMap 收集上传结果（线程安全）
            Map<String, String> urlMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ImageData img : pendingImages) {
                futures.add(CompletableFuture.runAsync(() -> {
                    OssFile ossFile = mongoFileService.uploadFile(img.fileName(), img.bytes());
                    urlMap.put(img.fileName(), ossFile.getUrl());
                }, executor));
            }
            // 等待所有上传任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // 将 lines 中的图片文件名占位符替换为真实 OSS URL
            for (int i = 0; i < lines.size(); i++) {
                TextLine line = lines.get(i);
                if (IMAGE_STYLE.equals(line.fontStyle()) && urlMap.containsKey(line.text())) {
                    lines.set(i, new TextLine(IMAGE_STYLE, urlMap.get(line.text()), 0, 0, line.xPos(), line.yPos()));
                }
            }
        } finally {
            executor.shutdown();
            pendingImages.clear();
        }
    }

    // ==================== 字体工具方法 ====================

    /**
     * 安全获取 PDF 字体名称
     * <p>
     * PDF 字体可能为 null 或获取名称时抛异常，这里统一兜底返回 "unknown"。
     * </p>
     *
     * @param font PDFBox 字体对象
     * @return 字体名称字符串，获取失败时返回 "unknown"
     */
    private String getFontName(PDFont font) {
        if (font == null) {
            return "unknown";
        }
        try {
            return font.getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== 扫描件检测 ====================

    /**
     * 判断 PDF 是否为扫描件（图片型PDF）
     * <p>
     * <b>核心逻辑</b>：用 PDFTextStripper 提取前 3 页的文字内容，
     * 去掉所有空白字符（空格、制表符、换行等）后，
     * 如果纯文字长度 < 10 个字符，则判定为扫描件。
     * </p>
     * <p>
     * <b>为什么是 10 个字符？</b>
     * 文字型 PDF 通常每页有数百到数千字符；而扫描件即使有隐藏的 OCR 层或元数据，
     * 也极少超过 10 个有效字符（可能只有文件名、页面标签等零散信息）。
     * </p>
     * <p>
     * <b>为什么检查前 3 页？</b>
     * 有些 PDF 封面是扫描件但正文是文字，或者相反。取前 3 页可以覆盖最常见的混合情况，
     * 也避免检查所有页面带来的性能损耗。
     * </p>
     *
     * @param document PDF 文档对象
     * @return true=扫描件（需要OCR），false=文字型PDF
     */
    private static boolean isScannedPDF(PDDocument document) {
        int checkPages = Math.min(3, document.getNumberOfPages());
        try {
            // 用独立的 PDFTextStripper 快速检测（不影响当前实例的状态）
            PDFTextStripper checkStripper = new PDFTextStripper();
            checkStripper.setStartPage(1);
            checkStripper.setEndPage(checkPages);
            String text = checkStripper.getText(document);
            // 去掉所有空白字符，只保留有效文字
            String cleanText = text.replaceAll("\\s+", "");
            // 有效文字字符数 < 10 → 扫描件
            return cleanText.trim().length() < 10;
        } catch (IOException e) {
            log.error(e.getMessage());
            return false; // 出错时假定为文字型，让正常提取流程尝试处理
        }
    }

    // ==================== 扫描件 OCR 处理 ====================

    /**
     * 针对扫描件 PDF 的 OCR 识别
     * <p>
     * 处理流程：
     * <ol>
     *   <li>使用 PDFRenderer 逐页将页面渲染为 BufferedImage（默认 DPI 下渲染）</li>
     *   <li>将 BufferedImage 写入临时 PNG 文件</li>
     *   <li>调用 PaddleOCR (ONNX V4) 引擎识别文字</li>
     *   <li>删除临时文件，拼接所有页面的识别结果</li>
     * </ol>
     * </p>
     * <p>
     * <b>为什么用 ONNX 版本？</b>
     * 相比 Python 版 PaddleOCR，ONNX 版本不依赖 Python 运行时，
     * 可直接在 JVM 进程中通过 JNA 调用，无需跨进程通信，延迟更低。
     * </p>
     *
     * @param document PDF 文档对象
     * @return OCR 识别的纯文本（页与页之间用两个换行符分隔）
     */
    private static String extractTextFromScannedPDF(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder fullText = new StringBuilder();
        int totalPages = document.getNumberOfPages();
        for (int i = 0; i < totalPages; i++) {
            // 将第 i 页渲染为图片
            BufferedImage image = renderer.renderImage(i);
            // 写入临时 PNG 文件供 OCR 引擎读取
            Path tempFile = Files.createTempFile("pdf_page_", ".png");
            try {
                ImageIO.write(image, "png", tempFile.toFile());
                // 调用 PaddleOCR ONNX 引擎识别
                OcrResult result = engine.runOcr(tempFile.toString());
                fullText.append(result.getStrRes()).append("\n\n");
            } finally {
                // 清理临时文件
                Files.deleteIfExists(tempFile);
            }
        }
        return fullText.toString();
    }

    // ==================== 图像格式转换 ====================

    /**
     * 将 BufferedImage 转换为 PNG 字节数组
     * <p>
     * 如果图片不是标准 RGB 色彩空间（如 CMYK PDF 图片），
     * 会先转换到 ARGB 色彩空间再输出，确保 PNG 编码兼容性。
     * </p>
     *
     * @param image 原始 BufferedImage
     * @return PNG 格式的字节数组
     */
    private static byte[] bufferedImageToBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baoStream = new ByteArrayOutputStream();
        BufferedImage rgbImage = ensureRgbColorSpace(image);
        ImageIO.write(rgbImage, "png", baoStream);
        return baoStream.toByteArray();
    }

    /**
     * 确保图片处于 RGB/ARGB 色彩空间
     * <p>
     * 检查图片的色块数和透明度通道：
     * 如果 numColorComponents ≤ 3（即 RGB 或灰度）且无 alpha 通道 → 直接返回；
     * 否则绘制到新的 ARGB BufferedImage 上做一次色彩空间转换。
     * </p>
     *
     * @param image 原始图片
     * @return RGB 色彩空间的图片
     */
    private static BufferedImage ensureRgbColorSpace(BufferedImage image) {
        if (image.getColorModel().getNumColorComponents() <= 3
                && !image.getColorModel().hasAlpha()) {
            return image; // 已是 RGB/灰度格式，无需转换
        }
        // 色彩空间不一致（如 CMYK），转换到 ARGB
        BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rgbImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgbImage;
    }

    // ==================== 标题层级识别（字号/字体统计分析） ====================

    /**
     * 统计字号频率确定正文基准字号，结合字体粗体特征映射标题层级
     *
     * <h3>算法步骤：</h3>
     * <ol>
     *   <li><b>统计字号频率</b>：排除超大字号（>100pt）的异常数据</li>
     *   <li><b>确定正文基准字号</b>：出现次数最多的字号 = bodyFontSize（通常 10-14pt）</li>
     *   <li><b>确定正文主流字体是否粗体</b>：在基准字号下，统计各字体名频率，最多者为正文主流字体，判断其是否为粗体</li>
     *   <li><b>映射标题层级</b>：比基准字号大的字号按从大到小排序，依次映射为 h1~h6</li>
     *   <li><b>基线及更小字号</b> → 段落（level=0）</li>
     *   <li><b>粗体子标题</b>：如果正文基准字号中存在粗体变体（如 SimHei/黑体/Bold），而正文主流字体非粗体，则粗体变体自动推断为低一级子标题</li>
     * </ol>
     *
     * <h3>示例：</h3>
     * <pre>
     * 字号出现次数统计：
     *   18pt → 3次   → h1（最大字号）
     *   16pt → 8次   → h2
     *   14pt → 5次   → h3
     *   12pt → 200次 → bodyFontSize（基准、段落）
     *   10pt → 50次  → 段落（小于基准）
     *
     * 如果在12pt下有 "SimHei" 字体出现 → 粗体子标题（h4）
     * </pre>
     *
     * @param lines 所有页面的文本行列表
     * @return 标题上下文，包含字号到标题层级的映射
     */
    private static HeadingContext buildFontSizeHeadingMap(List<TextLine> lines) {
        // 步骤1：统计非超大字号（≤100pt）的出现频率
        Map<Float, Integer> freq = new LinkedHashMap<>();
        for (TextLine line : lines) {
            if (line.fontSize() < MAX_TITLE_SIZE) {
                freq.merge(line.fontSize(), 1, Integer::sum);
            }
        }
        if (freq.isEmpty()) {
            return new HeadingContext(Map.of(), 12f, false, 1);
        }

        // 步骤2：出现次数最多的字号 = 正文基准字号
        float bodyFontSize = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(12f);

        // 步骤3：在正文基准字号下，统计各字体名频率，判断正文主流字体是否粗体
        Map<String, Integer> fontFreqAtBodySize = new LinkedHashMap<>();
        for (TextLine line : lines) {
            if (line.fontSize() == bodyFontSize) {
                fontFreqAtBodySize.merge(line.fontStyle(), 1, Integer::sum);
            }
        }
        // 出现最多的字体 = 正文主流字体
        String bodyFontName = fontFreqAtBodySize.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        boolean bodyIsBold = isBoldFont(bodyFontName);

        // 步骤4：比基准字号大的字号按从大到小排序，映射为 h1~h6
        List<Float> headingSizes = freq.keySet().stream()
                .filter(s -> s > bodyFontSize)
                .sorted(Comparator.reverseOrder())
                .toList();

        Map<Float, Integer> sizeToLevel = new LinkedHashMap<>();
        for (int i = 0; i < headingSizes.size() && i < 6; i++) {
            sizeToLevel.put(headingSizes.get(i), i + 1); // 最大的 → h1, 次大 → h2 ...
        }

        // 步骤5：基准字号及更小的字号 → 段落（level=0，非标题）
        sizeToLevel.put(bodyFontSize, 0);
        freq.keySet().stream()
                .filter(s -> s < bodyFontSize && s > 0)
                .forEach(s -> sizeToLevel.put(s, 0));

        // 步骤6：计算粗体子标题层级
        // 例如正文12pt, 标题有 h1(18pt), h2(16pt), h3(14pt)
        // → maxHeadingLevel = 3, boldAtBaselineLevel = min(4, 6) = 4
        // 意思是12pt的粗体文字会被当作 h4 子标题
        int maxHeadingLevel = sizeToLevel.values().stream()
                .filter(l -> l > 0)
                .max(Integer::compare)
                .orElse(0);
        int boldAtBaselineLevel = Math.min(maxHeadingLevel + 1, 6);

        return new HeadingContext(sizeToLevel, bodyFontSize, bodyIsBold, boldAtBaselineLevel);
    }

    // ==================== Markdown 输出 ====================

    /**
     * 将文本行列表转为 Markdown 格式字符串
     *
     * <h3>转换规则：</h3>
     * <ul>
     *   <li><b>图片行</b>（fontStyle="IMAGE"）：输出为 {@code ![](图片URL)}</li>
     *   <li><b>标题行</b>：如果文本行的字号对应标题层级（level > 0）且 X 坐标靠左（< 150），
     *       输出为 Markdown 标题（如 {@code ## 第一章}）</li>
     *   <li><b>粗体子标题</b>：如果文本行为正文基准字号但字体是粗体（如 SimHei），
     *       且正文主流字体非粗体，则推断为子标题</li>
     *   <li><b>普通段落</b>：直接输出文本内容</li>
     *   <li><b>X 坐标 ≥ 150</b>：即使字号匹配标题层级也当作普通段落（防止误判缩进文本为标题）</li>
     * </ul>
     *
     * @param lines 文本行列表（图片占位符已替换为真实 URL）
     * @param ctx   标题层级上下文
     * @return Markdown 格式的纯文本
     */
    private static String toMarkdown(List<TextLine> lines, HeadingContext ctx) {
        StringBuilder md = new StringBuilder();
        for (TextLine line : lines) {
            // 图片行 → Markdown 图片语法
            if (IMAGE_STYLE.equals(line.fontStyle())) {
                md.append("![](").append(line.text()).append(")").append("\n");
                continue;
            }
            String text = line.text().trim();
            // 空白行 → 保留换行
            if (text.isEmpty() || text.equals("\n")) {
                md.append("\n");
                continue;
            }
            // 查询当前字号对应的标题层级
            int level = ctx.fontSizeToLevel().getOrDefault(line.fontSize(), 0);

            // 粗体正文基准字号 → 子标题（例如正文12pt，但当前行用了 SimHei 粗体）
            if (level == 0
                    && line.fontSize() == ctx.bodyFontSize()
                    && isBoldFont(line.fontStyle())
                    && !ctx.bodyIsBold()) {
                level = ctx.boldAtBaselineLevel();
            }

            // X 坐标靠左（< 150）且有标题层级 → Markdown 标题
            // X 坐标阈值 150 用于区分真正的标题和缩进后的文本引用
            if (level > 0 && line.xPos() < 150) {
                md.append("#".repeat(level)).append(" ").append(text).append("\n\n");
            } else {
                // 普通段落
                md.append(text).append("\n");
            }
        }
        return md.toString().trim();
    }

    // ==================== 同页文字行合并 ====================

    /**
     * 合并连续具有相同 Y 坐标的文本行为一行
     * <p>
     * PDF 文字提取时，同一行文字可能被 PDFTextStripper 拆分为多个片段
     * （例如不同字体、不同颜色的文字会被独立提取）。
     * 此方法将 Y 坐标差值在 2.2 以内的连续文字片段拼接为完整的一行。
     * </p>
     *
     * <h3>合并时保留的信息：</h3>
     * <ul>
     *   <li>字体信息：使用后一片段的字体</li>
     *   <li>X 坐标：使用首个片段的 X 坐标（保持行首位置）</li>
     *   <li>Y 坐标：使用后一片段的 Y 坐标</li>
     * </ul>
     *
     * @param lines 当前页的文字行片段列表
     * @return 合并后的完整文本行列表
     */
    private static List<TextLine> mergeConsecutiveLines(List<TextLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextLine> merged = new ArrayList<>();
        TextLine current = lines.getFirst();
        merged.add(current);
        for (int i = 1; i < lines.size(); i++) {
            TextLine next = lines.get(i);
            // Y 坐标差值 ≤ 2.2 → 同一行，拼接文本
            if (isSameRow(current, next)) {
                String combinedText = current.text() + next.text();
                current = new TextLine(next.fontStyle(), combinedText, next.fontSize(), next.maxHeight(), current.xPos(), next.yPos());
                merged.set(merged.size() - 1, current);
            } else {
                // 不是同一行 → 作为新的独立行
                merged.add(next);
                current = next;
            }
        }
        return merged;
    }

    /**
     * 判断两个文本行是否在同一行（Y 坐标差 ≤ 2.2）
     * <p>
     * 2.2 的容差是为了容忍 PDF 中同一行文字因基线对齐导致的微小 Y 轴偏移。
     * </p>
     */
    private static boolean isSameRow(TextLine a, TextLine b) {
        if (a == null || b == null) return false;
        float diff = Math.abs(a.yPos - b.yPos);
        return diff <= 2.2;
    }

    /**
     * 判断字体名称是否为粗体
     * <p>
     * PDF 中没有类似 CSS font-weight 的标准粗体属性，只能通过字体名称判断：
     * </p>
     * <ul>
     *   <li>SimHei（黑体）→ 粗体</li>
     *   <li>Heiti（黑体系列）→ 粗体</li>
     *   <li>名称含 "bold" 关键字 → 粗体</li>
     *   <li>名称含 "黑体" 汉字 → 粗体</li>
     * </ul>
     *
     * @param fontName PDF 字体名称
     * @return true=粗体字体
     */
    private static boolean isBoldFont(String fontName) {
        if (fontName == null || fontName.isEmpty()) return false;
        String lower = fontName.toLowerCase();
        return lower.contains("simhei") || lower.contains("heiti") || lower.contains("bold") || fontName.contains("黑体");
    }

    // ==================== 内部数据类 ====================

    /**
     * 标题层级上下文 —— 字号/字体统计分析结果
     *
     * @param fontSizeToLevel       字号到标题层级的映射（0=段落, 1=h1, 2=h2 ... 6=h6）
     * @param bodyFontSize          正文基准字号（出现频率最高的字号）
     * @param bodyIsBold            正文主流字体是否本身就是粗体
     * @param boldAtBaselineLevel   基准字号下粗体变体的标题层级（maxHeadingLevel+1，上限6）
     */
    private record HeadingContext(
            Map<Float, Integer> fontSizeToLevel,
            float bodyFontSize,
            boolean bodyIsBold,
            int boldAtBaselineLevel
    ) {
    }

    /**
     * PDF 文本行数据记录 —— 每个文字片段的位置和字体元信息
     *
     * @param fontStyle 字体名称（如 "SimSun"、"SimHei"）
     * @param text      文字内容
     * @param fontSize  字号（pt）
     * @param maxHeight 字体最大高度（用于排版分析）
     * @param xPos      X 坐标（水平位置，用于判断是否为标题）
     * @param yPos      Y 坐标（垂直位置，底部坐标系，用于同行判断和图片位置插入）
     */
    public record TextLine(String fontStyle, String text, float fontSize, float maxHeight, float xPos, float yPos) {
        public TextLine(String fontStyle, String text, float fontSize, float maxHeight, float xPos, float yPos) {
            this.fontStyle = fontStyle != null ? fontStyle : "unknown";
            this.text = text != null ? text : "";
            this.fontSize = fontSize;
            this.maxHeight = maxHeight;
            this.xPos = xPos;
            this.yPos = yPos;
        }
    }

    /**
     * 待上传的嵌入图片数据
     *
     * @param fileName 图片文件名（如 "pdf_p3_img2.png"）
     * @param bytes    图片 PNG 字节数组
     */
    private record ImageData(String fileName, byte[] bytes) {}
}
