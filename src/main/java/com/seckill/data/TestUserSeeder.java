package com.seckill.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestUserSeeder implements CommandLineRunner {

    private final UserMapper userMapper;

    @Value("${seckill.seed.user-count:0}")
    private int userCount;

    @Override
    public void run(String... args) {
        if (userCount <= 0) {
            return;
        }
        long start = System.currentTimeMillis();

        // 先清理旧的测试用户，保证重复执行不产生脏数据
        int deleted = userMapper.delete(
                new LambdaQueryWrapper<User>().likeRight(User::getUsername, "test_"));

        // 所有测试用户共用同一份 BCrypt 哈希，避免 10 万次哈希运算（一次约 100ms）
        String pwdHash = new BCryptPasswordEncoder().encode("123456");

        List<User> batch = new ArrayList<>(1000);
        int total = 0;
        for (int i = 1; i <= userCount; i++) {
            User user = new User();
            user.setUsername("test_" + i);
            user.setPassword(pwdHash);
            user.setNickname("压测用户" + i);
            user.setStatus(1);
            batch.add(user);
            if (batch.size() == 1000) {
                Db.saveBatch(batch, 1000);
                total += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            Db.saveBatch(batch, 1000);
            total += batch.size();
        }
        log.info("测试用户种子完成: 清理 {} 条, 插入 {} 条, 耗时 {} ms",
                deleted, total, System.currentTimeMillis() - start);
    }
}
