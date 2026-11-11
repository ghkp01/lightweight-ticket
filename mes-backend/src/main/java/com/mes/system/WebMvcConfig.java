package com.mes.system;

import com.mes.platform.audit.OperLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OperLogInterceptor operLogInterceptor;

    public WebMvcConfig(OperLogInterceptor operLogInterceptor) {
        this.operLogInterceptor = operLogInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(operLogInterceptor).addPathPatterns("/api/**");
    }
}
