package com.maxkb4j.knowledge.retrieval;


import com.maxkb4j.knowledge.vo.TextChunkVO;

import java.util.List;

/**
 * 数据检索器接口
 *
 * <p>定义了检索服务的统一接口，支持多种搜索模式：</p>
 * <ul>
 *   <li>EMBEDDING - 向量检索（语义相似度，使用 PostgreSQL pgvector）</li>
 *   <li>FULL_TEXT - 全文检索（关键词匹配，使用 MongoDB 全文索引）</li>
 *   <li>HYBRID - 混合检索（向量+全文，RRF 融合排序）</li>
 *   <li>GRAPH - 图检索（基于知识图谱）</li>
 * </ul>
 *
 * @author MaxKB
 */
public interface IDataRetriever {

    /**
     * 执行检索（无模型ID版本）
     *
     * <p>用于非图检索场景，不需要指定模型ID</p>
     *
     * @param knowledgeIds        知识库ID列表
     * @param excludeParagraphIds 需要排除的段落ID列表（避免重复检索）
     * @param keyword             用户查询的关键词/文本
     * @param maxResults          最大返回结果数（topK）
     * @param minScore            最小相似度阈值（0-1），低于此值的结果会被过滤
     * @param searchMode          搜索模式（embedding/keywords/hybrid/graph）
     * @return 检索结果列表，按相似度降序排列
     */
    List<TextChunkVO> search(List<String> knowledgeIds, List<String> excludeParagraphIds,
                             String keyword, int maxResults, float minScore, String searchMode);

    /**
     * 执行检索（带模型ID版本）
     *
     * <p>用于图检索场景，需要指定模型ID来生成嵌入向量</p>
     *
     * @param knowledgeIds        知识库ID列表
     * @param excludeParagraphIds 需要排除的段落ID列表
     * @param keyword             用户查询的关键词/文本
     * @param maxResults          最大返回结果数
     * @param minScore            最小相似度阈值
     * @param searchMode          搜索模式
     * @param chatModelId         模型ID（图检索时使用，用于生成嵌入向量）
     * @return 检索结果列表
     */
    List<TextChunkVO> search(List<String> knowledgeIds, List<String> excludeParagraphIds,
                             String keyword, int maxResults, float minScore, String searchMode,
                             String chatModelId);
}
