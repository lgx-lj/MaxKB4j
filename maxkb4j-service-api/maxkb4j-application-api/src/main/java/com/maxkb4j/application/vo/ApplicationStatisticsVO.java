package com.maxkb4j.application.vo;

import lombok.Data;

/**
 * 应用统计数据展示对象
 * <p>用于应用数据看板，展示应用的运营统计指标</p>
 */
@Data
public class ApplicationStatisticsVO {
    /** 对话记录总数 */
    private Integer chatRecordCount;
    /** 新增客户数 */
    private Integer customerAddedCount;
    /** 客户总数 */
    private Integer customerNum;
    /** 点赞数 */
    private Integer starNum;
    /** Token消耗量 */
    private Integer tokensNum;
    /** 踩数（用户对回答的负面反馈次数） */
    private Integer trampleNum;
    /** 统计日期 */
    private String day;
    /** Token使用量（当日） */
    private Integer tokenUsage;
    /** 用户名称 */
    private String userName;
}
