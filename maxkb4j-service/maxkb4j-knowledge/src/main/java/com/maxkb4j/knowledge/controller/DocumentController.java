package com.maxkb4j.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.maxkb4j.common.annotation.SaCheckPerm;
import com.maxkb4j.common.constant.AppConst;
import com.maxkb4j.common.api.R;
import com.maxkb4j.common.enums.PermissionEnum;
import com.maxkb4j.knowledge.consts.KnowledgeType;
import com.maxkb4j.knowledge.dto.*;
import com.maxkb4j.knowledge.entity.DocumentEntity;
import com.maxkb4j.knowledge.service.DocumentService;
import com.maxkb4j.knowledge.vo.DocumentVO;
import com.maxkb4j.common.domain.vo.KeyAndValueVO;
import com.maxkb4j.knowledge.vo.TextSegmentVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库文档管理控制器
 * <p>
 * 提供知识库中文档的全生命周期管理 API，包括文档导入、切片预览、
 * 批量创建、迁移、导出、刷新向量等操作。
 * </p>
 *
 * <h3>核心接口一览</h3>
 * <ul>
 *   <li><b>切片预览</b>：POST /knowledge/{id}/document/split —— 上传文件预览切片效果（不写入数据库）</li>
 *   <li><b>批量创建</b>：PUT /knowledge/{id}/document/batch_create —— 确认切片结果后正式入库</li>
 *   <li><b>WEB 文档</b>：POST /knowledge/{id}/document/web —— 通过 URL 抓取网页内容入库</li>
 *   <li><b>问答导入</b>：POST /knowledge/{id}/document/qa —— 批量导入 Excel/CSV 问答对</li>
 *   <li><b>表格导入</b>：POST /knowledge/{id}/document/table —— 批量导入 Excel/CSV 表格数据</li>
 *   <li><b>文档迁移</b>：PUT /knowledge/{id}/document/migrate/{targetKnowledgeId} —— 跨知识库迁移文档</li>
 *   <li><b>刷新向量</b>：PUT /knowledge/{id}/document/{docId}/refresh —— 重新生成文档向量</li>
 *   <li><b>导出</b>：GET /knowledge/{id}/document/{docId}/export —— 导出文档为 Excel</li>
 * </ul>
 *
 * <h3>文档入库完整链路</h3>
 * <pre>
 *   前端上传文件 → split（切片预览）→ 用户确认切片效果
 *     → batch_create（批量入库）
 *       → DocumentWriteService.batchCreateDocs() 写入 PostgreSQL
 *       → 发布 DocumentIndexEvent → DataIndexListener 异步向量化
 *         → CompositeStoreImpl 双写（PG向量 + MongoDB全文）
 * </pre>
 *
 * @author tarzan
 * @date 2024-12-25 16:00:15
 */
@RestController
@RequestMapping(AppConst.ADMIN_WORKSPACE_API)
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PostMapping("/knowledge/{id}/document/web")
    public void web(@PathVariable("id") String id, @RequestBody WebUrlDTO params) throws IOException {
        documentService.createWebDoc(id, params.getSourceUrlList(), params.getSelector());
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_SYNC)
    @PutMapping("/knowledge/{id}/document/{docId}/sync")
    public void sync(@PathVariable("id") String id, @PathVariable("docId") String docId) throws IOException {
        documentService.syncWebDoc(id, docId);
    }


    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PostMapping("/knowledge/{id}/document/qa")
    public void importQa(@PathVariable("id") String id, MultipartFile[] file) throws IOException {
        documentService.importQa(id, file);
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PostMapping("/knowledge/{id}/document/table")
    public void importTable(@PathVariable("id") String id, MultipartFile[] file) throws IOException {
        documentService.importTable(id, file);
    }

    /**
     * 【文档切片预览】上传文件并预览切片效果，不写入数据库
     * <p>
     * 这是知识库入库链路的前置预览步骤。用户上传文档后，系统解析文件提取文本，
     * 按指定的切片策略拆分段落，返回预览结果供用户确认。确认后前端会调用
     * {@code PUT /knowledge/{id}/document/batch_create} 接口正式入库。
     * </p>
     *
     * <h3>完整处理流程</h3>
     * <ol>
     *   <li><b>校验</b>：检查文件数量和大小是否超出知识库限制</li>
     *   <li><b>解压</b>：如果是 ZIP 文件，递归提取内部所有文件</li>
     *   <li><b>存储</b>：原始文件存入 MongoDB OSS，返回 fileId</li>
     *   <li><b>解析</b>：通过 {@link DocumentParseService#extractText} 根据扩展名匹配解析器提取文本</li>
     *   <li><b>切片</b>：通过 {@link DocumentSplitService#split} 按指定策略（智能/自定义正则）拆分段落</li>
     *   <li><b>返回</b>：包装为 {@link TextSegmentVO} 列表，包含文件名、切片段落、fileId</li>
     * </ol>
     *
     * <h3>入参说明</h3>
     * <ul>
     *   <li><b>patterns</b>：切片分隔符正则数组，为 null 时使用智能切片模式（按 Markdown 标题层级切分）</li>
     *   <li><b>limit</b>：单个段落的最大字符数限制，超出部分会递归再切</li>
     *   <li><b>withFilter</b>：是否对切片结果进行清洗（去除空行、特殊字符等）</li>
     * </ul>
     *
     * <h3>典型调用场景</h3>
     * <ol>
     *   <li>用户选择文件上传 → 调用此接口预览切片 → 用户调整切片参数 → 再次预览</li>
     *   <li>用户确认切片效果满意 → 调用 batch_create 正式入库 → 异步向量化</li>
     * </ol>
     *
     * @param id         知识库ID（路径参数）
     * @param file       上传的文件数组，支持 PDF/Word/Excel/Markdown/TXT/CSV/PPT/HTML/ZIP
     * @param patterns   自定义切片分隔符正则表达式数组（可选，null 则走智能切片）
     * @param limit      单个段落最大长度限制（可选）
     * @param withFilter 是否清洗切片结果（可选，默认 true）
     * @return 切片预览结果列表，每个元素包含文件名、切片段落文本、存储的 fileId
     * @throws IOException 文件读写异常
     */
    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PostMapping("/knowledge/{id}/document/split")
    public R<List<TextSegmentVO>> split(@PathVariable String id, MultipartFile[] file, String[] patterns, Integer limit, Boolean withFilter) throws IOException {
        return R.success(documentService.split(id,file, patterns, limit, withFilter));
    }


    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PutMapping("/knowledge/{id}/document/batch_create")
    public R<Boolean> createBatchDoc(@PathVariable("id") String id, @RequestBody List<DocumentSimple> docs) {
        return R.success(documentService.batchCreateDocs(id, KnowledgeType.BASE, docs));
    }

    @GetMapping("/knowledge/{id}/document/split_pattern")
    public R<List<KeyAndValueVO>> splitPattern(@PathVariable("id") String id) {
        return R.success(documentService.splitPattern());
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_READ)
    @GetMapping("/knowledge/{id}/document")
    public R<List<DocumentEntity>> listDocByKnowledgeId(@PathVariable String id) {
        return R.success(documentService.listDocByKnowledgeId(id));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_GENERATE)
    @PutMapping("/knowledge/{id}/document/batch_generate_related")
    public R<Boolean> batchGenerateRelated(@PathVariable String id, @RequestBody GenerateProblemDTO dto) {
        return R.success(documentService.batchGenerateRelated(id, dto));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_MIGRATE)
    @PutMapping("/knowledge/{id}/document/migrate/{targetKnowledgeId}")
    public R<Boolean> migrateDoc(@PathVariable("id") String sourceKnowledgeId, @PathVariable("targetKnowledgeId") String targetKnowledgeId, @RequestBody List<String> docIds) {
        return R.success(documentService.migrateDoc(sourceKnowledgeId, targetKnowledgeId, docIds));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_EDIT)
    @PutMapping("/knowledge/{id}/document/batch_hit_handling")
    public R<Boolean> batchHitHandling(@PathVariable String id, @RequestBody DatasetBatchHitHandlingDTO dto) {
        return R.success(documentService.batchHitHandling(id, dto));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_DELETE)
    @PutMapping("/knowledge/{id}/document/batch_delete")
    public R<Boolean> deleteBatchDocByDocIds(@PathVariable("id") String id, @RequestBody IdListDTO dto) {
        return R.success(documentService.deleteDocByIds(id, dto.getIdList()));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_READ)
    @GetMapping("/knowledge/{id}/document/{docId}")
    public R<DocumentEntity> getDocByDocId(@PathVariable String id, @PathVariable("docId") String docId) {
        return R.success(documentService.getById(docId));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_VECTOR)
    @PutMapping("/knowledge/{id}/document/{docId}/refresh")
    public R<Boolean> refresh(@PathVariable String id, @PathVariable("docId") String docId, @RequestBody DocumentEmbedDTO dto) {
        return R.success(documentService.embedByDocIds(id, List.of(docId), dto.getStateList()));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_VECTOR)
    @PutMapping("/knowledge/{id}/document/batch_refresh")
    public R<Boolean> batchRefresh(@PathVariable String id, @RequestBody DocumentEmbedDTO dto) {
        return R.success(documentService.embedByDocIds(id, dto.getIdList(), dto.getStateList()));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_EDIT)
    @PutMapping("/knowledge/{id}/document/{docId}/cancel_task")
    public R<Boolean> cancelTask(@PathVariable String id, @PathVariable("docId") String docId, @RequestBody DocumentEntity documentEntity) {
        return R.success(documentService.cancelTask(docId, documentEntity));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_EDIT)
    @PutMapping("/knowledge/{id}/document/{docId}")
    public R<DocumentEntity> updateDocByDocId(@PathVariable String id, @PathVariable("docId") String docId, @RequestBody DocumentEntity documentEntity) {
        return R.success(documentService.updateAndGetById(docId, documentEntity));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_DELETE)
    @DeleteMapping("/knowledge/{id}/document/{docId}")
    public R<Boolean> deleteDoc(@PathVariable("id") String id, @PathVariable("docId") String docId) {
        return R.success(documentService.deleteDocByIds(id,List.of(docId)));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_READ)
    @GetMapping("/knowledge/{id}/document/{current}/{size}")
    public R<IPage<DocumentVO>> pageDocByDatasetId(@PathVariable String id, @PathVariable("current") int current, @PathVariable("size") int size, DocQuery query) {
        return R.success(documentService.getDocByKnowledgeId(id, current, size, query));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_EXPORT)
    @GetMapping("/knowledge/{id}/document/{docId}/export")
    public void export(@PathVariable("id") String id, @PathVariable("docId") String docId, HttpServletResponse response) {
        documentService.exportExcelByDocId(docId, response);
    }


    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_EXPORT)
    @GetMapping("/knowledge/{id}/document/{docId}/export_zip")
    public void exportZip(@PathVariable("id") String id, @PathVariable("docId") String docId, HttpServletResponse response) throws IOException {
        documentService.exportExcelZipByDocId(docId, response);
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_DOWNLOAD)
    @GetMapping("/knowledge/{id}/document/{docId}/download_source_file")
    public R<String> downloadSourceFile(@PathVariable String id, @PathVariable String docId, HttpServletResponse response) throws IOException {
        boolean flag = documentService.downloadSourceFile(docId, response);
        return flag ? R.success() : R.fail("文件不存在, 仅支持手动上传的文档");
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_REPLACE)
    @PostMapping("/knowledge/{id}/document/{docId}/replace_source_file")
    public R<String> replaceSourceFile(@PathVariable String id, @PathVariable String docId, MultipartFile file) throws IOException {
        boolean flag = documentService.replaceSourceFile(id,docId,file);
        return flag ? R.success() : R.fail("文件不存在, 仅支持手动上传的文档");
    }


}
