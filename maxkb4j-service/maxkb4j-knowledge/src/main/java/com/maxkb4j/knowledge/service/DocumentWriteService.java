package com.maxkb4j.knowledge.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.maxkb4j.core.event.DocumentIndexEvent;
import com.maxkb4j.knowledge.dto.DocumentSimple;
import com.maxkb4j.knowledge.entity.DocumentEntity;
import com.maxkb4j.knowledge.entity.ParagraphEntity;
import com.maxkb4j.knowledge.entity.ProblemEntity;
import com.maxkb4j.knowledge.entity.ProblemParagraphEntity;
import com.maxkb4j.knowledge.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档写入服务 —— 将切片后的段落批量写入数据库
 * <p>
 * 这是知识库入库链路中"持久化"环节的核心实现。接收 DocumentSplitService 切分好的
 * DocumentSimple 列表，在一个事务中完成以下数据库写入操作：
 * </p>
 * <ol>
 *   <li>写入 document 表 —— 创建文档记录，初始状态 "nn0"</li>
 *   <li>写入 paragraph 表 —— 每个切片创建一条段落记录</li>
 *   <li>写入 problem 表 —— QA 文档中的问题</li>
 *   <li>写入 problem_paragraph_mapping 表 —— 问题与段落的映射关系</li>
 * </ol>
 * <p>
 * 事务提交后发布 DocumentIndexEvent，触发异步向量化。
 * </p>
 *
 * @author tarzan
 * @date 2024-12-25 17:00:26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentWriteService extends ServiceImpl<DocumentMapper, DocumentEntity> {

    private final ParagraphService paragraphService;
    private final ProblemService problemService;
    private final ProblemParagraphService problemParagraphService;
    private final ApplicationEventPublisher eventPublisher;


    /**
     * 【核心方法】批量创建文档及其段落、问题
     * <p>
     * 在一个事务中完成文档、段落、问题、问题段落映射的批量写入。
     * 写入完成后发布 DocumentIndexEvent，触发 DataIndexListener 异步向量化。
     * </p>
     *
     * <h3>处理步骤</h3>
     * <ol>
     *   <li>遍历 DocumentSimple 列表，为每个文档创建 DocumentEntity（状态 "nn0"）</li>
     *   <li>遍历每个文档的 ParagraphSimple 列表，创建 ParagraphEntity（状态 "nn0"）</li>
     *   <li>处理段落关联的问题列表，已存在的问题复用 ID，新问题创建记录</li>
     *   <li>建立 problem-paragraph 多对多映射关系</li>
     *   <li>批量 INSERT：document → paragraph → problem → problem_paragraph_mapping</li>
     *   <li>事务提交后发布 DocumentIndexEvent（参数 stateList=["0"]）</li>
     * </ol>
     *
     * @param knowledgeId   知识库ID
     * @param knowledgeType 知识库类型（0=通用/1=WEB/2=工作流）
     * @param docs          待写入的文档列表（已切分好段落）
     * @return true=写入成功
     */
    @Transactional
    public boolean batchCreateDocs(String knowledgeId, int knowledgeType, List<DocumentSimple> docs) {
        // 空集合快速返回，避免不必要的数据库查询
        if (CollectionUtils.isEmpty(docs)) {
            return true;
        }
        // 查询知识库下已有问题列表，用于后续问题去重
        List<ProblemEntity> knowledgeProblems = problemService.lambdaQuery()
                .eq(ProblemEntity::getKnowledgeId, knowledgeId)
                .list();
        // 初始化四个容器，分别收集待批量插入的文档、段落、问题段落映射、新问题
        List<DocumentEntity> documentEntities = new ArrayList<>();
        List<ParagraphEntity> paragraphEntities = new ArrayList<>();
        List<ProblemParagraphEntity> problemParagraphs = new ArrayList<>();
        List<ProblemEntity> problemEntities = new ArrayList<>();
        for (DocumentSimple d : docs) {
            // 创建文档实体，ID在构造函数中通过 IdWorker 生成
            DocumentEntity doc = new DocumentEntity(knowledgeId, d.getName(), knowledgeType);
            // 使用 AtomicInteger 在 lambda 遍历中累加段落内容字符数
            AtomicInteger docCharLength = new AtomicInteger();
            if (!CollectionUtils.isEmpty(d.getParagraphs())) {
                for (var p : d.getParagraphs()) {
                    // 空内容统一转为空字符串，避免 NPE
                    String content = p.getContent() != null ? p.getContent() : "";
                    ParagraphEntity paragraph = paragraphService.createParagraph(knowledgeId, doc.getId(), p.getTitle(), content, null);
                    paragraphEntities.add(paragraph);
                    // 累加当前段落的字符数到文档总字符数
                    docCharLength.addAndGet(content.length());
                    if (!CollectionUtils.isEmpty(p.getProblemList())) {
                        for (String problem : p.getProblemList()) {
                            // 去除首尾空白，过滤纯空白字符串
                            problem = problem.trim();
                            if (problem.isEmpty()) continue;
                            String problemId = IdWorker.get32UUID();
                            // 在已有问题列表中查找是否已存在相同内容的问题，实现问题复用
                            ProblemEntity existingProblem = problemService.findProblem(problem, knowledgeProblems);
                            if (existingProblem == null) {
                                // 不存在则创建新问题记录
                                ProblemEntity problemEntity = ProblemEntity.createDefault();
                                problemEntity.setId(problemId);
                                problemEntity.setKnowledgeId(knowledgeId);
                                problemEntity.setContent(problem);
                                problemEntities.add(problemEntity);
                                // 加入内存列表，后续段落遇到相同问题时可直接复用
                                knowledgeProblems.add(problemEntity);
                            } else {
                                // 已存在则复用其ID，避免重复插入
                                problemId = existingProblem.getId();
                            }
                            // 防止同一段落重复关联同一问题（本批次内去重）
                            if (isExistProblemParagraph(paragraph.getId(), problemId, problemParagraphs)) {
                                ProblemParagraphEntity pp = new ProblemParagraphEntity();
                                pp.setKnowledgeId(knowledgeId);
                                pp.setParagraphId(paragraph.getId());
                                pp.setDocumentId(doc.getId());
                                pp.setProblemId(problemId);
                                problemParagraphs.add(pp);
                            }
                        }
                    }
                }
            }
            // 设置文档总字符数
            doc.setCharLength(docCharLength.get());
            // 设置文档元数据：允许下载 + 来源文件ID
            String sourceFileId = Optional.ofNullable(d.getSourceFileId()).orElse("");
            doc.setMeta(new JSONObject(Map.of("allow_download", true, "sourceFileId", sourceFileId)));
            documentEntities.add(doc);
        }
        // 按依赖顺序批量写入：文档 → 段落 → 问题 → 问题段落映射
        this.saveBatch(documentEntities);
        if (!paragraphEntities.isEmpty()) {
            paragraphService.saveBatch(paragraphEntities);
        }
        List<String> docIds = documentEntities.stream().map(DocumentEntity::getId).toList();
        if (!problemEntities.isEmpty()) {
            problemService.saveBatch(problemEntities);
        }
        if (!problemParagraphs.isEmpty()) {
            problemParagraphService.saveBatch(problemParagraphs);
        }
        // 事务提交后发布文档索引事件，stateList=["0"] 表示待向量化的状态
        publishDocumentIndexEvent(knowledgeId, docIds, List.of("0"));
        return true;
    }

    private boolean isExistProblemParagraph(String paragraphId, String problemId, List<ProblemParagraphEntity> problemParagraphs) {
        return problemParagraphs.stream().noneMatch(e -> problemId.equals(e.getProblemId()) && paragraphId.equals(e.getParagraphId()));
    }

    // ===== 封装事件发布 =====
    private void publishDocumentIndexEvent(String knowledgeId, List<String> docIds, List<String> stateList) {
        if (!docIds.isEmpty()) {
            eventPublisher.publishEvent(new DocumentIndexEvent(this, knowledgeId, docIds, stateList));
        }
    }
}