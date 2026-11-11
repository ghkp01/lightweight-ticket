package com.mes.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mes.common.BizException;
import com.mes.platform.config.ConfigService;
import com.mes.push.api.ReportPushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 经营日报推送：把当天经营摘要推到飞书 / 钉钉 / 企业微信的群机器人。
 *
 * <p>只读 rm_* 读模型，不碰任何业务写接口。Webhook 地址按密文配置保存，
 * 接口回传时只给掩码；发送失败只影响这一次推送，不影响业务。</p>
 */
@Service
public class ReportPushService implements ReportPushPort {

    private static final Logger log = LoggerFactory.getLogger(ReportPushService.class);
    private static final Set<String> TYPES = Set.of("FEISHU", "DINGTALK", "WECOM");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ReportPushService(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public record PushConfigRequest(String type, String url, Boolean enabled) {
    }

    /** 页面展示用：地址只回掩码，避免把群机器人 Webhook 明文发到前端 */
    public Map<String, Object> status() {
        String type = normalizeType(configService.stringValue("report.webhook.type", "FEISHU"));
        String url = configService.stringValue("report.webhook.url", "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("enabled", configService.booleanValue("report.push.enabled", false));
        result.put("hasUrl", !url.isBlank());
        result.put("urlMask", url.isBlank() ? "" : mask(url));
        result.put("hint", url.isBlank()
                ? "还没有配置 Webhook：在飞书/钉钉/企业微信群里添加自定义机器人后，把地址填进来"
                : "已配置 " + type + " 群机器人，每天 08:30 自动推送（需先打开开关）");
        return result;
    }

    public void save(PushConfigRequest request) {
        if (request == null) {
            throw new BizException(1002, "缺少推送配置");
        }
        String type = normalizeType(request.type());
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "群机器人类型只能是 FEISHU / DINGTALK / WECOM");
        }
        String url = request.url() == null ? "" : request.url().trim();
        if (!url.isEmpty() && !ConfigService.MASK.equals(url)
                && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException(1002, "Webhook 地址必须以 http:// 或 https:// 开头");
        }
        configService.update("report.webhook.type", type);
        // 地址留空或仍是掩码时 ConfigService 会保留原值，不会把已配置的地址清掉
        configService.update("report.webhook.url", url);
        configService.update("report.push.enabled", String.valueOf(Boolean.TRUE.equals(request.enabled())));
    }

    /** 手工推送：配置缺失或群里拒收时直接把原因抛给页面 */
    public Map<String, Object> sendNow() {
        String text = buildText();
        post(text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("text", text);
        result.put("message", "经营日报已推送到群机器人");
        return result;
    }

    @Override
    public boolean dailyPush() {
        if (!configService.booleanValue("report.push.enabled", false)) {
            return false;
        }
        try {
            post(buildText());
            return true;
        } catch (Exception error) {
            log.warn("经营日报推送失败：{}", error.getMessage());
            return false;
        }
    }

    /** 当天经营摘要：销售 / 生产 / 质量 / 交付 / 库存 / 安灯 */
    public String buildText() {
        BigDecimal todaySales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where created_at::date = current_date and status <> 'CANCELLED'
                  and currency = 'CNY'
                """);
        BigDecimal monthSales = amount("""
                select coalesce(sum(total_amount), 0) from rm_sales_order
                where date_trunc('month', created_at) = date_trunc('month', now()) and status <> 'CANCELLED'
                  and currency = 'CNY'
                """);
        long producing = count("select count(*) from rm_work_order where status in ('RELEASED', 'IN_PROGRESS')");
        long pendingShip = count("select count(*) from rm_sales_order where status = 'READY_TO_SHIP'");
        long overdue = count("""
                select count(*) from rm_work_order w
                join rm_sales_order so on so.id = w.sales_order_id
                where w.status not in ('FINISHED', 'CLOSED', 'CANCELLED')
                  and so.delivery_date is not null and so.delivery_date < current_date
                """);
        BigDecimal todayDone = amount("""
                select coalesce(sum(done_qty), 0) from rm_work_order where schedule_date = current_date
                """);
        Map<String, Object> quality = jdbcTemplate.queryForList("""
                select coalesce(sum(inspect_qty), 0) as qty, coalesce(sum(defect_qty), 0) as defect
                from rm_inspection where inspected_at::date = current_date and result is not null
                """).get(0);
        BigDecimal inspectQty = decimal(quality.get("qty"));
        BigDecimal defectQty = decimal(quality.get("defect"));
        BigDecimal passRate = inspectQty.signum() == 0 ? null
                : inspectQty.subtract(defectQty).multiply(BigDecimal.valueOf(100))
                .divide(inspectQty, 1, RoundingMode.HALF_UP);
        long lowStock = count("""
                select count(*) from rm_inventory i
                join rm_product p on p.id = i.product_id
                where p.safety_stock is not null and i.qty < p.safety_stock
                """);
        long andonOpen = count("select count(*) from rm_andon_call where status = 'OPEN'");

        StringBuilder text = new StringBuilder();
        text.append("【智造工单 · 经营日报】").append(LocalDate.now()).append('\n');
        text.append("━━━━━━━━━━━━━━").append('\n');
        text.append("销售：今日 ¥").append(money(todaySales))
                .append(" ｜ 本月 ¥").append(money(monthSales)).append('\n');
        text.append("生产：在制 ").append(producing).append(" 张 ｜ 今日排产完成 ")
                .append(money(todayDone)).append(" 件").append('\n');
        text.append("质量：今日检验 ").append(money(inspectQty)).append(" 件，不合格 ")
                .append(money(defectQty)).append(" 件");
        if (passRate != null) {
            text.append("（合格率 ").append(passRate).append("%）");
        }
        text.append('\n');
        text.append("交付：待发货 ").append(pendingShip).append(" 单 ｜ 逾期在制 ")
                .append(overdue).append(" 张").append('\n');
        text.append("库存：低库存 SKU ").append(lowStock).append(" 项 ｜ 安灯待接单 ")
                .append(andonOpen).append(" 条").append('\n');
        text.append("━━━━━━━━━━━━━━").append('\n');
        text.append("登录 MES →「经营大屏」查看明细");
        return text.toString();
    }

    private void post(String text) {
        String type = normalizeType(configService.stringValue("report.webhook.type", "FEISHU"));
        String url = configService.stringValue("report.webhook.url", "");
        if (url.isBlank()) {
            throw new BizException(1002, "还没有配置群机器人 Webhook 地址");
        }
        try {
            String payload = payload(type, text);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(1002, "群机器人返回 HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            int code = body.path("code").asInt(body.path("errcode").asInt(0));
            if (code != 0) {
                String message = body.path("msg").asText(body.path("errmsg").asText("发送失败"));
                throw new BizException(1002, "群机器人拒收：" + message);
            }
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException(1002, "推送失败：" + error.getMessage());
        }
    }

    private String payload(String type, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if ("FEISHU".equals(type)) {
                body.put("msg_type", "text");
                body.put("content", Map.of("text", text));
            } else {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", text));
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception error) {
            throw new BizException(1002, "组装推送内容失败：" + error.getMessage());
        }
    }

    private String normalizeType(String value) {
        return value == null ? "FEISHU" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String mask(String url) {
        if (url.length() <= 16) {
            return "****";
        }
        return url.substring(0, 12) + "****" + url.substring(url.length() - 6);
    }

    private String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
