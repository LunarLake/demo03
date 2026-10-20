package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.CreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    /**
     * 建号 + 加行锁，一条语句完成（{@code INSERT ... ON DUPLICATE KEY UPDATE}）。
     *
     * <p>信用分调整必须**先锁账户、再算账本**：两个并发事务各自 SUM 时看不到对方未提交的流水，
     * 后写者会用不完整的总和覆盖缓存分数（BUG-016），而错误分数还会参与预约准入判断。
     *
     * <p><b>为什么不用「先 INSERT 再 SELECT FOR UPDATE」</b>：INSERT 的重复键检查会先拿共享锁，
     * 随后的 SELECT FOR UPDATE 需要排他锁，两个并发事务会形成经典 S→X 升级死锁（实测必现）。
     * upsert 一开始就拿排他锁，天然串行化，没有升级过程。
     */
    @Insert("INSERT INTO t_credit_account (user_id, score, updated_at) "
            + "VALUES (#{userId}, 100, NOW()) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensureAndLock(@Param("userId") Long userId);
}
