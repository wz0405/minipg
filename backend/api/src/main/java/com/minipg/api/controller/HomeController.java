package com.minipg.api.controller;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 루트 진입 시 랜딩으로 안내 + 정적 리소스 캐시 무효화(구버전 화면 캐싱 방지). */
@Controller
public class HomeController {

    /**
     * 루트는 리다이렉트가 아니라 포워드로 랜딩을 서빙한다.
     * 리다이렉트로 절대 URL(Location)을 만들면, 앞단 리버스 프록시가 원래 포트를 전달하지
     * 않을 때 스프링이 포트를 떨궈 8090이 사라진다. 포워드는 Location 자체가 없어 그 문제가 없다.
     */
    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
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
