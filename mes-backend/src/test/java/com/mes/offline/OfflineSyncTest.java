package com.mes.offline;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线重传：同一幂等键只执行一次，重放原样返回上次响应；无键请求不缓存；记录可按保留天数清理。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OfflineSyncTest {

    @Autowired
    private OfflineSyncService service;

    @Autowired
    private JdbcTemplate jdbc;

    private IdempotencyFilter filter;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(service);
    }

    private String newKey() {
        return "test-" + System.nanoTime();
    }

    private MockHttpServletRequest post(String uri, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("Content-Type", "application/json");
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        return request;
    }

    /** 下游处理器：记录被调用次数，并把次数写进响应体 */
    private FilterChain downstream(AtomicInteger calls, int status) {
        return (req, res) -> {
            int index = calls.incrementAndGet();
            jakarta.servlet.http.HttpServletResponse response = (jakarta.servlet.http.HttpServletResponse) res;
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":0,\"message\":\"ok\",\"data\":{\"call\":" + index + "}}");
        };
    }

    @Test
    @DisplayName("同一个幂等键第二次到达直接返回上次结果，业务不再执行")
    void replaySameKey() throws Exception {
        String key = newKey();
        AtomicInteger calls = new AtomicInteger();

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(post("/api/work-orders/1/report", key), first, downstream(calls, 200));
        assertEquals(1, calls.get(), "首次应执行");
        String body = first.getContentAsString();
        assertTrue(body.contains("\"call\":1"), body);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(post("/api/work-orders/1/report", key), second, downstream(calls, 200));
        assertEquals(1, calls.get(), "重放不应再执行下游");
        assertEquals(body, second.getContentAsString(), "重放应原样返回首次响应");
        assertEquals("true", second.getHeader(IdempotencyFilter.REPLAY_HEADER));
    }

    @Test
    @DisplayName("不同幂等键互不影响")
    void differentKeysRunSeparately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(post("/api/work-orders/1/report", newKey()), new MockHttpServletResponse(),
                downstream(calls, 200));
        filter.doFilter(post("/api/work-orders/1/report", newKey()), new MockHttpServletResponse(),
                downstream(calls, 200));
        assertEquals(2, calls.get(), "不同的键应各执行一次");
    }

    @Test
    @DisplayName("没带幂等键的请求不落库，5xx 也不缓存")
    void skipWithoutKeyAndServerError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(post("/api/work-orders/1/report", null), new MockHttpServletResponse(),
                downstream(calls, 200));
        assertEquals(1, calls.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_offline_key", Integer.class),
                "无键请求不该写幂等表");

        String key = newKey();
        MockHttpServletResponse failed = new MockHttpServletResponse();
        filter.doFilter(post("/api/work-orders/1/report", key), failed, downstream(calls, 500));
        assertNull(service.cached(key), "5xx 不缓存，手机下次重试仍有机会成功");
    }

    @Test
    @DisplayName("读取幂等键请求头以外的请求不受影响")
    void getRequestIgnored() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/work-orders");
        get.addHeader(IdempotencyFilter.HEADER, newKey());
        filter.doFilter(get, new MockHttpServletResponse(), downstream(calls, 200));
        filter.doFilter(get, new MockHttpServletResponse(), downstream(calls, 200));
        assertEquals(2, calls.get(), "GET 不参与幂等");
    }

    @Test
    @DisplayName("超过保留天数的记录被后台任务清理")
    void cleanupExpired() {
        String key = newKey();
        service.remember(key, "POST", "/api/work-orders/1/report", 200, "{\"code\":0}");
        assertNotNull(service.cached(key));
        jdbc.update("""
                update sys_offline_key set created_at = now() - (? || ' days')::interval
                where idem_key = ?
                """, service.retentionDays() + 1, key);
        int removed = service.cleanup();
        assertTrue(removed >= 1, "应至少清掉这条过期记录");
        assertNull(service.cached(key));
    }

    @Test
    @DisplayName("状态接口给移动端返回开关与上限")
    void status() {
        Map<String, Object> status = service.status();
        assertEquals(true, status.get("enabled"));
        assertTrue(((Number) status.get("queueLimit")).intValue() >= 20);
        assertTrue(((Number) status.get("retentionDays")).intValue() >= 1);
        assertNotNull(status.get("serverTime"));
    }
}
