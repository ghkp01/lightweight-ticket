package com.mes.workshop;

import com.mes.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车间大屏接口（免登录）。
 *
 * <p>挂在 {@code /api/public/screen/**}：{@code SecurityConfig} 里对 GET 的
 * {@code /api/public/**} 是放行的，车间电视打开 {@code /#/screen} 就能一直刷新，
 * 不依赖某个人的登录态。三块数据各自一个接口，前端按屏轮播、独立刷新。</p>
 */
@RestController
@RequestMapping("/api/public/screen")
public class ScreenController {

    private final ScreenService service;

    public ScreenController(ScreenService service) {
        this.service = service;
    }

    @GetMapping("/production")
    public ApiResponse production() {
        return ApiResponse.ok(service.production());
    }

    @GetMapping("/quality")
    public ApiResponse quality() {
        return ApiResponse.ok(service.quality());
    }

    @GetMapping("/andon")
    public ApiResponse andon() {
        return ApiResponse.ok(service.andon());
    }

    @GetMapping("/oee")
    public ApiResponse oee() {
        return ApiResponse.ok(service.oee());
    }
}
