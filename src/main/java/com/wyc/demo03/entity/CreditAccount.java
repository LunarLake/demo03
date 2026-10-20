package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用账户（{@code t_credit_account}）。
 *
 * <p>{@code score} 是**缓存**，{@code t_credit_log} 才是账本。任何加减分都必须先写流水
 * 再重算分数（同一事务），否则一旦两步之间失败，分数与流水就对不上，申诉时无法自证。
 */
@Data
@TableName("t_credit_account")
public class CreditAccount {

    /** 用户 ID 即主键，由业务指定，不用自增 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 当前信用分，上下限 100/0 */
    private Integer score;

    /** 最后变动时刻 */
    private LocalDateTime updatedAt;

    /** 分值上限 */
    public static final int MAX_SCORE = 100;

    /** 分值下限 */
    public static final int MIN_SCORE = 0;

    /** 初始分 */
    public static final int INITIAL_SCORE = 100;

    /** 把任意分值夹到 [0, 100]。 */
    public static int clamp(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }
}
