package com.mes.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认的空通道：不接任何第三方推送时启作用。
 * 站内通知照常落库、前端铃铛与移动端通知中心照样能看到，只是不发手机推送。
 * 接入真实通道时新增一个 PushSender 实现（比如 JpushSender），把配置里的
 * mes.push.provider 改成对应值即可，业务代码不用动。
 */
@Component
public class NoopPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(NoopPushSender.class);

    @Override
    public boolean supports(String provider) {
        return provider == null || provider.isBlank() || "NONE".equalsIgnoreCase(provider);
    }

    @Override
    public void send(PushRequest request) {
        log.debug("未接入推送通道，跳过推送：user={} title={}", request.username(), request.title());
    }
}
