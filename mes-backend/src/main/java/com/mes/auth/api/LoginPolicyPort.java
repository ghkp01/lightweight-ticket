package com.mes.auth.api;

/**
 * 登录安全策略对外契约：用户管理页改密码、解锁账号时要用，
 * 但不需要知道失败计数怎么累加、刷新令牌怎么轮换。
 */
public interface LoginPolicyPort {

    /** 按当前策略校验密码强度，不合规抛 {@code BizException(1002)} */
    void validatePassword(String rawPassword);

    /** 管理员解锁：清掉失败次数与锁定时间 */
    void unlock(String username);
}
