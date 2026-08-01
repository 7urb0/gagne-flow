package com.gagneflow.config;

import java.util.List;

import com.gagneflow.config.security.CurrentUserArgumentResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig
implements WebMvcConfigurer {
    @Autowired
    private CurrentUserArgumentResolver currentUserArgumentResolver;
    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this.currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // P0修复: 补充 /api/lesson_plan 和 /api/rag/query 的限流路径注册
        // 原先 RateLimitInterceptor 中已实现这两个路径的限流逻辑 (LESSON_LIMIT=2/min, RAG_LIMIT=30/min)
        // 但因未在此注册导致拦截器永远不会被触发，限流形同虚设
        registry.addInterceptor((HandlerInterceptor)this.rateLimitInterceptor)
                .addPathPatterns("/api/auth/login", "/api/auth/register",
                        "/api/chat_stream", "/api/lesson_plan", "/api/rag/query");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(new String[]{"/**"}).addResourceLocations(new String[]{"classpath:/static/"});
    }
}
