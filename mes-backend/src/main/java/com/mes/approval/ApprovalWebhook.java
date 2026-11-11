package com.mes.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 自定义逻辑出口：流程结束 / 驳回 / 节点上的自定义服务任务，都会 POST 一个 JSON 给配置的地址。
 * 调用失败只记日志，不影响审批本身（审批结果以引擎为准）。
 */
@Component
public class ApprovalWebhook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhook.class);

    private final boolean enabled;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ApprovalWebhook(@Value("${mes.approval.webhook.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 是否成功发出 */
    public boolean post(String url, Map<String, Object> payload, String json) {
        if (!enabled || url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!ok) {
                log.warn("审批回调 {} 返回 {}", url, response.statusCode());
            }
            return ok;
        } catch (Exception error) {
            log.warn("审批回调 {} 失败：{}", url, error.getMessage());
            return false;
        }
    }
}
