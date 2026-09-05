package com.careercompass.common.config;

import com.careercompass.common.security.CurrentUserIdArgumentResolver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 계약: 모든 응답은 {@code application/json; charset=UTF-8} (API_SPEC §공통 규약). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

  public WebConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
    this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(currentUserIdArgumentResolver);
  }

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (HttpMessageConverter<?> converter : converters) {
      if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
        jackson.setDefaultCharset(StandardCharsets.UTF_8);
        jackson.setSupportedMediaTypes(
            List.of(new MediaType("application", "json", StandardCharsets.UTF_8)));
      }
    }
  }
}
