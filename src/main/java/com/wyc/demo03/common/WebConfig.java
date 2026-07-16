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
    private final TeacherInterceptor teacherInterceptor;
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

        registry.addInterceptor(teacherInterceptor)
                .addPathPatterns(
                        "/room/add", "/room/update", "/room/delete/**",
                        "/reservation/approve**", "/reservation/reject",
                        "/reservation/detail/**",
                        "/admin/**",
                        "/api/report-*"
                );
    }
}
