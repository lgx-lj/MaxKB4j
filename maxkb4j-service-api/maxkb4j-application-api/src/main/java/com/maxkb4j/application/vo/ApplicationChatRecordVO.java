package com.maxkb4j.application.vo;

import com.alibaba.fastjson.JSONObject;
import com.maxkb4j.common.domain.dto.ParagraphDTO;
import com.maxkb4j.application.entity.ApplicationChatRecordEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用对话记录展示对象
 * <p>继承对话记录实体，扩展了引用段落列表、优化后的问题文本和工作流执行详情，
 * 用于前端对话记录详情页的数据展示</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ApplicationChatRecordVO extends ApplicationChatRecordEntity {
    /** 关联的引用段落列表（用于展示回答的来源知识库段落） */
    private List<ParagraphDTO> paragraphList;
    /** 优化后的问题文本（问题改写后的结果） */
    private String paddingProblemText;
    /** 工作流执行详情列表（包含每个节点的执行信息） */
    private List<JSONObject> executionDetails;

    /** 设置执行详情，并清空父类的details字段，避免重复数据传输 */
    public void setExecutionDetails(List<JSONObject> executionDetails) {
        this.executionDetails = executionDetails;
        super.setDetails(null);
    }
}
