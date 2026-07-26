package com.neobank.sidecar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS by <em>pattern</em>, not by a fixed list of origins.
 *
 * <p>The sidecar serves its own UI same-origin, so this only matters when someone points a
 * separately-running dev server at it. A hard-coded port is the version of this that breaks the
 * first time anyone changes a compose port mapping.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origin-patterns:http://localhost:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS");
    }
}
