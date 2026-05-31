package com.maxkb4j.knowledge.vo;

import com.maxkb4j.knowledge.dto.ParagraphSimple;
import lombok.Data;

import java.util.List;

/**
 * 文档切片预览结果 VO —— split() 接口返回给前端的视图对象
 * <p>
 * 对应 {@code DocumentService.split()} 的处理结果，一个文件对应一个 ViewObject。
 * 前端拿到后可以调整拆分结果（合并/拆分段落、修改标题、增删关联问题），
 * 确认无误后调用 {@code batch_create} 接口将调整后的段落列表写库。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code name}：原始文件名（如 "report.pdf"）</li>
 *   <li>{@code content}：拆分后的段落列表（前端可编辑调整）</li>
 *   <li>{@code sourceFileId}：原始文件在 MongoDB GridFS 中的存储ID，
 *       后续确认写入时通过此ID关联原始文件</li>
 * </ul>
 *
 * @see DocumentService#split(String, MultipartFile[], String[], Integer, Boolean)
 */
@Data
public class TextSegmentVO {

    /**
     * 原始文件名（如 "report.pdf"、"数据.xlsx"）
     */
    private String name;

    /**
     * 拆分后的段落列表，每个段落包含标题、正文、关联问题和元数据
     */
    private List<ParagraphSimple> content;

    /**
     * 原始文件在 MongoDB GridFS 中的存储ID
     * <p>
     * 前端后续调用 batch_create 接口时需要回传此ID，
     * 入库时会写入 {@code DocumentEntity.fileId} 字段以关联原始文件。
     * </p>
     */
    private String sourceFileId;
}
