package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 签到码（{@code t_checkin_code}）。
 *
 * <p>与预约 1:1。{@code revokedAt} 非空即失效 —— 取消、教师覆盖、超时释放、到期结束
 * 都必须撤销，撤销后既不能签到，也不能用于开门（文档 3.2.3）。
 */
@Data
@TableName("t_checkin_code")
public class CheckinCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 t_reservation.id（唯一） */
    private Long reservationId;

    /** 4 位数字码 */
    private String code;

    /** 签发时刻 */
    private LocalDateTime issuedAt;

    /** 撤销时刻；非空表示已失效 */
    private LocalDateTime revokedAt;

    /** 是否仍然有效：未撤销且未签发撤销时间。 */
    public boolean isActive() {
        return revokedAt == null;
    }
}
