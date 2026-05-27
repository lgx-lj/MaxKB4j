package com.maxkb4j.application.dto;

import lombok.Data;


/**
 * 聊天记录查询条件
 * <p>用于按摘要、时间范围、点赞/点踩数量等条件筛选聊天记录</p>
 */
@Data
public class ChatQueryDTO {
    /** 对话摘要（模糊查询） */
    private String summary;
    /** 查询起始时间 */
    private String startTime;
    /** 查询结束时间 */
    private String endTime;
    /** 最小点赞数 */
    private Integer minStar;
    /** 最小点踩数 */
    private Integer minTrample;
}
