package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 附加规则参数（{@code t_rule_config}）。
 *
 * <p>键值对而非表达式：{@code paramKey} 受代码中的白名单枚举约束，
 * 未知 key 拒绝保存。这是刻意取舍 —— 通用规则 DSL 在 16 周内无法穷举测试。
 */
@Data
@TableName("t_rule_config")
public class RuleConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属规则版本 */
    private Long versionId;

    /** 参数名（白名单） */
    private String paramKey;

    /** 参数值 */
    private String paramValue;
}
