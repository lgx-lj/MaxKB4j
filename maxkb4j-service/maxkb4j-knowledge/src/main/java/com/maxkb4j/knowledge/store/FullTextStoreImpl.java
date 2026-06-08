package com.maxkb4j.knowledge.store;

import com.maxkb4j.knowledge.consts.SourceType;
import com.maxkb4j.knowledge.entity.EmbeddingEntity;
import com.maxkb4j.knowledge.retrieval.SearchRequest;
import com.maxkb4j.knowledge.util.Tokenizer;
import com.maxkb4j.knowledge.vo.TextChunkVO;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoExpression;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

/**
 * MongoDB 全文检索存储实现
 *
 * <p>使用 MongoDB 全文索引进行关键词匹配检索，支持中文分词</p>
 *
 * <p>核心特点：</p>
 * <ul>
 *   <li>使用 MongoDB 的 $text 查询进行全文检索</li>
 *   <li>支持中文分词（通过 {@link Tokenizer#segment(String)}）</li>
 *   <li>使用聚合管道（Aggregation Pipeline）实现复杂的检索逻辑</li>
 *   <li>支持分数归一化和阈值过滤</li>
 * </ul>
 *
 * <p>使用前提：</p>
 * <ul>
 *   <li>MongoDB 集合必须创建全文索引：{@code db.embedding.createIndex({ content: "text" })}</li>
 * </ul>
 *
 * @author MaxKB
 */
@Slf4j
@Component("fullTextStore")
@RequiredArgsConstructor
public class FullTextStoreImpl implements IDataStore {


    private final MongoTemplate mongoTemplate;

    @Override
    public void upsert(EmbeddingModel model, List<EmbeddingEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        entities.forEach(entity -> {
           String content = Tokenizer.segment(entity.getContent());
           entity.setContent(content);
        });
        // Insert into MongoDB for full-text search
        mongoTemplate.insertAll(entities);
        log.debug("Inserted {} embedding entities into MongoDB", entities.size());
    }

    @Override
    public void deleteByProblemIdAndParagraphId(String knowledgeId, String problemId, String paragraphId) {
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId).and("paragraphId").is(paragraphId).and("sourceId").is(problemId));
        mongoTemplate.remove(query, EmbeddingEntity.class);
    }

    @Override
    public void deleteProblemByIds(String knowledgeId, List<String> problemIds) {
        if (problemIds == null || problemIds.isEmpty()) {
            return;
        }
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId).and("sourceType").is(SourceType.PROBLEM).and("sourceId").in(problemIds));
        mongoTemplate.remove(query, EmbeddingEntity.class);
    }

    @Override
    public void deleteByParagraphIds(String knowledgeId, List<String> paragraphIds) {
        if (paragraphIds == null || paragraphIds.isEmpty()) {
            return;
        }
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId).and("paragraphId").in(paragraphIds));
        mongoTemplate.remove(query, EmbeddingEntity.class);
        log.debug("Deleted embeddings from MongoDB by paragraph IDs: {}", paragraphIds);
    }

    @Override
    public void deleteByDocumentIds(String knowledgeId, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId).and("documentId").in(documentIds));
        mongoTemplate.remove(query, EmbeddingEntity.class);
        log.debug("Deleted embeddings from MongoDB by document IDs: {}", documentIds);
    }

    @Override
    public void deleteByKnowledgeId(String knowledgeId) {
        if (knowledgeId == null) {
            return;
        }
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId));
        mongoTemplate.remove(query, EmbeddingEntity.class);
        log.debug("Deleted embeddings from MongoDB for knowledge ID: {}", knowledgeId);
    }

    @Override
    public void updateActiveStatus(String knowledgeId, String paragraphId, boolean isActive) {
        Query query = new Query(Criteria.where("knowledgeId").is(knowledgeId).and("paragraphId").is(paragraphId));
        Update update = new Update().set("isActive", isActive);
        mongoTemplate.updateMulti(query, update, EmbeddingEntity.class);
        log.debug("Updated active status in MongoDB for paragraph: {} to {}", paragraphId, isActive);
    }

    /**
     * 全文检索的核心方法
     *
     * <p>使用 MongoDB 全文索引进行关键词匹配检索，支持中文分词</p>
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>参数校验（知识库ID、查询文本）</li>
     *   <li>构建全文检索条件（TextCriteria）</li>
     *   <li>构建基础过滤条件（知识库、激活状态、排除列表）</li>
     *   <li>构建聚合管道（Aggregation Pipeline）</li>
     *   <li>执行聚合查询</li>
     *   <li>分数归一化（textScore -> [0, 1]）</li>
     *   <li>过滤和转换（Stream API）</li>
     * </ol>
     *
     * @param request 检索请求对象，包含：
     *               - knowledgeIds：知识库ID列表
     *               - excludeDocumentIds：排除的文档ID
     *               - excludeParagraphIds：排除的段落ID
     *               - query：查询文本
     *               - topK：返回数量上限
     *               - minScore：最小相似度阈值
     * @return 检索结果列表，按相似度降序排列
     */
    @Override
    public List<TextChunkVO> search(SearchRequest request) {
        // ============================================================
        // 第一步：参数校验
        // ============================================================

        // 校验知识库ID列表是否为空
        // 如果为空，直接返回空结果，避免后续查询出错
        if (request.getKnowledgeIds() == null || request.getKnowledgeIds().isEmpty()) {
            return Collections.emptyList();
        }

        // 校验查询文本是否为空或空白
        // 如果查询文本为空，无法进行全文检索，直接返回空结果
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return Collections.emptyList();
        }

        try {
            // ============================================================
            // 第二步：构建全文检索条件
            // ============================================================

            // 创建全文检索条件
            // TextCriteria.forDefaultLanguage() - 使用默认语言设置
            // .matching(Tokenizer.segment(request.getQuery())) - 对查询文本进行分词
            // 例如："邻居们" -> "邻居 们"
            TextCriteria textCriteria = TextCriteria.forDefaultLanguage()
                    .matching(Tokenizer.segment(request.getQuery()));

            // ============================================================
            // 第三步：构建基础过滤条件
            // ============================================================

            // 创建基础过滤条件：
            // 1. knowledgeId 必须在指定的知识库ID列表中
            // 2. isActive 必须为 true（只检索激活的段落）
            Criteria baseCriteria = Criteria.where("knowledgeId").in(request.getKnowledgeIds())
                    .and("isActive").is(true);

            // 如果有需要排除的文档ID，添加排除条件
            // documentId 不能在排除列表中
            if (!CollectionUtils.isEmpty(request.getExcludeDocumentIds())) {
                baseCriteria.and("documentId").nin(request.getExcludeDocumentIds());
            }

            // 如果有需要排除的段落ID，添加排除条件
            // paragraphId 不能在排除列表中
            if (!CollectionUtils.isEmpty(request.getExcludeParagraphIds())) {
                baseCriteria.and("paragraphId").nin(request.getExcludeParagraphIds());
            }

            // ============================================================
            // 第四步：构建聚合管道（Aggregation Pipeline）
            // ============================================================

            // 聚合管道是 MongoDB 的强大功能，可以对数据进行多阶段处理
            // 每个阶段处理完后，将结果传递给下一个阶段
            Aggregation aggregation = Aggregation.newAggregation(
                    // 阶段1：应用全文检索条件
                    // 使用 TextCriteria 进行全文匹配
                    // 只返回包含查询关键词的文档
                    Aggregation.match(textCriteria),

                    // 阶段2：应用基础过滤条件
                    // 过滤出指定知识库、激活状态、未排除的文档
                    Aggregation.match(baseCriteria),

                    // 阶段3：添加文本分数字段
                    // MongoDB 的全文检索会返回一个 textScore，表示匹配程度
                    // 这个分数是基于词频、文档长度等因素计算的
                    // {$meta: 'textScore'} - 获取 MongoDB 计算的文本匹配分数
                    Aggregation.addFields()
                            .addField("score")
                            .withValueOf(MongoExpression.create("{$meta: 'textScore'}"))
                            .build(),

                    // 阶段4：按分数降序排序
                    // 确保同一个 paragraphId 的多个文档中，分数最高的排在最前面
                    // 这样在后续的 group 阶段，first() 就能获取到分数最高的文档
                    Aggregation.sort(Sort.Direction.DESC, "score"),

                    // 阶段5：按 paragraphId 分组
                    // 同一个段落可能被切分成多个 chunk（片段）
                    // 这里按 paragraphId 分组，只保留分数最高的那个 chunk
                    // first("$$ROOT") - 获取当前分组中的第一个文档（即分数最高的）
                    // as("highestScoreDoc") - 将其命名为 highestScoreDoc
                    Aggregation.group("paragraphId")
                            .first("$$ROOT").as("highestScoreDoc"),

                    // 阶段6：替换根文档
                    // 将 highestScoreDoc 提升为根文档
                    // 这样后续的操作可以直接访问文档的字段
                    Aggregation.replaceRoot("highestScoreDoc"),

                    // 阶段7：最终排序
                    // 再次按分数降序排序，确保最终结果是有序的
                    Aggregation.sort(Sort.Direction.DESC, "score"),

                    // 阶段8：限制返回数量
                    // 只返回 topK 个结果
                    Aggregation.limit(request.getTopK())
            );

            // ============================================================
            // 第五步：执行聚合查询
            // ============================================================

            // 执行聚合查询，获取结果
            // mongoTemplate.aggregate() - 执行聚合查询
            // aggregation - 聚合管道
            // mongoTemplate.getCollectionName(EmbeddingEntity.class) - 获取集合名称
            // EmbeddingEntity.class - 结果映射的实体类
            List<EmbeddingEntity> result = mongoTemplate.aggregate(
                    aggregation,
                    mongoTemplate.getCollectionName(EmbeddingEntity.class),
                    EmbeddingEntity.class
            ).getMappedResults();

            // 如果结果为空，直接返回空列表
            if (CollectionUtils.isEmpty(result)) {
                return Collections.emptyList();
            }

            // ============================================================
            // 第六步：分数归一化
            // ============================================================

            // 获取最高分数（第一个元素的分数，因为已经按分数降序排序）
            float score = result.get(0) == null ? 0 : result.get(0).getScore();

            // 计算归一化的基准值
            // 取最高分数和2中的较大值作为基准
            // 为什么要取2？因为 MongoDB 的 textScore 可能会大于1
            // 使用 Math.max(score, 2) 确保归一化后的分数不会超过1
            float maxScore = Math.max(score, 2);

            // 对所有结果的分数进行归一化
            // 归一化公式：normalizedScore = score / maxScore
            // 归一化后的分数范围：[0, 1]
            for (EmbeddingEntity entity : result) {
                float normalizedScore = entity.getScore() / maxScore;
                entity.setScore(normalizedScore);
            }

            // ============================================================
            // 第七步：过滤和转换
            // ============================================================

            // 使用 Stream API 进行过滤和转换
            return result.stream()
                    // 将 EmbeddingEntity 转换为 TextChunkVO
                    // TextChunkVO 只包含 paragraphId 和 score 两个字段
                    .map(entity -> new TextChunkVO(entity.getParagraphId(), entity.getScore()))
                    // 过滤掉分数低于最小阈值的结果
                    // request.getMinScore() 是用户设置的最小相似度阈值（如0.6）
                    .filter(vo -> vo.getScore() >= request.getMinScore())
                    // 收集为 List
                    .toList();

        } catch (Exception e) {
            // ============================================================
            // 异常处理
            // ============================================================

            // 如果检索过程中出现异常，记录错误日志并返回空列表
            // 这样可以避免因为检索失败而导致整个请求失败
            log.error("Full-text search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

}