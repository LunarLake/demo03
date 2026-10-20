package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申诉工单（{@code t_appeal}）。
 *
 * <p>刻意**独立于预约状态机**：工单不占用房间单元，也不会因为申诉而把已经释放的单元"变回来"。
 * 申诉成立时只撤销信用扣分；用户若仍需要房间，走 {@code APPEAL_REBUILD} 重新提交一笔新预约。
 *
 * <p>{@code uk_user_target} 唯一键实现"同一目标只能申诉一次"。
 */
@Data
@TableName("t_appeal")
public class Appeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提交人 */
    private Long userId;

    /** 申诉对象类型，见 {@link TargetType} */
    private String targetType;

    /** 预约 ID 或信用流水 ID */
    private Long targetId;

    private String reason;

    /** 凭证说明或链接（一期不做文件上传） */
    private String evidence;

    /** 处理状态，见 {@link Status} */
    private String status;

    /** 原处理结论与规则版本快照，复核时展示 */
    private String originalDecision;

    private Long ruleVersionId;

    /** 复核人（必须非原审核人） */
    private Long reviewerId;

    private String reviewNote;

    private LocalDateTime createdAt;

    /** 应办结时刻 = 提交 + 3 工作日 */
    private LocalDateTime dueAt;

    private LocalDateTime decidedAt;

    /** 是否已办结。 */
    public boolean isDecided() {
        return decidedAt != null;
    }

    public enum TargetType {
        /** 对预约结果（拒绝/取消/超时释放）的申诉 */
        RESERVATION,
        /** 对信用扣分或违规认定的申诉 */
        CREDIT
    }

    public enum Status {
        /** 已提交，待复核 */
        SUBMITTED("待复核"),
        /** 维持原判 */
        UPHELD("维持原判"),
        /** 撤销原判（撤销扣分/纠正记录） */
        REVOKED("申诉成立"),
        /** 部分成立：纠正记录但维持扣分 */
        PARTIAL("部分成立");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean closed() {
            return this != SUBMITTED;
        }
    }
}
