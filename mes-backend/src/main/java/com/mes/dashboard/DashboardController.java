package com.mes.dashboard;

import com.mes.common.ApiResponse;
import com.mes.common.BizException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** from/to 可选：不传就按今天；经营大屏传区间时额外返回环比与同比。 */
    @GetMapping("/summary")
    public ApiResponse summary(@RequestParam(required = false) String from,
                               @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.summary(date(from), date(to)));
    }

    /** 大屏实时数据流（SSE）：连接后立即推一次，之后每 10 秒推一次 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return service.stream(date(from), date(to));
    }

    private LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw new BizException(1002, "日期格式应为 yyyy-MM-dd");
        }
    }
}
