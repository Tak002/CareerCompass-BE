package com.careercompass.auth.oauth;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GoogleOAuthClient implements OAuthClient {

  private final RestClient restClient;

  public GoogleOAuthClient(RestClient.Builder builder) {
    this.restClient = builder.baseUrl("https://openidconnect.googleapis.com").build();
  }

  @Override
  public String provider() {
    return "google";
  }

  @Override
  public OAuthProfile fetch(String accessToken) {
    JsonNode body;
    try {
      body =
          restClient
              .get()
              .uri("/v1/userinfo")
              .header("Authorization", "Bearer " + accessToken)
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        throw new BusinessException(ErrorCode.AUTH_INVALID, "구글 토큰 검증에 실패했습니다");
      }
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "구글 API 호출에 실패했습니다");
    }
    if (body == null || !body.path("sub").isTextual()) {
      throw new BusinessException(ErrorCode.AUTH_INVALID, "구글 토큰 검증에 실패했습니다");
    }
    String email = body.path("email").isTextual() ? body.path("email").asText() : null;
    String name = body.path("name").isTextual() ? body.path("name").asText() : null;
    return new OAuthProfile(body.path("sub").asText(), email, name);
  }
}
