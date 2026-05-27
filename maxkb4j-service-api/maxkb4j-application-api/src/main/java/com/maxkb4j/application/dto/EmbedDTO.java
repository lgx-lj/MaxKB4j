package com.maxkb4j.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 应用嵌入代码生成的数据传输对象
 * <p>用于生成将应用以iframe方式嵌入到第三方网页中的代码片段</p>
 */
@Data
public class EmbedDTO {
    /** 协议类型，如 http 或 https */
    @NotBlank(message = "protocol 不能为空")
    private String protocol;
    /** 主机地址（域名或IP） */
    @NotBlank(message = "host 不能为空")
    private String host;
    /** 应用访问令牌 */
    @NotBlank(message = "token 不能为空")
    private String token;
    /** 预置查询问题，嵌入后自动发起的默认提问 */
    private String query;
}
