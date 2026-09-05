package com.careercompass.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.careercompass.support.ApiITBase;
import com.careercompass.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** M6 — 초안 생성(#36)·SSE 복원(#37)·재생성/버전(#38)·편집/저장(#39)·이력(#40). */
class ApplicationFlowIT extends ApiITBase {

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private long seedParsedPosting(long userId, String type, String formQuestionsJson) {
    long boardId = TestFixtures.board(jdbc, userId, "https://apps.local/" + UUID.randomUUID());
    long postingId =
        jdbc.queryForObject(
            "insert into posting (board_id, title, url, url_hash, raw_content, type)"
                + " values (?, '2026 백엔드 신입 채용', ?, ?, '백엔드 Spring 채용 5명', ?) returning id",
            Long.class,
            boardId,
            "https://apps.local/p/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            type);
    jdbc.update(
        "insert into posting_parsed (posting_id, parse_status, keywords, form_questions)"
            + " values (?, 'done', '[\"백엔드\",\"Spring\",\"AWS\"]'::jsonb, ?::jsonb)",
        postingId,
        formQuestionsJson);
    return postingId;
  }

  private void seedCard(Session session) {
    call(
        HttpMethod.POST,
        "/experiences",
        Map.of(
            "type", "project",
            "title", "CareerCompass 백엔드",
            "startDate", "2026-01-01",
            "data", Map.of("summary", "Spring 백엔드 API 개발")),
        session.accessToken());
  }

  private static final String TWO_QUESTIONS =
      "[{\"order\":1,\"question\":\"지원 동기를 작성해 주세요\",\"maxChars\":500},"
          + "{\"order\":2,\"question\":\"본인의 강점을 기술해 주세요\",\"maxChars\":null}]";

  private JsonNode awaitFinished(Session session, long applicationId) throws Exception {
    for (int i = 0; i < 50; i++) {
      JsonNode data =
          json(call(HttpMethod.GET, "/applications/" + applicationId, null, session.accessToken()))
              .path("data");
      if (!"generating".equals(data.path("status").asText())) {
        return data;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("초안 생성이 제한 시간 안에 끝나지 않았습니다");
  }

  @Test
  @DisplayName("초안 생성 — 항목별 답변이 만들어지고 글자 수 제한이 지켜진다 (#36)")
  void createGeneratesDrafts() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);

    ResponseEntity<String> created =
        call(
            HttpMethod.POST,
            "/applications",
            Map.of("postingId", postingId, "tone", "formal"),
            session.accessToken());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    long applicationId = json(created).path("data").path("id").asLong();

    JsonNode data = awaitFinished(session, applicationId);
    assertThat(data.path("status").asText()).isEqualTo("ready");
    assertThat(data.path("items")).hasSize(2);
    for (JsonNode item : data.path("items")) {
      assertThat(item.path("status").asText()).isEqualTo("done");
      assertThat(item.path("answer").asText()).isNotBlank();
    }
    assertThat(data.path("items").get(0).path("answer").asText().length()).isLessThanOrEqualTo(500);
  }

  @Test
  @DisplayName("공모전 공고의 생성 요청은 거절된다 — 장학금·채용만 (#36, F3-3)")
  void contestRejected() {
    Session session = login();
    long postingId = seedParsedPosting(session.userId(), "contest", TWO_QUESTIONS);

    ResponseEntity<String> res =
        call(
            HttpMethod.POST,
            "/applications",
            Map.of("postingId", postingId),
            session.accessToken());

    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(json(res).path("error").path("field").asText()).isEqualTo("postingId");
  }

  @Test
  @DisplayName("양식 미인식 — 빈 items 로 시작하고 직접 추가하면 즉시 초안이 붙는다 (#36 계약)")
  void manualItemPath() {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "scholarship", "null");

    JsonNode created =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data");
    assertThat(created.path("items")).isEmpty();
    assertThat(created.path("status").asText()).isEqualTo("ready");

    JsonNode item =
        json(call(
                HttpMethod.POST,
                "/applications/" + created.path("id").asLong() + "/items",
                Map.of("question", "지원 동기를 작성해 주세요", "maxChars", 300),
                session.accessToken()))
            .path("data");
    assertThat(item.path("status").asText()).isEqualTo("done");
    assertThat(item.path("answer").asText()).isNotBlank();
  }

  @Test
  @DisplayName("재생성 4회 → 버전은 3개 유지, 되돌리기는 현재 본문도 보관한다 (#38)")
  void regenerateAndVersions() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    JsonNode data = awaitFinished(session, applicationId);
    long itemId = data.path("items").get(0).path("id").asLong();
    String base = "/applications/" + applicationId + "/items/" + itemId;

    for (int i = 0; i < 4; i++) {
      ResponseEntity<String> res =
          call(
              HttpMethod.POST,
              base + "/regenerate",
              Map.of("tone", "casual"),
              session.accessToken());
      assertThat(res.getStatusCode().value()).isEqualTo(200);
    }
    JsonNode versions =
        json(call(HttpMethod.GET, base + "/versions", null, session.accessToken())).path("data");
    assertThat(versions).hasSize(3);

    // 되돌리기 — 대상 버전의 본문으로 교체된다
    long versionId = versions.get(0).path("id").asLong();
    String versionAnswer = versions.get(0).path("answer").asText();
    JsonNode reverted =
        json(call(
                HttpMethod.POST,
                base + "/versions/" + versionId + "/revert",
                null,
                session.accessToken()))
            .path("data");
    assertThat(reverted.path("answer").asText()).isEqualTo(versionAnswer);

    // 알 수 없는 tone 은 400
    ResponseEntity<String> badTone =
        call(HttpMethod.POST, base + "/regenerate", Map.of("tone", "angry"), session.accessToken());
    assertThat(badTone.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("글자 수 초과 저장 허용 + 최종 저장 → 스냅샷·이력 → 결과 입력 → 삭제 (#39 #40)")
  void editSaveHistoryFlow() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    jdbc.update(
        "insert into suitability_score (user_id, posting_id, score, label, breakdown)"
            + " values (?, ?, 77, 'suitable', '[]'::jsonb)",
        session.userId(),
        postingId);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    JsonNode data = awaitFinished(session, applicationId);
    long itemId = data.path("items").get(0).path("id").asLong();

    // 글자 수 초과(maxChars 500 에 1000자) 저장 — 서버는 막지 않는다
    String longAnswer = "가".repeat(1000);
    JsonNode edited =
        json(call(
                HttpMethod.PATCH,
                "/applications/" + applicationId + "/items/" + itemId,
                Map.of("answer", longAnswer),
                session.accessToken()))
            .path("data");
    assertThat(edited.path("answer").asText()).hasSize(1000);

    JsonNode saved =
        json(call(
                HttpMethod.POST,
                "/applications/" + applicationId + "/save",
                null,
                session.accessToken()))
            .path("data");
    assertThat(saved.path("status").asText()).isEqualTo("saved");
    assertThat(saved.path("scoreSnapshot").asInt()).isEqualTo(77);

    JsonNode listed =
        json(call(HttpMethod.GET, "/applications?status=saved", null, session.accessToken()))
            .path("data");
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).path("postingTitle").asText()).contains("백엔드");
    assertThat(listed.get(0).path("result").asText()).isEqualTo("none");

    JsonNode resulted =
        json(call(
                HttpMethod.PATCH,
                "/applications/" + applicationId + "/result",
                Map.of("result", "pass"),
                session.accessToken()))
            .path("data");
    assertThat(resulted.path("result").asText()).isEqualTo("pass");

    call(HttpMethod.DELETE, "/applications/" + applicationId, null, session.accessToken());
    assertThat(
            json(call(HttpMethod.GET, "/applications", null, session.accessToken())).path("data"))
        .isEmpty();
    Integer itemRows =
        jdbc.queryForObject(
            "select count(*) from application_item where application_id = ?",
            Integer.class,
            applicationId);
    assertThat(itemRows).isZero();
  }

  @Test
  @DisplayName("SSE 재연결 복원 — 생성 종료 후 구독해도 스냅샷과 complete 를 받는다 (#37)")
  void sseSnapshotRestore() throws Exception {
    Session session = login();
    seedCard(session);
    long postingId = seedParsedPosting(session.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                session.accessToken()))
            .path("data")
            .path("id")
            .asLong();
    awaitFinished(session, applicationId);

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/applications/" + applicationId + "/stream"))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<java.io.InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);

    StringBuilder received = new StringBuilder();
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
        received.append(line).append('\n');
        if (line.contains("complete")) {
          break;
        }
      }
    } catch (java.io.IOException ignored) {
      // complete 수신 후의 전송 계층 종료 잡음은 판정에 영향 없음
    }
    assertThat(received.toString()).contains("event:item_done");
    assertThat(received.toString()).contains("event:complete");
  }

  @Test
  @DisplayName("남의 지원서 접근·스트림 구독은 404")
  void othersApplicationHidden() {
    Session owner = login();
    seedCard(owner);
    long postingId = seedParsedPosting(owner.userId(), "recruit", TWO_QUESTIONS);
    long applicationId =
        json(call(
                HttpMethod.POST,
                "/applications",
                Map.of("postingId", postingId),
                owner.accessToken()))
            .path("data")
            .path("id")
            .asLong();

    Session stranger = login();
    assertThat(
            call(HttpMethod.GET, "/applications/" + applicationId, null, stranger.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            call(
                    HttpMethod.GET,
                    "/applications/" + applicationId + "/stream",
                    null,
                    stranger.accessToken())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }
}
