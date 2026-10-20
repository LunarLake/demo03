package com.wyc.demo03.service;

import com.wyc.demo03.entity.Appeal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 申诉工单（文档 3.2.2）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>独立于预约状态机，**不占用房间单元**；</li>
 *   <li>同一目标只能申诉一次（库层唯一键 + 业务前置检查）；</li>
 *   <li>提交后 3 个工作日内应给出书面结论；</li>
 *   <li>复核人必须是**未参与原审核**的人，且不能是自己。</li>
 * </ul>
 *
 * <p>申诉成立只撤销信用扣分，**不恢复已释放的单元** —— 房间早就可能被别人订走了。
 */
public interface AppealService {

    /**
     * 提交申诉。
     *
     * @throws IllegalArgumentException 目标不存在/不属于本人/已超 7 日/已申诉过
     */
    Appeal submit(Long userId, Appeal.TargetType type, Long targetId, String reason, String evidence);

    /** 我提交的工单。 */
    List<Map<String, Object>> listMine(Long userId);

    /** 待复核工单（按应办结时间升序，超期的排前面）。 */
    List<Map<String, Object>> listPending();

    /** 已办结工单。 */
    List<Map<String, Object>> listDecided();

    /**
     * 复核。
     *
     * @param status 只能是 UPHELD / REVOKED / PARTIAL
     * @throws IllegalArgumentException 状态非法、工单不存在或已办结
     * @throws IllegalStateException    自审（复核人是申请人或原审核人）
     */
    Appeal decide(Long appealId, Long reviewerId, Appeal.Status status, String note);

    /** 超过应办结时间仍未处理的数量。 */
    int countOverdue(LocalDateTime now);
}
