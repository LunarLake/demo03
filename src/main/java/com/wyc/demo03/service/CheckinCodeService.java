package com.wyc.demo03.service;

import com.wyc.demo03.entity.CheckinCode;

/**
 * 签到码。
 *
 * <p>码与预约 1:1，生命周期独立于预约状态：预约进入任何终止态都必须 {@link #revoke}，
 * 撤销后既不能签到，也不能用于开门（文档 3.2.3）。
 */
public interface CheckinCodeService {

    /** 为预约签发 4 位码；已有码则原样返回（幂等）。 */
    String issue(Long reservationId);

    /** 撤销；无码或已撤销时静默返回（幂等）。 */
    void revoke(Long reservationId);

    /**
     * 校验码：存在、未撤销、与输入一致。
     *
     * @return 是否通过
     */
    boolean verify(Long reservationId, String code);

    /** 取码记录（含已撤销），没有则返回 null。 */
    CheckinCode findByReservationId(Long reservationId);
}
