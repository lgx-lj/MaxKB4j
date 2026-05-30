package com.maxkb4j.knowledge.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.maxkb4j.common.constant.ResourceType;
import com.maxkb4j.common.constant.RoleType;
import com.maxkb4j.common.domain.form.BaseField;
import com.maxkb4j.common.domain.form.LocalFileUpload;
import com.maxkb4j.common.domain.form.TextInputField;
import com.maxkb4j.common.util.BeanUtil;
import com.maxkb4j.common.util.DateTimeUtil;
import com.maxkb4j.common.util.StpKit;
import com.maxkb4j.core.event.CreateWebDocsEvent;
import com.maxkb4j.core.event.GenerateProblemEvent;
import com.maxkb4j.knowledge.dto.GenerateProblemDTO;
import com.maxkb4j.knowledge.dto.KnowledgeQuery;
import com.maxkb4j.knowledge.dto.WebKnowledgeDTO;
import com.maxkb4j.knowledge.entity.*;
import com.maxkb4j.knowledge.handler.KnowledgeExportHandler;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.maxkb4j.knowledge.handler.KnowledgeImportHandler;
import com.maxkb4j.knowledge.mapper.KnowledgeMapper;
import com.maxkb4j.knowledge.mapper.ParagraphMapper;
import com.maxkb4j.knowledge.mapper.ProblemMapper;
import com.maxkb4j.knowledge.mapper.ProblemParagraphMapper;
import com.maxkb4j.knowledge.store.IDataStore;
import com.maxkb4j.knowledge.vo.KnowledgeListVO;
import com.maxkb4j.knowledge.vo.KnowledgeVO;
import com.maxkb4j.system.constant.AuthTargetType;
import com.maxkb4j.system.entity.TargetResource;
import com.maxkb4j.system.service.IResourceMappingService;
import com.maxkb4j.user.service.IUserResourcePermissionService;
import com.maxkb4j.user.service.IUserService;
import com.maxkb4j.workflow.builder.NodeBuilder;
import com.maxkb4j.workflow.logic.LogicFlow;
import com.maxkb4j.workflow.model.KnowledgeParams;
import com.maxkb4j.workflow.model.KnowledgeWorkflow;
import com.maxkb4j.workflow.node.AbsNode;
import com.maxkb4j.workflow.service.IWorkFlowActuator;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.maxkb4j.workflow.enums.NodeType.DATA_SOURCE_WEB;


/**
 * 知识库核心服务
 * <p>
 * 负责知识库的创建、配置管理、文档上传编排、发布、导入导出等功能。
 * 是文档入库流程的总调度中心。
 * </p>
 *
 * @author tarzan
 * @date 2024-12-25 16:00:15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService extends ServiceImpl<KnowledgeMapper, KnowledgeEntity> implements IKnowledgeService {

    private final ProblemMapper problemMapper;
    private final ParagraphMapper paragraphMapper;
    private final ProblemParagraphMapper problemParagraphMapper;
    private final DocumentService documentService;
    private final IUserService userService;
    private final IUserResourcePermissionService userResourcePermissionService;
    private final ApplicationEventPublisher eventPublisher;
    private final IDataStore compositeStore;
    private final KnowledgeActionService knowledgeActionService;
    private final KnowledgeVersionService knowledgeVersionService;
    private final IWorkFlowActuator workFlowActuator;
    private final KnowledgeExportHandler knowledgeExportHandler;
    
    @Lazy
    @Autowired
    private KnowledgeImportHandler knowledgeImportHandler;
    private final NodeBuilder nodeBuilder;
    private final IResourceMappingService resourceMappingService;
    private final TaskExecutor workflowExecutor;


    public IPage<KnowledgeVO> selectKnowledgePage(Page<KnowledgeVO> knowledgePage, KnowledgeQuery query) {
        String loginId = StpKit.ADMIN.getLoginIdAsString();
        List<String> targetIds = userResourcePermissionService.getTargetIds(AuthTargetType.KNOWLEDGE, loginId);
        Set<String> role = userService.getRoleById(loginId);
        query.setIsAdmin(role.contains(RoleType.ADMIN));
        query.setTargetIds(targetIds);
        IPage<KnowledgeVO> page = baseMapper.selectKnowledgePage(knowledgePage, query);
        Map<String, String> nicknameMap = userService.getNicknameMap();
        page.getRecords().forEach(vo -> vo.setNickname(nicknameMap.get(vo.getUserId())));
        return page;
    }


    public KnowledgeVO getKnowledgeById(String id) {
        KnowledgeEntity entity = baseMapper.selectById(id);
        if (Objects.isNull(entity)) {
            return null;
        }
        return BeanUtil.copy(entity, KnowledgeVO.class);
    }


    public List<ParagraphEntity> getParagraphByProblemId(String problemId) {
        List<ProblemParagraphEntity> list = problemParagraphMapper.selectList(Wrappers.<ProblemParagraphEntity>lambdaQuery().select(ProblemParagraphEntity::getParagraphId).eq(ProblemParagraphEntity::getProblemId, problemId));
        if (!CollectionUtils.isEmpty(list)) {
            List<String> paragraphIds = list.stream().map(ProblemParagraphEntity::getParagraphId).toList();
            return paragraphMapper.selectByIds(paragraphIds);
        }
        return Collections.emptyList();
    }


    @Transactional
    public Boolean deleteById(String id) {
        problemParagraphMapper.delete(Wrappers.<ProblemParagraphEntity>lambdaQuery().eq(ProblemParagraphEntity::getKnowledgeId, id));
        problemMapper.delete(Wrappers.<ProblemEntity>lambdaQuery().eq(ProblemEntity::getKnowledgeId, id));
        paragraphMapper.delete(Wrappers.<ParagraphEntity>lambdaQuery().eq(ParagraphEntity::getKnowledgeId, id));
        documentService.remove(Wrappers.<DocumentEntity>lambdaQuery().eq(DocumentEntity::getKnowledgeId, id));
        knowledgeVersionService.lambdaQuery().eq(KnowledgeVersionEntity::getKnowledgeId, id);
        knowledgeActionService.lambdaQuery().eq(KnowledgeActionEntity::getKnowledgeId, id);
        userResourcePermissionService.remove(AuthTargetType.KNOWLEDGE, id);
        compositeStore.deleteByKnowledgeId(id);
        resourceMappingService.deleteBySourceId(ResourceType.KNOWLEDGE, id);
        return this.removeById(id);
    }


    // 公共方法：根据 knowledgeId 获取文档列表
    private List<DocumentEntity> getDocumentsByKnowledgeId(String knowledgeId) {
        return documentService.list(Wrappers.<DocumentEntity>lambdaQuery().eq(DocumentEntity::getKnowledgeId, knowledgeId));
    }

    // 根据 ID 导出 ZIP
    public void exportExcelZip(String id, HttpServletResponse response) throws IOException {
        KnowledgeEntity dataset = this.getById(id);
        if (dataset == null) {
            throw new IllegalArgumentException("未找到知识库 ID: " + id);
        }
        List<DocumentEntity> docs = getDocumentsByKnowledgeId(id);
        if (docs == null || docs.isEmpty()) {
            throw new IllegalArgumentException("文档列表为空，无法导出");
        }

        knowledgeExportHandler.writeExcelToZipAndResponse(docs, dataset.getName(), response);
    }

    // 直接导出 Excel（不压缩）
    public void exportExcel(String id, HttpServletResponse response) throws IOException {
        KnowledgeEntity dataset = this.getById(id);
        if (dataset == null) {
            throw new IllegalArgumentException("未找到知识库 ID: " + id);
        }
        List<DocumentEntity> docs = getDocumentsByKnowledgeId(id);
        knowledgeExportHandler.setExcelResponseHeader(response, dataset.getName());
        knowledgeExportHandler.writeMultiSheetExcel(response.getOutputStream(), docs);
    }

    // 导出知识库ZIP包（包含knowledge.json和knowledge.xlsx）
    public void exportKnowledge(String id, HttpServletResponse response) throws IOException {
        KnowledgeEntity knowledge = this.getById(id);
        if (knowledge == null) {
            throw new IllegalArgumentException("未找到知识库 ID: " + id);
        }
        List<DocumentEntity> docs = getDocumentsByKnowledgeId(id);
        knowledgeExportHandler.exportKnowledgeZip(docs, knowledge.getName(), knowledge.getDesc(),
                knowledge.getType(), knowledge.getMeta(), knowledge.getFileSizeLimit(),
                knowledge.getFileCountLimit(), response);
    }

    // 导入知识库ZIP包
    public KnowledgeEntity importKnowledgeZip(MultipartFile file) throws IOException {
        return knowledgeImportHandler.importKnowledgeFromZip(file);
    }


    public List<KnowledgeEntity> list(String userId, String folderId) {
        return this.lambdaQuery().eq(KnowledgeEntity::getUserId, userId).eq(KnowledgeEntity::getFolderId, folderId).list();
    }

    @Transactional
    public KnowledgeEntity createKnowledge(KnowledgeEntity knowledge) {
        knowledge.setMeta(new JSONObject());
        knowledge.setUserId(StpKit.ADMIN.getLoginIdAsString());
        if (knowledge.getWorkFlow() == null) {
            knowledge.setWorkFlow(new JSONObject());
        }
        this.save(knowledge);
        userResourcePermissionService.ownerSave(AuthTargetType.KNOWLEDGE, knowledge.getId(), knowledge.getUserId());
        saveResourceMappings(knowledge);
        return knowledge;
    }

    @Transactional
    public KnowledgeEntity createKnowledgeWeb(WebKnowledgeDTO knowledge) {
        createKnowledge(knowledge);
        // 使用事件驱动异步处理，确保事务提交后再执行
        eventPublisher.publishEvent(new CreateWebDocsEvent(
            this,
            knowledge.getId(),
            knowledge.getSourceUrl(),
            knowledge.getSelector()
        ));
        return knowledge;
    }


    public boolean embeddingKnowledge(String knowledgeId) {
        List<DocumentEntity> documents = documentService.lambdaQuery().select(DocumentEntity::getId).eq(DocumentEntity::getKnowledgeId, knowledgeId).list();
        documentService.embedByDocIds(knowledgeId, documents.stream().map(DocumentEntity::getId).toList(), List.of("0", "1", "2", "3", "n"));
        return true;
    }

    public List<KnowledgeListVO> listKnowledge() {
        String userId = StpKit.ADMIN.getLoginIdAsString();
        Set<String> role = userService.getRoleById(userId);
        List<KnowledgeEntity> list;
        if (role.contains(RoleType.ADMIN)) {
            list = this.lambdaQuery().select(KnowledgeEntity::getId, KnowledgeEntity::getName, KnowledgeEntity::getDesc, KnowledgeEntity::getType, KnowledgeEntity::getFolderId).orderByDesc(KnowledgeEntity::getCreateTime).list();
        } else {
            List<String> targetIds = userResourcePermissionService.getTargetIds(AuthTargetType.KNOWLEDGE, userId);
            if (targetIds.isEmpty()) {
                return Collections.emptyList();
            }
            list = this.lambdaQuery().select(KnowledgeEntity::getId, KnowledgeEntity::getName, KnowledgeEntity::getDesc, KnowledgeEntity::getType, KnowledgeEntity::getFolderId).in(KnowledgeEntity::getId, targetIds).orderByDesc(KnowledgeEntity::getCreateTime).list();
        }
        return BeanUtil.copyList(list, KnowledgeListVO.class);
    }

    public Boolean generateRelated(String knowledgeId, GenerateProblemDTO dto) {
        eventPublisher.publishEvent(new GenerateProblemEvent(this, knowledgeId, dto.getDocumentIdList(), dto.getModelId(),dto.getNumber(), dto.getPrompt(), dto.getStateList()));
        return true;
    }

    public KnowledgeEntity updateDatasetWorkflow(String id, KnowledgeEntity dataset) {
        dataset.setId(id);
        return this.updateById(dataset) ? dataset : null;
    }

    public List<BaseField> datasourceFormList(String nodeType, JSONObject params) {
        if (DATA_SOURCE_WEB.getKey().equals(nodeType)) {
            BaseField field1 = new TextInputField("Web 根地址", "sourceUrl", "请输入 Web 根地址", true);
            BaseField field2 = new TextInputField("选择器", "selector", "默认为 body，可输入 .classname/#idname/tagname", false);
            return List.of(field1, field2);
        } else {
            BaseField localFileUpload = new LocalFileUpload(50, 100, List.of("TXT", "DOCX", "PDF", "HTML", "XLS", "XLSX", "CSV"));
            if (params == null) {
                return List.of(localFileUpload);
            }
            JSONObject node = params.getJSONObject("node");
            if (node == null) {
                return List.of(localFileUpload);
            }
            JSONObject properties = node.getJSONObject("properties");
            if (properties == null) {
                return List.of(localFileUpload);
            }
            JSONObject nodeData = properties.getJSONObject("nodeData");
            if (nodeData == null) {
                return List.of(localFileUpload);
            }
            Integer fileCountLimit = nodeData.getInteger("fileCountLimit");
            Integer fileSizeLimit = nodeData.getInteger("fileSizeLimit");
            List<String> fileTypeList = nodeData.getJSONArray("fileTypeList").toJavaList(String.class);
            return List.of(new LocalFileUpload(fileCountLimit, fileSizeLimit, fileTypeList));
        }
    }


    /**
     * 获取知识库工作流配置
     * <p>
     * debug 模式使用草稿状态的工作流（knowledge.work_flow），
     * 正式模式使用最新发布版本的工作流快照（knowledge_workflow_version.work_flow）。
     * </p>
     *
     * @param id    知识库ID
     * @param debug true=调试模式（读草稿），false=正式模式（读已发布版本）
     * @return 工作流配置 JSON，包含节点列表和连线关系
     */
    public JSONObject getKnowledgeWorkFlow(String id, boolean debug) {
        JSONObject workFlow = null;
        if (debug) {
            KnowledgeEntity knowledge = baseMapper.selectById(id);
            if (knowledge != null) {
                workFlow = knowledge.getWorkFlow();
            }
        } else {
            KnowledgeVersionEntity KnowledgeVersion = knowledgeVersionService.lambdaQuery().eq(KnowledgeVersionEntity::getKnowledgeId, id).orderByDesc(KnowledgeVersionEntity::getCreateTime).last("limit 1").one();
            if (KnowledgeVersion != null) {
                workFlow = KnowledgeVersion.getWorkFlow();
            }
        }
        return workFlow;
    }

    /**
     * 【核心方法】上传文档到知识库，异步执行完整处理链路
     * <p>
     * 这是文档入库的总调度方法，由 KnowledgeController.uploadDocument() 调用。
     * 整个流程为异步执行，方法自身不阻塞——它创建追踪记录后立即返回，
     * 实际处理在工作流线程池中异步完成。
     * </p>
     *
     * <h3>完整处理链路</h3>
     * <ol>
     *   <li><b>工作流编排</b>：获取知识库的工作流配置（发布版本或草稿），构建 LogicFlow</li>
     *   <li><b>创建追踪记录</b>：写入 knowledge_action 表（state=STARTED），返回给客户端轮询</li>
     *   <li><b>异步执行</b>：通过 CompletableFuture.runAsync 在线程池中执行工作流</li>
     *   <li><b>工作流节点链</b>：
     *     <ul>
     *       <li>数据源节点：接收文件/URL → DocumentParseService 解析 → 提取文本</li>
     *       <li>切片节点：DocumentSplitService.split() → 按标题/句子拆分为段落</li>
     *       <li>写入节点：DocumentWriteService.batchCreateDocs() → 写入 document/paragraph/problem 表</li>
     *     </ul>
     *   </li>
     *   <li><b>事件驱动向量化</b>：写入完成后发布 DocumentIndexEvent →
     *     DataIndexListener（@Async）异步生成向量 → CompositeStoreImpl 双写 PG + MongoDB</li>
     * </ol>
     *
     * @param id     知识库ID
     * @param params 上传参数（文件信息、切片模式、选择器等）
     * @param debug  true=调试模式（读取草稿工作流），false=正式模式（读取已发布版本）
     * @return 操作追踪记录，id 为任务ID，state=STARTED，客户端可轮询获取进度
     */
    public KnowledgeActionEntity uploadDocument(String id, KnowledgeParams params, boolean debug) {
        JSONObject knowledgeWorkFlow = getKnowledgeWorkFlow(id, debug);
        if (knowledgeWorkFlow == null) {
            throw new IllegalArgumentException("未找到知识库 ID: " + id);
        }
        KnowledgeActionEntity knowledgeAction = new KnowledgeActionEntity();
        knowledgeAction.setKnowledgeId(id);
        knowledgeAction.setState("STARTED");
        knowledgeAction.setDetails(new JSONObject());
        knowledgeAction.setRunTime(0F);
        JSONObject meta = new JSONObject();
        meta.put("userId", StpKit.ADMIN.getLoginIdAsString());
        meta.put("username", StpKit.ADMIN.getExtra("username"));
        knowledgeAction.setMeta(meta);
        knowledgeActionService.save(knowledgeAction);
        LogicFlow logicFlow = LogicFlow.newInstance(knowledgeWorkFlow);
        List<AbsNode> nodes = logicFlow.getNodes().stream().map(nodeBuilder::getNode).filter(Objects::nonNull).toList();
        params.setActionId(knowledgeAction.getId());
        params.setKnowledgeId(id);
        params.setDebug(debug);
        KnowledgeWorkflow workflow = new KnowledgeWorkflow(nodes, logicFlow.getEdges(), params);
        CompletableFuture.runAsync(() -> workFlowActuator.execute(workflow),workflowExecutor);
        return knowledgeAction;
    }

    public KnowledgeActionEntity action(String actionId) {
        return knowledgeActionService.getById(actionId);
    }

    public IPage<KnowledgeActionEntity> actionPage(String id, int current, int size, String username, String state) {
        Page<KnowledgeActionEntity> actionPage = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeActionEntity> query = Wrappers.lambdaQuery();
        if (!StringUtils.isEmpty(username)) {
            query.eq(KnowledgeActionEntity::getMeta, username);
        }
        if (!StringUtils.isEmpty(state)) {
            query.eq(KnowledgeActionEntity::getState, state);
        }
        query.eq(KnowledgeActionEntity::getKnowledgeId, id);
        query.orderByDesc(KnowledgeActionEntity::getCreateTime);
        return knowledgeActionService.pageList(actionPage, username, state);
    }

    @Transactional
    public Boolean publish(String id) {
        KnowledgeEntity knowledge = new KnowledgeEntity();
        knowledge.setId(id);
        knowledge.setIsPublish(true);
        this.updateById(knowledge);
        knowledge = this.getById(id);
        KnowledgeVersionEntity knowledgeVersion = new KnowledgeVersionEntity();
        knowledgeVersion.setKnowledgeId(id);
        knowledgeVersion.setName(DateTimeUtil.now());
        knowledgeVersion.setWorkFlow(knowledge.getWorkFlow());
        knowledgeVersion.setPublishUserId(StpKit.ADMIN.getLoginIdAsString());
        knowledgeVersion.setPublishUserName((String) StpKit.ADMIN.getExtra("username"));
        return knowledgeVersionService.save(knowledgeVersion);
    }

    public List<KnowledgeVersionEntity> knowledgeVersion(String id) {
        return knowledgeVersionService.lambdaQuery().eq(KnowledgeVersionEntity::getKnowledgeId, id).list();
    }

    public Boolean knowledgeVersion(String versionId, KnowledgeVersionEntity knowledgeVersion) {
        knowledgeVersion.setId(versionId);
        return knowledgeVersionService.updateById(knowledgeVersion);
    }

    @Override
    public List<KnowledgeEntity> listNameAndDescByIds(List<String> knowledgeIds) {
        return this.lambdaQuery().select(KnowledgeEntity::getId, KnowledgeEntity::getName, KnowledgeEntity::getDesc).in(KnowledgeEntity::getId, knowledgeIds).list();
    }


    public void saveResourceMappings(KnowledgeEntity knowledge) {
        List<String> modelIds = new ArrayList<>();
        modelIds.add(knowledge.getEmbeddingModelId());
        List<String> toolIds = new ArrayList<>();
        JSONObject workFlow = knowledge.getWorkFlow();
        if (workFlow != null && workFlow.containsKey("nodes")) {
            JSONArray nodes = workFlow.getJSONArray("nodes");
            if (nodes != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    JSONObject node = nodes.getJSONObject(i);
                    JSONObject properties = node.getJSONObject("properties");
                    if (properties != null && properties.containsKey("nodeData")) {
                        JSONObject nodeData = properties.getJSONObject("nodeData");
                        if (nodeData != null && nodeData.containsKey("toolLibId")) {
                            toolIds.add(nodeData.getString("toolLibId"));
                        }
                        if (nodeData != null && nodeData.containsKey("mcpToolId")) {
                            toolIds.add(nodeData.getString("mcpToolId"));
                        }
                        if (nodeData != null && nodeData.containsKey("toolIds")) {
                            toolIds.addAll((Collection<? extends String>) nodeData.get("toolIds"));
                        }
                        if (nodeData != null && nodeData.containsKey("modelId")) {
                            modelIds.add(nodeData.getString("modelId"));
                        }
                        if (nodeData != null && nodeData.containsKey("ttsModelId")) {
                            modelIds.add(nodeData.getString("ttsModelId"));
                        }
                        if (nodeData != null && nodeData.containsKey("sttModelId")) {
                            modelIds.add(nodeData.getString("sttModelId"));
                        }
                        if (nodeData != null && nodeData.containsKey("rerankerModelId")) {
                            modelIds.add(nodeData.getString("rerankerModelId"));
                        }
                    }
                }
            }
        }
        List<TargetResource> targets = new ArrayList<>();
        targets.addAll(toolIds.stream().map(id -> new TargetResource(id, ResourceType.TOOL)).toList());
        targets.addAll(modelIds.stream().filter(Objects::nonNull).map(id -> new TargetResource(id, ResourceType.MODEL)).toList());
        resourceMappingService.relation(ResourceType.KNOWLEDGE, knowledge.getId(), targets);
    }

    public Boolean delMulApplication(List<String> idList) {
        Boolean result = false;
        for (String id : idList) {
            result = deleteById(id);
        }
        return result;
    }
}
