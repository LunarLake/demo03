package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用流水（{@code t_credit_log}）。只由规则写入，不提供手工改分入口。
 *
 * <p>{@code uk_res_reason} 唯一键从库层挡住"同一预约同一原因重复扣分"，
 * 定时任务重复扫描也不会扣两次。
 */
@Data
@TableName("t_credit_log")
public class CreditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变动值：负扣分、正加分、0 为注记（如"再犯加倍"的说明行） */
    private Integer delta;

    /** 原因码，见 {@link Reason} */
    private String reasonCode;

    private Long reservationId;

    private Long appealId;

    /** 依据的规则版本 */
    private Long ruleVersionId;

    /** SYSTEM 或人工认定的管理员用户名 */
    private String operator;

    private String note;

    private LocalDateTime createdAt;

    /** 信用变动原因码及其默认分值（文档 3.2.2）。 */
    public enum Reason {
        /** 签到截止仍未签到：−10 */
        NO_SHOW("未签到（爽约）", -10),
        /** 结束超时占用超过 15 分钟：−5（一期由管理员手工认定） */
        OVERTIME_OCCUPY("超时占用", -5),
        /** 经确认的违规使用：−20 */
        VIOLATION("违规使用", -20),
        /** 30 日内同类再犯：注记行，实际加倍体现在前一条流水上 */
        REPEAT_DOUBLE("30 日内同类再犯（加倍）", 0),
        /** 按时签到并正常结束：+2 */
        ON_TIME_CHECKIN("按时签到并正常结束", 2),
        /** 30 日内无爽约：+5 */
        CLEAN_30D("30 日内无爽约", 5),
        /** 申诉成立，对冲原扣分 */
        APPEAL_REVOKE("申诉成立撤销扣分", 0);

        private final String label;
        private final int defaultDelta;

        Reason(String label, int defaultDelta) {
            this.label = label;
            this.defaultDelta = defaultDelta;
        }

        public String label() {
            return label;
        }

        public int defaultDelta() {
            return defaultDelta;
        }

        /** 是否为负向（扣分）原因，用于判断"30 日内是否再犯"。 */
        public boolean negative() {
            return defaultDelta < 0;
        }

        public static Reason of(String code) {
            for (Reason r : values()) {
                if (r.name().equals(code)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("未知的信用原因码：" + code);
        }
    }
}
