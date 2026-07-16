package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM t_user WHERE username=#{username}")
    User findByUsername(String username);
}
