package com.seckill.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，映射 t_user 表。
 * 分层约定：Entity 只负责和数据库表一一对应，不参与接口出入参；
 * 接口入参用 DTO（带校验注解），出参用 VO（可脱敏）。
 */
@Data
@TableName("t_user")
public class User {

    /** 雪花算法生成的主键（IdType.ASSIGN_ID），数据库侧无自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名，唯一索引 uk_username 保证不重复 */
    private String username;

    /** BCrypt 单向加密后的密文，明文永远不落库、不出现在任何响应里 */
    private String password;

    private String nickname;

    private String phone;

    /** 1 正常 / 0 封禁 */
    private Integer status;

    /** 由数据库 DEFAULT CURRENT_TIMESTAMP 生成，插入时不填 */
    private LocalDateTime createTime;

    /** ON UPDATE CURRENT_TIMESTAMP 自动更新 */
    private LocalDateTime updateTime;
}
