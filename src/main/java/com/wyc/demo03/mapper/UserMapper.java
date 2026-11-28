package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM t_user WHERE username=#{username}")
    User findByUsername(String username);

    /**
     * 对用户行加排他锁，把"同一用户的并发提交"串行化。
     *
     * <p>每日自动通过上限是"读计数 → 判断 → 写入"三步：不加锁时两个标签页可以
     * 同时读到 count=2 并各自通过，实际落地 4 笔。锁随提交事务一起释放。
     * 只锁自己那一行，不影响其他用户。
     */
    @Select("SELECT id FROM t_user WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
