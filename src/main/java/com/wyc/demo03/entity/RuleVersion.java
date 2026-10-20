package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核规则版本（{@code t_rule_version}）。
 *
 * <p>每改一次附加规则参数就产生一个新版本。预约行记录判定时的 {@code ruleVersionId}，
 * 因此事后调整参数不会"篡改历史"，申诉复核时能还原当时的依据。
 */
@Data
@TableName("t_rule_version")
public class RuleVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本号，自增 */
    private Integer versionNo;

    /** 生效时刻 */
    private LocalDateTime effectiveFrom;

    /** 发布人 ID */
    private Long createdBy;

    /** 变更说明 */
    private String note;
}
