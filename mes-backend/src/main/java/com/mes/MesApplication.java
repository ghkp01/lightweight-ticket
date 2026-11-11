package com.mes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目用 JWT 自建登录体系，不需要 Spring Security 的内存用户，
 * 排除掉 UserDetailsServiceAutoConfiguration，避免启动日志里冒出一句
 * "Using generated security password: ..."（那串密码没人用得上，但看着像漏配）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class MesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MesApplication.class, args);
    }
}
