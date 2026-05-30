package com.maxkb4j.knowledge.controller;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maxkb4j.common.annotation.SaCheckPerm;
import com.maxkb4j.common.constant.AppConst;
import com.maxkb4j.common.api.R;
import com.maxkb4j.common.domain.form.BaseField;
import com.maxkb4j.common.enums.PermissionEnum;
import com.maxkb4j.knowledge.consts.KnowledgeType;
import com.maxkb4j.knowledge.dto.DataSearchDTO;
import com.maxkb4j.knowledge.dto.GenerateProblemDTO;
import com.maxkb4j.knowledge.dto.WebKnowledgeDTO;
import com.maxkb4j.knowledge.dto.KnowledgeQuery;
import com.maxkb4j.knowledge.entity.KnowledgeActionEntity;
import com.maxkb4j.knowledge.entity.KnowledgeEntity;
import com.maxkb4j.knowledge.entity.KnowledgeVersionEntity;
import com.maxkb4j.knowledge.service.KnowledgeService;
import com.maxkb4j.knowledge.service.RetrieveService;
import com.maxkb4j.knowledge.vo.KnowledgeListVO;
import com.maxkb4j.knowledge.vo.KnowledgeVO;
import com.maxkb4j.knowledge.vo.ParagraphVO;
import com.maxkb4j.workflow.model.KnowledgeParams;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库管理控制器
 * <p>
 * 提供知识库的 CRUD、文档上传、向量化、发布、导入导出等全部 REST API。
 * 所有接口均以 {@code /admin/workspace/api} 为前缀。
 * </p>
 *
 * <h3>核心入库链路</h3>
 * <p>
 * 通用知识库上传文档的入口是 {@link #uploadDocument(String, KnowledgeParams)}：
 * </p>
 * <pre>
 *   POST /admin/workspace/api/knowledge/{id}/upload_document
 *     → KnowledgeService.uploadDocument()
 *       → 获取工作流配置 → 构建 LogicFlow → CompletableFuture.runAsync 异步执行
 *         → 工作流节点依次执行（解析→切片→写入→向量化）
 *           → DocumentParser 解析文件提取文本
 *           → DocumentSplitService 切片（按标题/句子拆分）
 *           → DocumentWriteService.batchCreateDocs() 写入数据库
 *           → 发布 DocumentIndexEvent → DataIndexListener 异步向量化
 *             → CompositeStoreImpl 双写（PG向量 + MongoDB全文）
 * </pre>
 *
 * @author tarzan
 * @date 2024-12-25 16:00:15
 */
@RestController
@RequestMapping(AppConst.ADMIN_WORKSPACE_API)
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final RetrieveService retrieveService;


    @SaCheckPerm(PermissionEnum.KNOWLEDGE_READ)
    @GetMapping("/knowledge")
    public R<List<KnowledgeListVO>> listKnowledge(String folderId) {
        return R.success(knowledgeService.listKnowledge());
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_CREATE)
    @PostMapping("/knowledge/base")
    public R<KnowledgeEntity> createKnowledgeBase(@RequestBody KnowledgeEntity knowledge) {
        knowledge.setType(KnowledgeType.BASE);
        return R.success(knowledgeService.createKnowledge(knowledge));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_CREATE)
    @PostMapping("/knowledge/web")
    public R<KnowledgeEntity> createKnowledgeWeb(@RequestBody WebKnowledgeDTO knowledge) {
        knowledge.setType(KnowledgeType.WEB);
        return R.success(knowledgeService.createKnowledgeWeb(knowledge));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_CREATE)
    @PostMapping("/knowledge/workflow")
    public R<KnowledgeEntity> createKnowledgeWorkflow(@RequestBody KnowledgeEntity knowledge) {
        knowledge.setType(KnowledgeType.WORKFLOW);
        return R.success(knowledgeService.createKnowledge(knowledge));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_WORKFLOW_EDIT)
    @PutMapping("/knowledge/{id}/workflow")
    public R<KnowledgeEntity> updateDatasetWorkflow(@PathVariable String id,@RequestBody KnowledgeEntity knowledge) {
        return R.success(knowledgeService.updateDatasetWorkflow(id,knowledge));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_READ)
    @GetMapping("/knowledge/{id}")
    public R<KnowledgeVO> getKnowledgeById(@PathVariable("id") String id) {
        return R.success(knowledgeService.getKnowledgeById(id));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_HIT_TEST_READ)
    @PutMapping("/knowledge/{id}/hit_test")
    public R<List<ParagraphVO>> hitTest(@PathVariable("id") String id, @RequestBody DataSearchDTO dto) {
        return R.success(retrieveService.paragraphSearch(List.of(id), dto));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_EDIT)
    @PutMapping("/knowledge/{id}")
    public R<KnowledgeEntity> updatedKnowledge(@PathVariable("id") String id, @RequestBody KnowledgeEntity datasetEntity) {
        datasetEntity.setId(id);
        knowledgeService.updateById(datasetEntity);
        knowledgeService.saveResourceMappings(datasetEntity);
        return R.success(datasetEntity);
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_VECTOR)
    @PutMapping("/knowledge/{id}/embedding")
    public R<Boolean> embeddingKnowledge(@PathVariable("id") String id) {
        return R.success(knowledgeService.embeddingKnowledge(id));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_PROBLEM_RELATE)
    @PutMapping("/knowledge/{id}/generate_related")
    public R<Boolean> generateRelated(@PathVariable String id, @RequestBody GenerateProblemDTO dto) {
        return R.success(knowledgeService.generateRelated(id, dto));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DELETE)
    @DeleteMapping("/knowledge/{id}")
    public R<Boolean> deleteKnowledgeId(@PathVariable("id") String id) {
        return R.success(knowledgeService.deleteById(id));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_BATCH_DELETE)
    @DeleteMapping("/knowledge/batchDelete")
    public R<Boolean> delMulKnowledge(@RequestParam("idList") List<String> idList) {
        return R.success(knowledgeService.delMulApplication(idList));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_READ)
    @GetMapping("/knowledge/{current}/{size}")
    public R<IPage<KnowledgeVO>> knowledgePage(@PathVariable("current") int current, @PathVariable("size") int size, KnowledgeQuery query) {
        Page<KnowledgeVO> knowledgePage = new Page<>(current, size);
        return R.success(knowledgeService.selectKnowledgePage(knowledgePage, query));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_EXPORT)
    @GetMapping("/knowledge/{id}/export")
    public void export(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        knowledgeService.exportExcel(id, response);
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_EXPORT)
    @GetMapping("/knowledge/{id}/export_zip")
    public void exportZip(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        knowledgeService.exportExcelZip(id, response);
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_EXPORT)
    @GetMapping("/knowledge/{id}/export_knowledge")
    public void exportKnowledge(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        knowledgeService.exportKnowledge(id, response);
    }

    @PostMapping("/knowledge/import_knowledge")
    public R<KnowledgeEntity> importKnowledge(@RequestParam("file") MultipartFile file) throws IOException {
        return R.success(knowledgeService.importKnowledgeZip(file));
    }

    @SaCheckPerm(PermissionEnum.KNOWLEDGE_DOCUMENT_CREATE)
    @PostMapping("/knowledge/{id}/datasource/local/{nodeType}/form_list")
    public R<List<BaseField>> datasourceFormList(@PathVariable("id") String id, @PathVariable("nodeType")String nodeType, @RequestBody JSONObject params) {
      return R.success(knowledgeService.datasourceFormList(nodeType,params));
    }

    /**
     * 调试模式上传文档
     * <p>
     * 与 {@link #uploadDocument(String, KnowledgeParams)} 逻辑相同，但使用知识库草稿状态
     * 的工作流配置（而非已发布的版本快照），用于工作流调试场景。
     * </p>
     *
     * @param id     知识库ID
     * @param params 上传参数，包含文件列表、切片配置等
     * @return 异步操作追踪记录，客户端轮询此记录获取处理进度
     */
    @PostMapping("/knowledge/{id}/debug")
    public R<KnowledgeActionEntity> debug(@PathVariable("id") String id, @RequestBody KnowledgeParams params) {
        return R.success(knowledgeService.uploadDocument(id, params, true));
    }

    @PutMapping("/knowledge/{id}/publish")
    public R<Boolean> publish(@PathVariable("id") String id) {
        return R.success(knowledgeService.publish(id));
    }
    @GetMapping("/knowledge/{id}/knowledge_version")
    public R<List<KnowledgeVersionEntity>> knowledgeVersion(@PathVariable("id") String id) {
        return R.success(knowledgeService.knowledgeVersion(id));
    }

    @PutMapping("/knowledge/{id}/knowledge_version/{versionId}")
    public R<Boolean> knowledgeVersion(@PathVariable("id") String id,@PathVariable("versionId") String versionId,@RequestBody KnowledgeVersionEntity knowledgeVersionEntity) {
        return R.success(knowledgeService.knowledgeVersion(versionId,knowledgeVersionEntity));
    }

    @GetMapping("/knowledge/{id}/action/{current}/{size}")
    public R<IPage<KnowledgeActionEntity>> actionPage(@PathVariable("id") String id,@PathVariable("current") int current, @PathVariable("size") int size, String username, String state) {
        return R.success(knowledgeService.actionPage(id,current,size,username,state));
    }

    /**
     * 【核心入口】通用知识库上传文档
     * <p>
     * 这是知识库文档入库的最主要入口，支持上传 PDF/Word/Excel/TXT/PPT/CSV/HTML/MD 等多种文件格式。
     * 整个处理链路为异步执行，接口立即返回 KnowledgeActionEntity，客户端通过
     * {@code GET /admin/workspace/api/knowledge/{id}/action/{actionId}} 轮询处理进度。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>获取知识库已发布版本的工作流配置</li>
     *   <li>创建操作追踪记录（state=STARTED）</li>
     *   <li>构建 LogicFlow 工作流，包含解析节点、切片节点、写入节点等</li>
     *   <li>通过 CompletableFuture.runAsync 异步执行工作流</li>
     *   <li>工作流执行过程：文件解析 → 文本切片 → 写入数据库 → 异步向量化 → 双写存储</li>
     * </ol>
     *
     * @param id     知识库ID，路径参数，对应 knowledge 表主键
     * @param params 上传参数，包含文件信息、切片模式等
     * @return 操作追踪记录，id 字段为任务追踪ID，state 记录当前状态
     */
    @PostMapping("/knowledge/{id}/upload_document")
    public R<KnowledgeActionEntity> uploadDocument(@PathVariable("id") String id, @RequestBody KnowledgeParams params) {
        return R.success(knowledgeService.uploadDocument(id, params, false));
    }

    @GetMapping("/knowledge/{id}/action/{actionId}")
    public R<KnowledgeActionEntity> action(@PathVariable("id") String id, @PathVariable("actionId") String actionId) {
        return R.success(knowledgeService.action(actionId));
    }






}
