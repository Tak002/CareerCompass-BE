package com.careercompass.analysis.gateway;

import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * CareerCompass-AI 서버 HTTP 게이트웨이 (#35) — 타임아웃·재시도(백오프)·rate limit/장애의 503(LLM_UNAVAILABLE) 변환·동일 입력
 * 캐싱·일일 호출 예산·응답 스키마 검증·프롬프트 버전 헤더를 한 곳에서 강제한다.
 *
 * <p>개인정보 경계(#48): 이 게이트웨이로 나가는 요청에는 공고 본문·경험 요약만 싣고, 이름·이메일 등 식별자는 싣지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.base-url")
public class HttpLlmGateway implements LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(HttpLlmGateway.class);

  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private final com.careercompass.common.privacy.PrivacyMasker privacyMasker;
  private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
  private final RestClient restClient;
  private final Map<String, ParsedPosting> parseCache = new ConcurrentHashMap<>();
  private final AtomicInteger dailyCalls = new AtomicInteger();
  private final AtomicReference<LocalDate> budgetDay = new AtomicReference<>(LocalDate.now());

  public HttpLlmGateway(
      AiProperties properties,
      ObjectMapper objectMapper,
      com.careercompass.common.privacy.PrivacyMasker privacyMasker,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.privacyMasker = privacyMasker;
    this.meterRegistry = meterRegistry;
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
    requestFactory.setReadTimeout(properties.timeout());
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("X-Prompt-Version", properties.promptVersion())
            .build();
  }

  @Override
  public ParsedPosting parsePosting(String title, String rawContent) {
    String cacheKey = hash(properties.promptVersion() + "|" + title + "|" + rawContent);
    ParsedPosting cached = parseCache.get(cacheKey);
    if (cached != null) {
      return cached; // 같은 공고를 두 번 파싱하지 않는다
    }
    JsonNode node =
        callWithRetry("/v1/parse-posting", Map.of("title", title, "rawContent", rawContent));
    if (Boolean.TRUE.equals(node.path("parsingFailed").asBoolean(false))) {
      throw new ParsingFailedException(node.path("reason").asText("파싱 실패"));
    }
    if (!node.path("keywords").isArray()) {
      throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "AI 응답 스키마가 올바르지 않습니다");
    }
    List<String> keywords = new ArrayList<>();
    node.path("keywords").forEach(k -> keywords.add(k.asText()));
    if (keywords.size() < 3) {
      throw new ParsingFailedException("핵심 키워드가 3개 미만입니다");
    }
    List<FormQuestion> questions = new ArrayList<>();
    node.path("formQuestions")
        .forEach(
            q ->
                questions.add(
                    new FormQuestion(
                        q.path("order").asInt(),
                        q.path("question").asText(),
                        q.path("maxChars").isNumber() ? q.path("maxChars").asInt() : null)));
    ParsedPosting parsed =
        new ParsedPosting(
            textOrNull(node, "type"),
            keywords,
            textOrNull(node, "qualificationYear"),
            textOrNull(node, "qualificationGpa"),
            textOrNull(node, "qualificationMajor"),
            toStringList(node.path("preferences")),
            node.path("dueDate").isTextual()
                ? LocalDate.parse(node.path("dueDate").asText())
                : null,
            questions);
    parseCache.put(cacheKey, parsed);
    return parsed;
  }

  @Override
  public Comments comments(CommentContext context) {
    JsonNode node =
        callWithRetry(
            "/v1/comments",
            Map.of(
                "matchedKeywords", context.matchedKeywords(),
                "missingQualifications", context.missingQualifications(),
                "matchedPreferences", context.matchedPreferences(),
                "topExperienceTitle",
                    context.topExperienceTitle() == null ? "" : context.topExperienceTitle()));
    return new Comments(node.path("strength").asText(null), node.path("weakness").asText(null));
  }

  @Override
  public String draftAnswer(
      String question,
      Integer maxChars,
      String tone,
      String postingTitle,
      List<String> keywords,
      List<String> experienceSummaries) {
    // 개인정보 경계 (#48): 경험 요약·과거 자소서 발췌는 마스킹 후에만 외부로 나간다
    JsonNode node =
        callWithRetry(
            "/v1/draft-answer",
            Map.of(
                "question",
                question,
                "maxChars",
                maxChars == null ? 0 : maxChars,
                "tone",
                tone == null ? "formal" : tone,
                "postingTitle",
                postingTitle,
                "keywords",
                keywords,
                "experienceSummaries",
                privacyMasker.maskAll(experienceSummaries)));
    String answer = node.path("answer").asText(null);
    if (answer == null || answer.isBlank()) {
      throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "AI 응답 스키마가 올바르지 않습니다");
    }
    return answer;
  }

  private JsonNode callWithRetry(String path, Object body) {
    enforceBudget();
    RuntimeException last = null;
    for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
      try {
        String response = restClient.post().uri(path).body(body).retrieve().body(String.class);
        dailyCalls.incrementAndGet();
        meterRegistry.counter("careercompass.ai.calls", "outcome", "success").increment();
        JsonNode node = objectMapper.readTree(response);
        logUsage(path, node);
        return node;
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError()) {
          last = e;
          backoff(attempt);
          continue;
        }
        throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "AI 서버 응답 오류: " + e.getStatusCode());
      } catch (Exception e) {
        last = new RuntimeException(e);
        backoff(attempt);
      }
    }
    log.warn("AI 게이트웨이 호출 실패: {} ({})", path, last == null ? "?" : last.toString());
    meterRegistry.counter("careercompass.ai.calls", "outcome", "failure").increment();
    throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
  }

  private void enforceBudget() {
    LocalDate today = LocalDate.now();
    if (!today.equals(budgetDay.get())) {
      budgetDay.set(today);
      dailyCalls.set(0);
    }
    if (dailyCalls.get() >= properties.dailyCallBudget()) {
      log.warn("AI 일일 호출 예산({}) 초과", properties.dailyCallBudget());
      throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "AI 호출 한도를 초과했습니다");
    }
  }

  private void logUsage(String path, JsonNode node) {
    if (node.has("usage")) {
      log.info(
          "ai-usage path={} tokens={} calls_today={}",
          path,
          node.path("usage").path("totalTokens").asLong(-1),
          dailyCalls.get());
    }
  }

  private void backoff(int attempt) {
    try {
      Thread.sleep(300L * (1L << attempt));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String textOrNull(JsonNode node, String field) {
    return node.path(field).isTextual() ? node.path(field).asText() : null;
  }

  private List<String> toStringList(JsonNode array) {
    List<String> list = new ArrayList<>();
    if (array.isArray()) {
      array.forEach(item -> list.add(item.asText()));
    }
    return list;
  }

  private String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
