package com.maxkb4j.application.vo;

import lombok.Data;

import java.util.Date;

/**
 * 对话记录详情展示对象
 * <p>用于展示单条对话记录的详细信息，包含问题、回答、耗时、评分等，用于对话历史详情页</p>
 */
@Data
public class ChatRecordDetailVO {
    /** 会话ID */
    private String chatId;
    /** 对话概要/摘要 */
    private String overview;
    /** 投票状态（如点赞/踩的状态） */
    private String voteStatus;
    /** 用户问题文本 */
    private String problemText;
    /** AI回答文本 */
    private String answerText;
    /** Token消耗量 */
    private Integer cost;
    /** 回答耗时（秒） */
    private Float runTime;
    /** 对话轮次序号 */
    private Integer index;
    /** 创建时间 */
    private Date createTime;
}
