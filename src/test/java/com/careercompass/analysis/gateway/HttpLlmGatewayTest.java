package com.careercompass.analysis.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpLlmGatewayTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpLlmGateway gateway(int port) {
    return new HttpLlmGateway(
        new AiProperties("http://127.0.0.1:" + port, Duration.ofSeconds(2), 1, "v1", 100),
        new ObjectMapper(),
        new com.careercompass.common.privacy.PrivacyMasker(),
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  @Test
  @DisplayName("정상 응답 파싱 + 동일 입력은 캐시되어 업스트림을 한 번만 부른다 (#35)")
  void parsesAndCaches() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          calls.incrementAndGet();
          byte[] body =
              """
              {"type":"recruit","keywords":["백엔드","Spring","AWS"],"preferences":["우대"],
               "dueDate":"2026-12-31","formQuestions":[{"order":1,"question":"지원 동기","maxChars":500}],
               "usage":{"totalTokens":123}}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    LlmGateway.ParsedPosting first = gateway.parsePosting("채용", "본문");
    LlmGateway.ParsedPosting second = gateway.parsePosting("채용", "본문");

    assertThat(first.keywords()).containsExactly("백엔드", "Spring", "AWS");
    assertThat(first.formQuestions()).hasSize(1);
    assertThat(second).isEqualTo(first);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("AI 서버 5xx 지속 → 재시도 후 LLM_UNAVAILABLE(503) 로 변환 (#35)")
  void serverFailureMapsTo503() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("채용", "본문"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
  }

  @Test
  @DisplayName("AI 가 parsingFailed 를 주면 ParsingFailedException — 503 이 아니라 failed 확정")
  void parsingFailedFlag() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/parse-posting",
        exchange -> {
          byte[] body =
              "{\"parsingFailed\":true,\"reason\":\"키워드 부족\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    HttpLlmGateway gateway = gateway(server.getAddress().getPort());

    assertThatThrownBy(() -> gateway.parsePosting("채용", "본문"))
        .isInstanceOf(LlmGateway.ParsingFailedException.class);
  }
}
