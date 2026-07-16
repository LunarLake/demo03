package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_log")
public class Log {
    @TableId(type = IdType.AUTO)
    //日志主键ID
    private Long id;
    //触发操作的用户名
    private String username;
    //请求的系统资源路径/URL
    private String url;
    //客户端的远程IP地址
    private String ip;
    //操作时间戳
    private LocalDateTime timestamp;
}
