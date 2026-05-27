package com.maxkb4j.application.dto;


import lombok.Data;

/**
 * 第三方平台启用状态数据传输对象
 * <p>用于设置或查询某个第三方平台（如微信、钉钉等）的启用/禁用状态</p>
 */
@Data
public class PlatformStatusDTO {
    /** 平台类型标识 */
    private String type;
    /** 是否启用 */
    private Boolean status;
}

