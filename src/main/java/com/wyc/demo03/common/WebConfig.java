package com.wyc.demo03.common;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RequiredArgsConstructor
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roleInterceptor;
    private final LogInterceptor logInterceptor;
    private final CsrfInterceptor csrfInterceptor;
    private final AdminInterceptor adminInterceptor;
    // 注册拦截器，设置拦截路径和排除路径
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login", "/loginAction",
                        "/register", "/registerAction",
                        "/verityImg", "/error",
                        "/css/**", "/js/**", "/images/**",
                        "/**/*.css", "/**/*.js", "/**/*.jpg", "/**/*.png", "/**/*.gif", "/**/*.ico"
                );

        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/verityImg", "/error",
                        "/css/**", "/js/**", "/images/**",
                        "/**/*.css", "/**/*.js", "/**/*.jpg", "/**/*.png", "/**/*.gif", "/**/*.ico"
                );

        // CSRF 防护：全部路径（含登录/注册匿名表单），排除静态资源
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/verityImg", "/error",
                        "/css/**", "/js/**", "/images/**",
                        "/**/*.css", "/**/*.js", "/**/*.jpg", "/**/*.png", "/**/*.gif", "/**/*.ico"
                );

        // 管理员：会议室管理 + 审批管理 + 控制台总览 + 系统日志
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(
                        "/room/add", "/room/update", "/room/delete/**",
                        "/reservation/approve", "/reservation/approve-list",
                        "/reservation/reject", "/reservation/detail/**",
                        "/admin/**",
                        "/api/report-*"
                );
    }
}
