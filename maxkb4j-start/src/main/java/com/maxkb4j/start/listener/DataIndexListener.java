package com.maxkb4j.start.listener;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.maxkb4j.core.event.DocumentIndexEvent;
import com.maxkb4j.core.event.ParagraphIndexEvent;
import com.maxkb4j.knowledge.entity.ParagraphEntity;
import com.maxkb4j.knowledge.service.IDocumentService;
import com.maxkb4j.knowledge.service.IParagraphService;
import com.maxkb4j.knowledge.service.KnowledgeModelService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 数据索引监听器 —— 异步处理文档和段落的向量化
 * <p>
 * 这是知识库入库链路中"向量化"环节的核心组件。通过 Spring 事件机制异步监听
 * DocumentIndexEvent 和 ParagraphIndexEvent，调用嵌入模型生成向量并写入存储。
 * </p>
 *
 * <h3>事件类型与触发时机</h3>
 * <ul>
 *   <li><b>DocumentIndexEvent</b>：文档写入完成后触发（全量/批量向量化）。
 *       {@code @EventListener} 保证在事务提交后执行。</li>
 *   <li><b>ParagraphIndexEvent</b>：段落创建/更新/迁移后触发（单个段落向量化）。
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 确保事务先提交，
 *       避免读到未提交的数据。</li>
 * </ul>
 *
 * <h3>向量化流程</h3>
 * <ol>
 *   <li>通过 knowledgeId 获取对应的 EmbeddingModel（嵌入模型）</li>
 *   <li>更新文档/段落状态为"向量化中"（status[1]=0）</li>
 *   <li>调用 ParagraphService.createIndexBatch() 批量生成向量</li>
 *   <li>CompositeStoreImpl.upsert() 双写：PostgreSQL(pgvector) + MongoDB(全文检索)</li>
 *   <li>更新段落状态为"完成"（status[1]=2），更新文档状态为"完成"（status[1]=2）</li>
 * </ol>
 *
 * <h3>异常处理</h3>
 * 单个文档失败不影响其他文档继续处理。段落批量处理失败时抛出异常，
 * 状态保持为"处理中"以支持重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataIndexListener {

    private final KnowledgeModelService knowledgeModelService;
    private final IDocumentService documentService;
    private final IParagraphService paragraphService;

    /**
     * 处理文档向量化事件（全量/批量）
     * <p>
     * {@code @Async} 异步执行，不阻塞调用线程。
     * {@code @EventListener} 在事件发布后立即执行（事务可能尚未提交）。
     * </p>
     * <p>
     * 遍历事件中的所有 docId，逐个处理：查询待向量化的段落 → 批量生成向量 → 更新状态。
     * 单个文档失败不影响其他文档。
     * </p>
     *
     * @param event 文档索引事件，包含 knowledgeId、docIds、stateList
     */
    @Async
    @EventListener
    public void handleEvent(DocumentIndexEvent event) {
        log.info("收到文档向量化事件消息: {}", event.getDocIds());
        EmbeddingModel embeddingModel = knowledgeModelService.getEmbeddingModel(event.getKnowledgeId());
        documentService.updateStatusByIds(event.getDocIds(), 1, 0);

        for (String docId : event.getDocIds()) {
            try {
                List<ParagraphEntity> paragraphs = paragraphService.listByStateIds(docId, 1, event.getStateList());
                embedBatch(embeddingModel, docId, paragraphs);
            } catch (Exception e) {
                log.error("文档索引失败: {}, 错误: {}", docId, e.getMessage(), e);
                // 单个文档失败不影响其他文档继续处理
            }
        }
    }

    /**
     * 处理段落向量化事件（单段落）
     * <p>
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保数据库事务
     * 已提交后再执行，避免读到未提交的段落数据。
     * </p>
     *
     * @param event 段落索引事件，包含 knowledgeId、docId、paragraphIds
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEvent(ParagraphIndexEvent event) {
        log.info("收到段落向量化事件消息: {}", event.getParagraphIds());
        List<ParagraphEntity> paragraphs = paragraphService.listByIds(event.getParagraphIds());
        EmbeddingModel embeddingModel = knowledgeModelService.getEmbeddingModel(event.getKnowledgeId());
        try {
            embedBatch(embeddingModel, event.getDocId(), paragraphs);
        } catch (Exception e) {
            log.error("段落索引失败: docId={}, paragraphIds={}, 错误: {}",
                event.getDocId(), event.getParagraphIds(), e.getMessage(), e);
        }
    }

    /**
     * 批量向量化段落并更新状态
     * <p>
     * 完整的向量化流程：
     * </p>
     * <ol>
     *   <li>更新文档状态：status[1]=0（向量化中）→ status[1]=1（正在处理）</li>
     *   <li>批量更新段落状态：status[1]=0（向量化中）</li>
     *   <li>调用 {@code paragraphService.createIndexBatch(paragraphs, embeddingModel)}
     *       批量生成向量并写入 CompositeStoreImpl（PG + MongoDB）</li>
     *   <li>批量更新段落状态：status[1]=2（完成）</li>
     *   <li>更新文档状态：status[1]=2（完成），并刷新 status_meta</li>
     * </ol>
     *
     * @param embeddingModel 嵌入模型，用于将文本转为向量
     * @param docId          文档ID
     * @param paragraphs     待向量化的段落列表
     */
    private void embedBatch(EmbeddingModel embeddingModel, String docId, List<ParagraphEntity> paragraphs) {
        documentService.updateStatusById(docId, 1, 0);

        if (CollectionUtils.isNotEmpty(paragraphs)) {
            log.info("开始--->文档索引: {}", docId);
            documentService.updateStatusById(docId, 1, 1);

            List<String> paragraphIds = paragraphs.stream().map(ParagraphEntity::getId).toList();
            paragraphService.updateStatusByIds(paragraphIds, 1, 0);

            try {
                // Use batch indexing instead of processing one by one
                paragraphService.createIndexBatch(paragraphs, embeddingModel);

                // Update all paragraph statuses to completed
                paragraphService.updateStatusByIds(paragraphIds, 1, 2);

                // Update document status
                documentService.updateStatusMetaById(docId);

                log.info("结束--->文档索引: {} (处理了 {} 个段落)", docId, paragraphs.size());

            } catch (Exception e) {
                log.error("文档索引失败: {}, 错误: {}", docId, e.getMessage(), e);
                // Keep paragraphs in processing state for retry
                throw new RuntimeException("文档索引失败: " + docId, e);
            }
        }

        documentService.updateStatusById(docId, 1, 2);
    }

}