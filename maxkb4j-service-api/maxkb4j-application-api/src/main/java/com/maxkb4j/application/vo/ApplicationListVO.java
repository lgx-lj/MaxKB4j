package com.maxkb4j.application.vo;

import lombok.Data;

/**
 * 应用列表展示对象
 * <p>用于应用列表页的数据展示，包含应用的基本信息摘要</p>
 */
@Data
public class ApplicationListVO {
    /** 应用ID */
    private String id;
    /** 应用名称 */
    private String name;
    /** 应用描述 */
    private String desc;
    /** 应用图标 */
    private String icon;
    /** 是否已发布 */
    private Boolean isPublish;
}
