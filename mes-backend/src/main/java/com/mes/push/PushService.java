package com.mes.push;

import com.mes.common.BizException;
import com.mes.push.api.PushMessage;
import com.mes.push.api.PushPort;
import com.mes.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 移动端推送：设备登记 + 通道分发。
 * 站内通知永远是主通道（sys_notice），推送只是「同一条消息顺带发到手机」。
 * 默认 provider = none，即没有厂商推送也不会报错，只是不到手机。
 */
@Service
public class PushService implements PushPort {

    private static final Set<String> PLATFORMS = Set.of("APP-ANDROID", "APP-IOS", "APP-HARMONY",
            "WECHAT-MP", "H5");

    private static final Set<String> PROVIDERS = Set.of("NONE", "JPUSH", "GETUI", "WECHAT");

    private final JdbcTemplate jdbcTemplate;
    private final List<PushSender> senders;
    private final String provider;

    public PushService(JdbcTemplate jdbcTemplate, List<PushSender> senders,
                       @Value("${mes.push.provider:none}") String provider) {
        this.jdbcTemplate = jdbcTemplate;
        this.senders = senders;
        this.provider = provider;
    }

    public record RegisterRequest(String platform, String provider, String token) {
    }

    /** 推送能力概览：移动端启动时用它决定要不要注册。 */
    public Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        String active = normalizeProvider(provider);
        config.put("provider", active);
        config.put("enabled", !"NONE".equals(active));
        config.put("platforms", List.of("APP-ANDROID", "APP-IOS", "APP-HARMONY", "WECHAT-MP"));
        config.put("hint", "NONE".equals(active)
                ? "未接入厂商推送；网页在线时可在消息中心开启浏览器桌面通知"
                : "已接入 " + active + "，设备登记后会随站内通知一起推送到手机");
        return config;
    }

    @Transactional
    public Map<String, Object> register(RegisterRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new BizException(1002, "缺少推送 token");
        }
        String platform = normalize(request.platform());
        if (!PLATFORMS.contains(platform)) {
            throw new BizException(1002, "推送平台只能是 " + String.join("、", PLATFORMS));
        }
        String token = request.token().trim();
        if (token.length() > 1024) {
            throw new BizException(1002, "推送 token 不能超过 1024 个字符");
        }
        String deviceProvider = normalizeProvider(request.provider() == null || request.provider().isBlank()
                ? provider : request.provider());
        if (!PROVIDERS.contains(deviceProvider)) {
            throw new BizException(1002, "推送通道只能是 " + String.join("、", PROVIDERS));
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_push_device (username, platform, provider, token, enabled, last_seen_at)
                values (?, ?, ?, ?, true, now())
                on conflict (username, platform, token)
                do update set provider = excluded.provider, enabled = true, last_seen_at = now()
                returning id
                """, Long.class, CurrentUser.name(), platform, deviceProvider, token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("platform", platform);
        result.put("provider", deviceProvider);
        result.put("enabled", true);
        return result;
    }

    @Transactional
    public void unregister(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(1002, "缺少推送 token");
        }
        jdbcTemplate.update("delete from sys_push_device where username = ? and token = ?",
                CurrentUser.name(), token.trim());
    }

    /** 我登记过的设备（token 打码，只给前端确认用） */
    public List<Map<String, Object>> myDevices() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, platform, provider, token, enabled,
                       to_char(last_seen_at, 'YYYY-MM-DD HH24:MI') as "lastSeenAt"
                from sys_push_device where username = ? order by id desc
                """, CurrentUser.name());
        for (Map<String, Object> row : rows) {
            row.put("token", mask(String.valueOf(row.get("token"))));
        }
        return rows;
    }

    /**
     * 站内通知落库后调用：按接收人查设备，交给对应通道发送。
     * 整段静默失败——推送发不出去不能影响业务本身。
     */
    @Override
    public void dispatch(String username, PushMessage message) {
        try {
            List<PushSender.PushDevice> devices = jdbcTemplate.query("""
                    select id, platform, provider, token from sys_push_device
                    where username = ? and enabled = true
                    """, (rs, rowNum) -> new PushSender.PushDevice(
                    rs.getLong("id"), rs.getString("platform"),
                    rs.getString("provider"), rs.getString("token")), username);
            if (devices.isEmpty()) {
                return;
            }
            Map<String, List<PushSender.PushDevice>> grouped = new LinkedHashMap<>();
            for (PushSender.PushDevice device : devices) {
                grouped.computeIfAbsent(normalizeProvider(device.provider()), key -> new ArrayList<>()).add(device);
            }
            for (Map.Entry<String, List<PushSender.PushDevice>> entry : grouped.entrySet()) {
                if ("NONE".equals(entry.getKey())) {
                    continue;
                }
                for (PushSender sender : senders) {
                    if (sender.supports(entry.getKey())) {
                        sender.send(new PushSender.PushRequest(username, message.category(), message.title(),
                                message.content(), message.refType(), message.refNo(), entry.getValue()));
                    }
                }
            }
        } catch (Exception ignored) {
            // 推送是辅助通道
        }
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return PROVIDERS.contains(upper) ? upper : "NONE";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String mask(String token) {
        if (token.length() <= 8) {
            return token;
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
