package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    //用户主键ID
    private Long id;
    //登录用户名
    @NotBlank
    private String username;
    //登录密码
    @NotBlank
    private String password;
    //用户真实姓名
    @NotBlank
    private String name;
    //角色权限区分：STUDENT-学生，TEACHER-教师，ADMIN-管理员（审批/控制台）
    private String role;
    //用户邮箱(用于接收抢占/变更通知)
    private String email;
}
