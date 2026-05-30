package com.maxkb4j.knowledge.store;

import com.maxkb4j.knowledge.entity.EmbeddingEntity;
import com.maxkb4j.knowledge.retrieval.SearchRequest;
import com.maxkb4j.knowledge.vo.TextChunkVO;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 组合向量存储 —— 双写 PostgreSQL(pgvector) 和 MongoDB(全文检索)
 * <p>
 * 知识库入库链路的"向量存储"环节。每个写入/删除操作同时同步到两个底层存储，
 * 保证向量检索和全文检索的数据一致性。
 * </p>
 *
 * <h3>两个底层存储</h3>
 * <ul>
 *   <li><b>VectorStoreImpl</b>：PostgreSQL pgvector，存储向量嵌入数据，
 *       使用 {@code embeddingMapper.search()} 执行向量距离计算（余弦相似度等）</li>
 *   <li><b>FullTextStoreImpl</b>：MongoDB，存储分词后的文本，
 *       使用 {@code $text} 索引进行全文关键词检索</li>
 * </ul>
 *
 * <h3>混合检索（RRF 融合）</h3>
 * {@link #search(SearchRequest)} 方法同时从两个存储并行检索，
 * 结果按 paragraphId 合并取最高分，按 score 降序返回 topK。
 *
 * <h3>数据一致性</h3>
 * 所有写入操作先写 PostgreSQL，再写 MongoDB。两者均为同步操作，
 * 任一失败都会抛异常回滚（依赖外层事务）。
 */
@Slf4j
@Component("compositeStore")
@RequiredArgsConstructor
public class CompositeStoreImpl implements IDataStore {

    private final VectorStoreImpl vectorStore;
    private final FullTextStoreImpl fullTextStore;


    /**
     * 双写向量嵌入数据到 PostgreSQL 和 MongoDB
     * <p>
     * 这是入库链路中向量化的最终写入点。被 DataIndexListener.embedBatch() →
     * ParagraphService.createIndexBatch() 调用。
     * </p>
     *
     * <h3>写入顺序</h3>
     * <ol>
     *   <li>VectorStoreImpl.upsert(model, entities) → PostgreSQL embedding 表（向量数据）</li>
     *   <li>FullTextStoreImpl.upsert(model, entities) → MongoDB embedding 集合（分词文本）</li>
     * </ol>
     *
     * @param model    嵌入模型，用于 generate 向量（已在调用方完成，此处用于兼容接口）
     * @param entities 待写入的向量实体列表
     * @throws RuntimeException 任一存储写入失败时抛出
     */
    @Override
    public void upsert(EmbeddingModel model, List<EmbeddingEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        log.debug("Upserting {} entities to both PostgreSQL and MongoDB", entities.size());
        try {
            // Dual-write: insert to PostgreSQL (with vector embeddings)
            vectorStore.upsert(model, entities);
            // Insert to MongoDB (for full-text search)
            fullTextStore.upsert(model, entities);
            log.debug("Successfully upserted {} entities to both stores", entities.size());
        } catch (Exception e) {
            log.error("Failed to upsert entities: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upsert entities to vector stores", e);
        }
    }

    @Override
    public void deleteByProblemIdAndParagraphId(String knowledgeId, String problemId, String paragraphId) {
        try {
            vectorStore.deleteByProblemIdAndParagraphId(knowledgeId, problemId, paragraphId);
            fullTextStore.deleteByProblemIdAndParagraphId(knowledgeId, problemId, paragraphId);
        } catch (Exception e) {
            log.error("Failed to delete by paragraph IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete from vector stores", e);
        }
    }

    @Override
    public void deleteProblemByIds(String knowledgeId, List<String> problemIds) {
        try {
            vectorStore.deleteProblemByIds(knowledgeId, problemIds);
            fullTextStore.deleteProblemByIds(knowledgeId, problemIds);
        } catch (Exception e) {
            log.error("Failed to delete by paragraph IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete from vector stores", e);
        }
    }

    @Override
    public void deleteByParagraphIds(String knowledgeId, List<String> paragraphIds) {
        log.debug("Deleting embeddings by paragraph IDs from both stores");
        try {
            vectorStore.deleteByParagraphIds(knowledgeId, paragraphIds);
            fullTextStore.deleteByParagraphIds(knowledgeId, paragraphIds);
        } catch (Exception e) {
            log.error("Failed to delete by paragraph IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete from vector stores", e);
        }
    }

    @Override
    public void deleteByDocumentIds(String knowledgeId, List<String> documentIds) {
        log.debug("Deleting embeddings by document IDs from both stores");
        try {
            vectorStore.deleteByDocumentIds(knowledgeId, documentIds);
            fullTextStore.deleteByDocumentIds(knowledgeId, documentIds);
        } catch (Exception e) {
            log.error("Failed to delete by document IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete from vector stores", e);
        }
    }

    @Override
    public void deleteByKnowledgeId(String knowledgeId) {
        log.debug("Deleting embeddings for knowledge ID from both stores");
        try {
            vectorStore.deleteByKnowledgeId(knowledgeId);
            fullTextStore.deleteByKnowledgeId(knowledgeId);
        } catch (Exception e) {
            log.error("Failed to delete by knowledge ID: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete from vector stores", e);
        }
    }

    @Override
    public void updateActiveStatus(String knowledgeId, String paragraphId, boolean isActive) {
        log.debug("Updating active status for paragraph in both stores");
        try {
            vectorStore.updateActiveStatus(knowledgeId, paragraphId, isActive);
            fullTextStore.updateActiveStatus(knowledgeId, paragraphId, isActive);
        } catch (Exception e) {
            log.error("Failed to update active status: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update status in vector stores", e);
        }
    }

    /**
     * 混合检索 —— 向量检索 + 全文检索，RRF 融合排序
     * <p>
     * 通过 CompletableFuture 并行执行两个存储的检索，结果按 paragraphId 合并
     * （取最高分），按 score 降序返回前 topK 条。
     * </p>
     *
     * @param request 检索请求，包含 knowledgeIds、queryText、topK、相似度阈值等
     * @return 融合排序后的检索结果，最多 topK 条
     */
    @Override
    public List<TextChunkVO> search(SearchRequest request) {
        Map<String, Float> map = new LinkedHashMap<>();
        List<TextChunkVO> results = new ArrayList<>();
        List<CompletableFuture<List<TextChunkVO>>> futureList = new ArrayList<>();
        futureList.add(CompletableFuture.supplyAsync(()->vectorStore.search(request)));
        futureList.add(CompletableFuture.supplyAsync(()->fullTextStore.search(request)));
        List<TextChunkVO> retrieveResults = futureList.stream().flatMap(future-> future.join().stream()).toList();
        //融合排序
        for (TextChunkVO result : retrieveResults) {
            if (map.containsKey(result.getParagraphId())) {
                if (map.get(result.getParagraphId()) < result.getScore()) {
                    map.put(result.getParagraphId(), result.getScore());
                }
            } else {
                map.put(result.getParagraphId(), result.getScore());
            }
        }
        map.forEach((key, value) -> results.add(new TextChunkVO(key, value)));
        results.sort(Comparator.comparing(TextChunkVO::getScore).reversed());
        int endIndex = Math.min(request.getTopK(), results.size());
        return results.subList(0, endIndex);
    }


}