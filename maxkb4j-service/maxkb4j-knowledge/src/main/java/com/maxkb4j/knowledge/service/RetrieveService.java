package com.maxkb4j.knowledge.service;

import com.maxkb4j.common.mp.entity.KnowledgeSetting;
import com.maxkb4j.knowledge.consts.SearchType;
import com.maxkb4j.knowledge.dto.DataSearchDTO;
import com.maxkb4j.knowledge.mapper.ParagraphMapper;
import com.maxkb4j.knowledge.retrieval.IDataRetriever;
import com.maxkb4j.knowledge.vo.ParagraphVO;
import com.maxkb4j.knowledge.vo.TextChunkVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetrieveService implements IRetrieveService{

    private final ParagraphMapper paragraphMapper;
    private final IDataRetriever dataRetriever;


    /**
     * 执行数据检索的核心方法
     *
     * <p>这是检索链路的入口方法，负责：</p>
     * <ol>
     *   <li>校验知识库ID列表是否为空</li>
     *   <li>从 DTO 中提取搜索参数</li>
     *   <li>委托给 dataRetriever 执行实际检索</li>
     * </ol>
     *
     * @param knowledgeIds 知识库ID列表，如 ["id1", "id2"]
     *                    - 一个知识库可以包含多个文档
     *                    - 支持跨知识库检索
     * @param dto 搜索参数对象，包含：
     *           - queryText：用户查询的文本（如"邻居们"）
     *           - excludeParagraphIds：需要排除的段落ID（避免重复检索）
     *           - topNumber：返回结果数量上限（如5）
     *           - similarity：相似度阈值（如0.6，只返回>=0.6的结果）
     *           - searchMode：搜索模式（embedding/keywords/hybrid/graph）
     *           - chatModelId：图检索时使用的模型ID
     * @return 检索结果列表，每个 TextChunkVO 包含：
     *         - paragraphId：段落ID
     *         - score：相似度分数（0-1）
     */
    private List<TextChunkVO> dataSearch(List<String> knowledgeIds, DataSearchDTO dto) {
        // 空值校验：如果知识库ID列表为空，直接返回空结果
        // 避免后续查询时出现空指针异常
        if (CollectionUtils.isEmpty(knowledgeIds)) {
            return Collections.emptyList();
        }

        // 委托给 dataRetriever 执行实际检索
        // dataRetriever 是 IDataRetriever 接口的实现类 DataRetriever
        // 这里使用了依赖注入，Spring 会自动注入实现类
        return dataRetriever.search(
                knowledgeIds,                 // 知识库ID列表
                dto.getExcludeParagraphIds(), // 排除的段落ID
                dto.getQueryText(),           // 查询文本
                dto.getTopNumber(),           // 返回数量上限
                dto.getSimilarity(),          // 相似度阈值
                dto.getSearchMode(),          // 搜索模式
                dto.getChatModelId()          // 模型ID（图检索用）
        );
    }

    public List<ParagraphVO> paragraphSearch(String question, List<String> knowledgeIds, List<String> excludeParagraphIds, KnowledgeSetting datasetSetting) {
        DataSearchDTO dto = new DataSearchDTO();
        dto.setQueryText(question);
        dto.setSearchMode(datasetSetting.getSearchMode());
        dto.setSimilarity(datasetSetting.getSimilarity());
        dto.setTopNumber(datasetSetting.getTopN());
        dto.setExcludeParagraphIds(excludeParagraphIds);
        // For graph search mode, use the graph model ID or fall back to application model ID
        if (SearchType.GRAPH.equals(datasetSetting.getSearchMode())) {
            dto.setChatModelId(datasetSetting.getGraphModelId());
        }
        return paragraphSearch(knowledgeIds, dto);
    }
    /**
     * 根据知识ID列表和查询DTO，进行段落搜索
     * 搜索 → 提取ID → 查数据库 → 设置分数 → 排序 → 返回
     * @param knowledgeIds 知识ID列表
     * @param dto 查询DTO
     * @return 搜索结果列表
     */

    public List<ParagraphVO> paragraphSearch(List<String> knowledgeIds, DataSearchDTO dto) {
        // 调用检索服务，返回「段落ID + 分数」的列表
        List<TextChunkVO> list = dataSearch(knowledgeIds, dto);
        // 提取段落ID
        List<String> paragraphIds = list.stream().map(TextChunkVO::getParagraphId).toList();


        if (CollectionUtils.isEmpty(paragraphIds)) {
            return Collections.emptyList();
        }


        Map<String, Float> scoreMap = list.stream().collect(Collectors.toMap(TextChunkVO::getParagraphId, TextChunkVO::getScore));
        // 记录 paragraphIds 的顺序索引
        Map<String, Integer> orderMap = new java.util.HashMap<>();
        for (int i = 0; i < paragraphIds.size(); i++) {
            orderMap.put(paragraphIds.get(i), i);
        }
        List<ParagraphVO> paragraphs = paragraphMapper.retrievalParagraph(paragraphIds);
        paragraphs.forEach(e -> {
            float score = scoreMap.get(e.getId());
            e.setSimilarity(score);
            e.setComprehensiveScore(score);
            if (e.getDocumentName()==null){
                e.setDocumentName("");
            }
        });
        // 按照 paragraphIds 的原始顺序排序
        paragraphs.sort(Comparator.comparingInt(p -> orderMap.get(p.getId())));
        return paragraphs;
    }
}
