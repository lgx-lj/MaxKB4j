package com.maxkb4j.knowledge.retriever;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.maxkb4j.knowledge.consts.SearchType;
import com.maxkb4j.knowledge.entity.DocumentEntity;
import com.maxkb4j.knowledge.retrieval.SearchMode;
import com.maxkb4j.knowledge.retrieval.SearchRequest;
import com.maxkb4j.knowledge.retrieval.IDataRetriever;
import com.maxkb4j.knowledge.service.IDocumentService;
import com.maxkb4j.knowledge.store.IDataStore;
import com.maxkb4j.knowledge.store.VectorStoreImpl;
import com.maxkb4j.knowledge.store.FullTextStoreImpl;
import com.maxkb4j.knowledge.store.CompositeStoreImpl;
import com.maxkb4j.knowledge.store.GraphStoreImpl;
import com.maxkb4j.knowledge.vo.TextChunkVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 统一数据检索器实现
 *
 * <p>支持多种搜索模式，根据 searchMode 参数选择对应的存储实现：</p>
 * <ul>
 *   <li>{@code embedding} - 向量检索，使用 PostgreSQL pgvector（VectorStoreImpl）</li>
 *   <li>{@code keywords} - 全文检索，使用 MongoDB 全文索引（FullTextStoreImpl）</li>
 *   <li>{@code hybrid} - 混合检索，同时使用向量和全文检索，RRF 融合排序（CompositeStoreImpl）</li>
 *   <li>{@code graph} - 图检索，基于关键词图谱（GraphStoreImpl）</li>
 * </ul>
 *
 * <p>设计模式：策略模式（Strategy Pattern）</p>
 * <p>通过 getStore() 方法根据 searchMode 动态选择不同的检索实现</p>
 *
 * @author MaxKB
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetriever implements IDataRetriever {

    /**
     * 向量检索存储实现
     * <p>使用 PostgreSQL pgvector 进行语义相似度检索</p>
     * <p>适合模糊查询、语义理解场景</p>
     */
    private final VectorStoreImpl vectorStore;

    /**
     * 全文检索存储实现
     * <p>使用 MongoDB 全文索引进行关键词匹配</p>
     * <p>适合精确关键词查询场景</p>
     */
    private final FullTextStoreImpl fullTextStore;

    /**
     * 混合检索存储实现
     * <p>同时使用向量检索和全文检索，通过 RRF（Reciprocal Rank Fusion）算法融合排序</p>
     * <p>综合了向量检索的语义理解和全文检索的精确匹配，效果最优</p>
     */
    private final CompositeStoreImpl compositeStore;

    /**
     * 图检索存储实现
     * <p>基于知识图谱进行实体关系检索</p>
     * <p>适合复杂查询、实体关系推理场景</p>
     */
    private final GraphStoreImpl graphStore;

    /**
     * 文档服务，用于查询需要排除的文档
     */
    private final IDocumentService documentService;

    /**
     * 搜索模式映射表
     * <p>将前端传来的字符串映射为枚举类型 SearchMode</p>
     * <p>键：搜索模式字符串（对应 SearchType 常量）</p>
     * <p>值：搜索模式枚举（SearchMode）</p>
     */
    private static final Map<String, SearchMode> SEARCH_MODE_MAP = Map.of(
            SearchType.EMBEDDING, SearchMode.VECTOR,      // "embedding" -> VECTOR
            SearchType.FULL_TEXT, SearchMode.FULL_TEXT,     // "keywords"  -> FULL_TEXT
            SearchType.HYBRID, SearchMode.HYBRID,          // "hybrid"    -> HYBRID
            SearchType.GRAPH, SearchMode.GRAPH             // "graph"     -> GRAPH
    );

    /**
     * 执行检索（无模型ID版本）
     *
     * <p>委托给带模型ID的重载方法，chatModelId 传 null</p>
     */
    @Override
    public List<TextChunkVO> search(List<String> knowledgeIds, List<String> excludeParagraphIds,
                                     String keyword, int maxResults, float minScore, String searchMode) {
        return search(knowledgeIds, excludeParagraphIds, keyword, maxResults, minScore, searchMode, null);
    }

    /**
     * 执行检索的核心方法
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>构建 SearchRequest 请求对象，封装所有检索参数</li>
     *   <li>查询需要排除的文档（已禁用的文档 isActive=false）</li>
     *   <li>根据搜索模式选择对应的存储实现（策略模式）</li>
     *   <li>调用存储实现执行检索，返回结果</li>
     * </ol>
     *
     * @param knowledgeIds        知识库ID列表
     * @param excludeParagraphIds 需要排除的段落ID（避免重复检索）
     * @param keyword             查询文本（如"邻居们"）
     * @param maxResults          最大返回数（topK）
     * @param minScore            最小相似度阈值（0-1）
     * @param searchMode          搜索模式字符串（embedding/keywords/hybrid/graph）
     * @param chatModelId         模型ID（图检索时使用）
     * @return 检索结果列表，按相似度降序排列
     */
    @Override
    public List<TextChunkVO> search(List<String> knowledgeIds, List<String> excludeParagraphIds,
                                     String keyword, int maxResults, float minScore,
                                     String searchMode, String chatModelId) {
        // 1. 构建搜索请求对象
        SearchRequest request = new SearchRequest();
        request.setKnowledgeIds(knowledgeIds);                  // 知识库ID
        request.setExcludeParagraphIds(excludeParagraphIds);    // 排除的段落ID
        request.setQuery(keyword);                              // 查询文本
        request.setTopK(maxResults);                            // 返回数量上限
        request.setMinScore(minScore);                          // 最小相似度阈值
        request.setMode(SEARCH_MODE_MAP.get(searchMode));       // 搜索模式枚举
        request.setChatModelId(chatModelId);                    // 模型ID

        // 2. 查询需要排除的文档（已禁用的文档）
        //    从数据库中查询出 isActive=false 的文档ID
        //    这些文档虽然存在于知识库中，但不应该参与检索
        List<DocumentEntity> excludeDocuments = documentService
                .lambdaQuery()
                .select(DocumentEntity::getId)                    // 只查询ID字段，减少数据传输
                .in(DocumentEntity::getKnowledgeId, knowledgeIds) // 条件：知识库ID在列表中
                .eq(DocumentEntity::getIsActive, false)           // 条件：文档已禁用
                .list();

        // 3. 如果有需要排除的文档，设置到请求对象中
        if (CollectionUtils.isNotEmpty(excludeDocuments)) {
            request.setExcludeDocumentIds(
                    excludeDocuments.stream()
                            .map(DocumentEntity::getId)  // 提取文档ID
                            .toList()                     // 收集为List
            );
        }

        // 4. 根据搜索模式选择对应的存储实现，并执行检索
        //    getStore() 方法根据 searchMode 返回：
        //    - "embedding" -> vectorStore（向量检索）
        //    - "keywords"  -> fullTextStore（全文检索）
        //    - "hybrid"    -> compositeStore（混合检索）
        //    - "graph"     -> graphStore（图检索）
        return getStore(searchMode).search(request);
    }

    /**
     * 根据搜索模式获取对应的存储实现
     *
     * <p>使用 switch 表达式（Java 14+）实现模式匹配，简洁高效</p>
     *
     * @param searchMode 搜索模式字符串，对应 SearchType 中的常量
     * @return 对应的存储实现（IDataStore 接口）
     * @throws IllegalArgumentException 未知的搜索模式时抛出异常
     */
    private IDataStore getStore(String searchMode) {
        return switch (searchMode) {
            case SearchType.EMBEDDING -> vectorStore;      // 向量检索
            case SearchType.FULL_TEXT -> fullTextStore;     // 全文检索
            case SearchType.HYBRID -> compositeStore;      // 混合检索
            case SearchType.GRAPH -> graphStore;           // 图检索
            default -> throw new IllegalArgumentException("Unknown search mode: " + searchMode);
        };
    }
}