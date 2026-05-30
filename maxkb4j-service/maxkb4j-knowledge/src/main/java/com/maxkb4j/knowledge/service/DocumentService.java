package com.maxkb4j.knowledge.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.maxkb4j.common.domain.vo.KeyAndValueVO;
import com.maxkb4j.common.exception.FileLimitExceededException;
import com.maxkb4j.common.util.IoUtil;
import com.maxkb4j.common.util.SecurityUtil;
import com.maxkb4j.core.event.DocumentIndexEvent;
import com.maxkb4j.core.event.GenerateProblemEvent;
import com.maxkb4j.core.util.ExcelUtil;
import com.maxkb4j.knowledge.consts.KnowledgeType;
import com.maxkb4j.knowledge.dto.DatasetBatchHitHandlingDTO;
import com.maxkb4j.knowledge.dto.DocQuery;
import com.maxkb4j.knowledge.dto.DocumentSimple;
import com.maxkb4j.knowledge.dto.GenerateProblemDTO;
import com.maxkb4j.knowledge.entity.*;
import com.maxkb4j.knowledge.excel.KnowledgeExcel;
import com.maxkb4j.knowledge.handler.DocumentHandler;
import com.maxkb4j.knowledge.mapper.DocumentMapper;
import com.maxkb4j.knowledge.mapper.KnowledgeMapper;
import com.maxkb4j.knowledge.store.IDataStore;
import com.maxkb4j.knowledge.vo.DocFileVO;
import com.maxkb4j.knowledge.vo.DocumentVO;
import com.maxkb4j.knowledge.vo.TextSegmentVO;
import com.maxkb4j.oss.service.IOssService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文档服务 —— 知识库文档的完整管理
 * <p>
 * 提供文档的导入、查询、导出、迁移、删除等操作。是 DocumentController 的后端服务层。
 * </p>
 *
 * <h3>核心入库方法</h3>
 * <ul>
 *   <li>{@link #importQa(String, MultipartFile[])} —— 导入 QA 问答对（Excel/CSV）</li>
 *   <li>{@link #importTable(String, MultipartFile[])} —— 导入表格文档</li>
 *   <li>{@link #split(String, MultipartFile[], String[], Integer, Boolean)} —— 预切片（预览用）</li>
 *   <li>{@link #createWebDoc(String, List, String)} —— 从 URL 创建 WEB 文档</li>
 *   <li>{@link #batchCreateDocs(String, int, List)} —— 委托给 DocumentWriteService 批量写入</li>
 * </ul>
 *
 * @author tarzan
 * @date 2024-12-25 17:00:26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService extends ServiceImpl<DocumentMapper, DocumentEntity> implements IDocumentService{

    private final ParagraphService paragraphService;
    private final ProblemParagraphService problemParagraphService;
    private final DocumentParseService documentParseService;
    private final DocumentSplitService documentSpiltService;
    private final IOssService mongoFileService;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentWebService documentWebService;
    private final DocumentWriteService documentWriteService;
    private final DocumentHandler documentHandler;
    private final IDataStore compositeStore;
    private final KnowledgeMapper knowledgeMapper;

    public void updateStatusMetaById(String id) {
        baseMapper.updateStatusMetaByIds(List.of(id));
    }

    public void updateStatusById(String id, int type, int status) {
        baseMapper.updateStatusByIds(List.of(id), type, status);
    }

    public void updateStatusByIds(List<String> ids, int type, int status) {
        baseMapper.updateStatusByIds(ids, type, status);
    }

    public List<DocumentEntity> listDocByKnowledgeId(String id) {
        return this.lambdaQuery().eq(DocumentEntity::getKnowledgeId, id).list();
    }

    @Transactional
    public boolean migrateDoc(String sourceKnowledgeId, String targetKnowledgeId, List<String> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return false;
        }
        compositeStore.deleteByDocumentIds(targetKnowledgeId,docIds);
        paragraphService.lambdaUpdate().set(ParagraphEntity::getKnowledgeId, targetKnowledgeId).in(ParagraphEntity::getDocumentId, docIds).update();
        problemParagraphService.lambdaUpdate().eq(ProblemParagraphEntity::getKnowledgeId, sourceKnowledgeId).in(ProblemParagraphEntity::getDocumentId, docIds).remove();
        publishDocumentIndexEvent(targetKnowledgeId, docIds, List.of("0","1","2","3","4","5","n"));
        return this.lambdaUpdate()
                .set(DocumentEntity::getKnowledgeId, targetKnowledgeId)
                .in(DocumentEntity::getId, docIds)
                .update();
    }

    @Transactional
    public boolean batchHitHandling(String knowledgeId, DatasetBatchHitHandlingDTO dto) {
        List<String> ids = dto.getIdList();
        if (CollectionUtils.isEmpty(ids)) {
            return false;
        }
        List<DocumentEntity> documentEntities = ids.stream().map(id -> {
            DocumentEntity entity = new DocumentEntity();
            entity.setId(id);
            entity.setKnowledgeId(knowledgeId);
            entity.setHitHandlingMethod(dto.getHitHandlingMethod());
            entity.setDirectlyReturnSimilarity(dto.getDirectlyReturnSimilarity());
            return entity;
        }).collect(Collectors.toList());
        return this.updateBatchById(documentEntities);
    }

    /**
     * 导入 QA 问答对文档
     * <p>
     * 对应 API：POST /admin/workspace/api/knowledge/{id}/document/qa
     * 支持上传 Excel(.xlsx/.xls) 或 CSV 文件，每行包含"问题"和"答案"两列。
     * 也支持 ZIP 压缩包（内部处理解压）。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>校验文件数量和大小限制</li>
     *   <li>DocumentHandler.processQaFile() 解析 Excel/CSV，生成 DocumentSimple 列表</li>
     *   <li>调用 batchCreateDocs() → DocumentWriteService.batchCreateDocs() 写入数据库</li>
     *   <li>写入完成后发布 DocumentIndexEvent，异步向量化</li>
     * </ol>
     *
     * @param knowledgeId 知识库ID
     * @param files       上传的 Excel/CSV/ZIP 文件数组
     */
    @Transactional
    public void importQa(String knowledgeId, MultipartFile[] files) throws IOException {
        if (checkFileLimit(knowledgeId,files)){
            throw new FileLimitExceededException("文件数量超出限制");
        }
        if (files == null) return;
        List<DocumentSimple> docs =new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String fileName = file.getOriginalFilename();
            if (fileName == null) continue;
            // 验证文件名安全性
            if (SecurityUtil.illegalityFileName(fileName)) {
                continue; // 跳过非法文件
            }
            if (fileName.toLowerCase().endsWith(".zip")) {
                docs.addAll(documentHandler.processZipQaFile(file));
            } else {
                docs.addAll(documentHandler.processQaFile(file.getBytes(), fileName));
            }
        }
        // 将解析的文档保存到数据库
        if (!docs.isEmpty()) {
            batchCreateDocs(knowledgeId, KnowledgeType.BASE, docs);
        }
    }

    /**
     * 导入表格文档
     * <p>
     * 对应 API：POST /admin/workspace/api/knowledge/{id}/document/table
     * 将表格的每一行转为 "key: value" 格式的文本段落。
     * </p>
     *
     * @param knowledgeId 知识库ID
     * @param files       上传的表格文件
     */
    @Transactional
    public void importTable(String knowledgeId, MultipartFile[] files) throws IOException {
        if (checkFileLimit(knowledgeId,files)){
            throw new FileLimitExceededException("文件数量超出限制");
        }
        if (files == null) return;
        List<DocumentSimple> docs =new ArrayList<>();
        for (MultipartFile uploadFile : files) {
            if (uploadFile == null || uploadFile.isEmpty()) continue;
            String originalFilename = uploadFile.getOriginalFilename();
            if (originalFilename == null) continue;

            // 验证文件名安全性
            if (SecurityUtil.illegalityFileName(originalFilename)) {
                continue; // 跳过非法文件
            }

            docs.addAll(documentHandler.processTable(uploadFile.getBytes(), originalFilename));
        }
        // 将解析的文档保存到数据库
        if (!docs.isEmpty()) {
            batchCreateDocs(knowledgeId, KnowledgeType.BASE, docs);
        }
    }

    /**
     * 委托给 DocumentWriteService 批量写入文档到数据库
     * <p>
     * 这是一个薄代理方法，实际逻辑在 DocumentWriteService.batchCreateDocs() 中。
     * 写入数据库后自动发布 DocumentIndexEvent 触发异步向量化。
     * </p>
     *
     * @param knowledgeId   知识库ID
     * @param knowledgeType 知识库类型
     * @param docs          已切分好段落的文档列表
     * @return true=写入成功
     */
    public boolean batchCreateDocs(String knowledgeId, int knowledgeType, List<DocumentSimple> docs) {
       return documentWriteService.batchCreateDocs(knowledgeId,knowledgeType, docs);
    }

    public void exportExcelByDocId(String docId, HttpServletResponse response) {
        DocumentEntity doc = this.getById(docId);
        if (doc == null) return;
        List<KnowledgeExcel> list = getDatasetExcelByDoc(doc);
        int index=doc.getName().lastIndexOf(".");
        int end=Math.min(31,index);
        String sheetName=doc.getName().substring(0,end);
        ExcelUtil.export(response, doc.getName(), sheetName, list, KnowledgeExcel.class);
    }

    public void exportExcelZipByDocId(String docId, HttpServletResponse response) throws IOException {
        DocumentEntity doc = this.getById(docId);
        if (doc == null) return;
        exportExcelZipByDocs(List.of(doc), doc.getName(), response);
    }

    private List<KnowledgeExcel> getDatasetExcelByDoc(DocumentEntity doc) {
        List<KnowledgeExcel> list = new ArrayList<>();
        List<ParagraphEntity> paragraphs = paragraphService.lambdaQuery()
                .eq(ParagraphEntity::getDocumentId, doc.getId())
                .list();
        for (ParagraphEntity paragraph : paragraphs) {
            KnowledgeExcel excel = new KnowledgeExcel();
            excel.setTitle(paragraph.getTitle());
            excel.setContent(paragraph.getContent());
            List<ProblemEntity> problemEntities = problemParagraphService.getProblemsByParagraphId(paragraph.getId());
            if (!CollectionUtils.isEmpty(problemEntities)) {
                String problems = problemEntities.stream()
                        .map(ProblemEntity::getContent)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.joining("\n"));
                excel.setProblems(problems);
            }
            list.add(excel);
        }
        return list;
    }

    public void exportExcelZipByDocs(List<DocumentEntity> docs, String exportName, HttpServletResponse response) throws
            IOException {
        if (docs.isEmpty()) return;
        response.setContentType("application/zip");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String encodedName = URLEncoder.encode(exportName, StandardCharsets.UTF_8);
        response.setHeader("Content-disposition", "attachment;filename=" + encodedName + ".zip");
        try (ByteArrayOutputStream zipBuffer = new ByteArrayOutputStream();
             ZipOutputStream zipOut = new ZipOutputStream(zipBuffer);
             ByteArrayOutputStream excelBuffer = new ByteArrayOutputStream();
             ExcelWriter excelWriter = EasyExcel.write(excelBuffer, KnowledgeExcel.class).build()) {
            for (DocumentEntity doc : docs) {
                List<KnowledgeExcel> data = getDatasetExcelByDoc(doc);
                WriteSheet sheet = EasyExcel.writerSheet(doc.getName()).build();
                excelWriter.write(data, sheet);
            }
            excelWriter.finish();

            ZipEntry zipEntry = new ZipEntry(exportName + ".xlsx");
            zipOut.putNextEntry(zipEntry);
            zipOut.write(excelBuffer.toByteArray());
            zipOut.closeEntry();
            zipOut.finish();

            IoUtil.copy(new ByteArrayInputStream(zipBuffer.toByteArray()), response.getOutputStream());
        }
    }


    @Transactional
    public boolean deleteDocByIds(String knowledgeId, List<String> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return false;
        }
        this.lambdaUpdate().in(DocumentEntity::getId, docIds).remove();
        paragraphService.lambdaUpdate().in(ParagraphEntity::getDocumentId, docIds).remove();
        compositeStore.deleteByDocumentIds(knowledgeId,docIds);
        return  problemParagraphService.lambdaUpdate().in(ProblemParagraphEntity::getDocumentId, docIds).remove();
    }

    @Transactional
    public boolean embedByDocIds(String knowledgeId, List<String> docIds, List<String> stateList) {
        publishDocumentIndexEvent(knowledgeId, docIds, stateList);
        return true;
    }

    public boolean cancelTask(String docId, DocumentEntity doc) {
        DocumentEntity entity = baseMapper.selectById(docId);
        if (entity == null) return false;
        String status = entity.getStatus();
        if (status == null || status.length() < 3) return false;
        StringBuilder newStatus = new StringBuilder(status);
        if (doc.getType() == 1) {
            newStatus.setCharAt(2, '3'); // 向量化取消
        } else if (doc.getType() == 2) {
            newStatus.setCharAt(1, '3'); // 问题生成取消
        }
        entity.setStatus(newStatus.toString());
        return this.updateById(entity);
    }

    public DocumentEntity updateAndGetById(String docId, DocumentEntity documentEntity) {
        documentEntity.setId(docId);
        this.updateById(documentEntity);
        return this.getById(docId);
    }



    public IPage<DocumentVO> getDocByKnowledgeId(String knowledgeId, int current, int size, DocQuery query) {
        Page<DocumentVO> docPage = new Page<>(current, size);
        baseMapper.selectDocPage(docPage, knowledgeId, query);
        return docPage;
    }

    public List<KeyAndValueVO> splitPattern() {
        return Arrays.asList(
                new KeyAndValueVO("#", "(?<=^)# .*|(?<=\\n)# .*"),
                new KeyAndValueVO("##", "(?<=\\n)(?<!#)## (?!#).*|(?<=^)(?<!#)## (?!#).*"),
                new KeyAndValueVO("###", "(?<=\\n)(?<!#)### (?!#).*|(?<=^)(?<!#)### (?!#).*"),
                new KeyAndValueVO("####", "(?<=\\n)(?<!#)#### (?!#).*|(?<=^)(?<!#)#### (?!#).*"),
                new KeyAndValueVO("#####", "(?<=\\n)(?<!#)##### (?!#).*|(?<=^)(?<!#)##### (?!#).*"),
                new KeyAndValueVO("######", "(?<=\\n)(?<!#)###### (?!#).*|(?<=^)(?<!#)###### (?!#).*"),
                new KeyAndValueVO("-", "(?<! )- .*"),
                new KeyAndValueVO("space", "(?<! ) (?! )"),
                new KeyAndValueVO("semicolon", "(?<!；)；(?!；)"),
                new KeyAndValueVO("comma", "(?<!，)，(?!，)"),
                new KeyAndValueVO("period", "(?<!。)。(?!。)"),
                new KeyAndValueVO("enter", "(?<!\\n)\\n(?!\\n)"),
                new KeyAndValueVO("blank line", "(?<!\\n)\\n\\n(?!\\n)")
        );
    }

    /**
     * 文档预切片（仅预览，不保存到数据库）
     * <p>
     * 对应 API：POST /admin/workspace/api/knowledge/{id}/document/split
     * 用于用户上传前预览切片效果，不会真正写入数据库。
     * </p>
     *
     * <h3>处理步骤</h3>
     * <ol>
     *   <li>校验文件数量和大小限制</li>
     *   <li>处理 ZIP 文件（解压提取内部文件）</li>
     *   <li>文件存储到 MongoDB（OSS）获取 fileId</li>
     *   <li>DocumentParseService.extractText() 解析文件提取文本</li>
     *   <li>DocumentSplitService.split() 按策略切片</li>
     *   <li>返回切片预览结果（不写入 paragraph 表）</li>
     * </ol>
     *
     * @param knowledgeId 知识库ID
     * @param files       上传的文件
     * @param patterns    切片模式（null 时走智能模式）
     * @param limit       段落最大长度限制
     * @param withFilter  是否清洗结果
     * @return 切片预览列表，每个元素包含文件名、切片段落、存储的 fileId
     */
    public List<TextSegmentVO> split(String knowledgeId, MultipartFile[] files, String[] patterns, Integer limit, Boolean withFilter) throws IOException {
        if (checkFileLimit(knowledgeId,files)){
            throw new FileLimitExceededException("文件数量超出限制");
        }
        List<TextSegmentVO> result = new ArrayList<>();
        List<DocFileVO> fileStreams = new ArrayList<>();
        if (files == null) return result;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String name = file.getOriginalFilename();
            if (name == null) continue;
            // 验证文件名安全性
            if (SecurityUtil.illegalityFileName(name)) {
                log.warn("非法的文件名: {}", name);
                continue; // 跳过非法文件
            }
            if (name.toLowerCase().endsWith(".zip")) {
                try (ZipArchiveInputStream zis = new ZipArchiveInputStream(file.getInputStream())) {
                    ZipArchiveEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (!entry.isDirectory()) {
                            // 验证压缩包内文件名的安全性
                            String entryName = SecurityUtil.normalizeFilePath(entry.getName());
                            if (entryName == null) {
                                log.warn("压缩包中存在非法的文件路径: {}", entry.getName());
                                continue; // 跳过非法文件
                            }
                            try {
                                byte[] bytes = zis.readAllBytes();
                                fileStreams.add(new DocFileVO(entryName, bytes, ""));
                            } catch (java.io.EOFException e) {
                                log.warn("压缩包中文件 {} 读取不完整，已跳过: {}", entryName, e.getMessage());
                            }
                        }
                    }
                } catch (java.io.EOFException e) {
                    log.warn("ZIP文件 {} 格式异常或已损坏，部分文件可能未读取: {}", name, e.getMessage());
                }
            } else {
                fileStreams.add(new DocFileVO(name, file.getBytes(), file.getContentType()));
            }
        }
        for (DocFileVO fs : fileStreams) {
            TextSegmentVO vo = new TextSegmentVO();
            vo.setName(fs.getName());
            log.info("开始处理文件: {}, 大小: {} bytes", fs.getName(), fs.getBytes().length);
            long t1 = System.currentTimeMillis();
            String fileId = mongoFileService.storeFile(fs.getBytes(), fs.getName(), fs.getContentType());
            log.info("文件存储完成: {} -> {}, 耗时: {}ms", fs.getName(), fileId, System.currentTimeMillis() - t1);
            long t2 = System.currentTimeMillis();
            String text = documentParseService.extractText(fs.getName(), new ByteArrayInputStream(fs.getBytes()));
            log.info("文本提取完成: {}, 文本长度: {} 字符, 耗时: {}ms", fs.getName(), text.length(), System.currentTimeMillis() - t2);
            long t3 = System.currentTimeMillis();
            vo.setContent(documentSpiltService.split(text, patterns, limit, withFilter));
            log.info("切片完成: {}, 段落数: {}, 耗时: {}ms", fs.getName(), vo.getContent().size(), System.currentTimeMillis() - t3);
            vo.setSourceFileId(fileId);
            result.add(vo);
        }
        return result;
    }


    /**
     * 从 URL 创建 WEB 知识库文档
     * <p>
     * 对应 API：POST /admin/workspace/api/knowledge/{id}/document/web
     * 通过 Jsoup 抓取网页内容 → HTML 转 Markdown → 切片 → 写入数据库。
     * </p>
     *
     * @param knowledgeId    知识库ID
     * @param sourceUrlList  要抓取的网页 URL 列表
     * @param selector       CSS 选择器，用于提取页面指定区域（默认 body）
     */
    @Transactional
    public void createWebDoc(String knowledgeId, List<String> sourceUrlList, String selector) {
        for (String sourceUrl : sourceUrlList) {
            List<DocumentSimple> docs =documentWebService.getWebDocuments(sourceUrl, selector,false);
            batchCreateDocs(knowledgeId, KnowledgeType.WEB, docs);
        }
    }


    @Transactional
    public void syncWebDoc(String knowledgeId, String docId) {
        DocumentEntity doc = this.getById(docId);
        if (doc == null || doc.getMeta() == null) return;
        String sourceUrl = doc.getMeta().getString("sourceUrl");
        String selector = doc.getMeta().getString("selector");
        if (StringUtils.isAnyBlank(sourceUrl, selector)) return;
        deleteDocByIds(knowledgeId, List.of(docId));
        List<DocumentSimple> docs =documentWebService.getWebDocuments(sourceUrl, selector,false);
        batchCreateDocs(knowledgeId, KnowledgeType.WEB, docs);
    }



    public boolean batchGenerateRelated(String knowledgeId, GenerateProblemDTO dto) {
        eventPublisher.publishEvent(new GenerateProblemEvent(this, knowledgeId, dto.getDocumentIdList(), dto.getModelId(), dto.getNumber(),dto.getPrompt(), dto.getStateList()));
        return true;
    }

    public boolean downloadSourceFile(String docId, HttpServletResponse response) throws IOException {
        DocumentEntity doc = this.getById(docId);
        if (doc == null || doc.getMeta() == null) return false;

        String fileId = doc.getMeta().getString("sourceFileId");
        if (StringUtils.isBlank(fileId)) return false;

        try (InputStream in = mongoFileService.getStream(fileId)) {
            IoUtil.copy(in, response.getOutputStream());
            return true;
        }
    }

    public boolean replaceSourceFile(String id, String docId, MultipartFile file) throws IOException {
        DocumentEntity doc = this.getById(docId);
        if (doc == null) return false;
        String fileId = mongoFileService.storeFile(file);
        doc.setMeta(new JSONObject(Map.of("allow_download", true, "sourceFileId", fileId)));
        return this.updateById(doc);
    }

    // ===== 封装事件发布 =====
    private void publishDocumentIndexEvent(String knowledgeId, List<String> docIds, List<String> stateList) {
        if (!docIds.isEmpty()) {
            eventPublisher.publishEvent(new DocumentIndexEvent(this, knowledgeId, docIds, stateList));
        }
    }

    private boolean checkFileLimit(String id, MultipartFile[] files) {
        KnowledgeEntity knowledge = knowledgeMapper.selectById(id);
        if (Objects.isNull(knowledge)) {
            return false;
        }
        int fileSizeLimit = knowledge.getFileSizeLimit();
        int fileCountLimit = knowledge.getFileCountLimit();
        // 检查文件数量
        if (files == null || files.length == 0) {
            return false;
        }
        if (files.length > fileCountLimit) {
            return true;
        }
        // 预计算字节上限（避免循环内重复计算）
        long fileSizeLimitBytes = (long) fileSizeLimit * 1024 * 1024;
        // 收集超限文件的序号（从1开始）
        List<Integer> overLimitIndices = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            if (file != null && file.getSize() > fileSizeLimitBytes) {
                overLimitIndices.add(i + 1);
            }
        }
        // 若有超限文件，返回提示
        return !overLimitIndices.isEmpty();
    }


}