package com.minipg.api.controller;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 루트 진입 시 랜딩으로 안내 + 정적 리소스 캐시 무효화(구버전 화면 캐싱 방지). */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/index.html";
    }

    /** 데모라 화면이 자주 바뀌므로 정적 HTML/JS를 캐시하지 않게 한다 — 배포 후 항상 최신본이 뜨도록. */
    @Bean
    public WebMvcConfigurer staticNoCache() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**")
                        .addResourceLocations("classpath:/static/")
                        .setCachePeriod(0);
            }
        };
    }
}
