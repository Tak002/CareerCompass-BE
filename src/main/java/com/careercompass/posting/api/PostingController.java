package com.careercompass.posting.api;

import com.careercompass.analysis.domain.SuitabilityScore;
import com.careercompass.common.api.ApiResponse;
import com.careercompass.common.paging.CursorPage;
import com.careercompass.common.paging.PageLimit;
import com.careercompass.common.security.CurrentUserId;
import com.careercompass.posting.application.PostingQueryService;
import com.careercompass.posting.application.PostingQueryService.PostingCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "5-1. 공고", description = "API_SPEC §5 — 목록·상세·읽음/북마크. parseStatus 로 분석 중/실패 구분 (#27)")
@RestController
@RequestMapping("/postings")
public class PostingController {

  private final PostingQueryService service;

  public PostingController(PostingQueryService service) {
    this.service = service;
  }

  public record BoardRef(Long id, String name) {}

  public record CardResponse(
      Long id,
      String title,
      String type,
      BoardRef board,
      LocalDate dueDate,
      Instant collectedAt,
      String parseStatus,
      Integer score,
      String scoreLabel,
      String scoreUnavailableReason,
      boolean isRead,
      boolean isBookmarked) {

    static CardResponse from(PostingCard card) {
      return new CardResponse(
          card.id(),
          card.title(),
          card.type(),
          new BoardRef(card.boardId(), card.boardName()),
          card.dueDate(),
          card.collectedAt(),
          card.parseStatus(),
          card.score(),
          card.scoreLabel(),
          card.scoreUnavailableReason(),
          card.isRead(),
          card.isBookmarked());
    }
  }

  public record ListResponse(List<CardResponse> postings, String nextCursor) {}

  @Operation(
      summary = "공고 목록",
      description =
          "마감 공고 기본 숨김(includeExpired 로 표시). due_asc 는 마감일 있는 공고만, score_desc 는 점수 산출된 공고만.")
  @GetMapping
  public ApiResponse<ListResponse> list(
      @CurrentUserId Long userId,
      @RequestParam(required = false) List<Long> boardIds,
      @RequestParam(name = "boardIds[]", required = false) List<Long> boardIdsBracket,
      @RequestParam(required = false) List<String> types,
      @RequestParam(name = "types[]", required = false) List<String> typesBracket,
      @RequestParam(required = false) Integer minScore,
      @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false, defaultValue = "false") boolean bookmarkedOnly,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueAfter,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueBefore,
      @RequestParam(required = false, defaultValue = "false") boolean includeExpired,
      @RequestParam(required = false, defaultValue = "collected_desc") String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CursorPage<PostingCard> page =
        service.list(
            userId,
            new PostingQueryService.ListFilter(
                merge(boardIds, boardIdsBracket),
                merge(types, typesBracket),
                minScore,
                unreadOnly,
                bookmarkedOnly,
                q,
                dueAfter,
                dueBefore,
                includeExpired),
            sort,
            cursor,
            PageLimit.resolve(limit));
    return ApiResponse.success(
        new ListResponse(
            page.items().stream().map(CardResponse::from).toList(), page.nextCursor()));
  }

  private <T> List<T> merge(List<T> plain, List<T> bracket) {
    if (plain == null && bracket == null) {
      return null;
    }
    List<T> merged = new ArrayList<>();
    if (plain != null) {
      merged.addAll(plain);
    }
    if (bracket != null) {
      merged.addAll(bracket);
    }
    return merged;
  }

  public record SuitabilityResponse(
      int score,
      String label,
      List<Map<String, Object>> breakdown,
      String strengthComment,
      String weaknessComment) {

    static SuitabilityResponse from(SuitabilityScore score) {
      return score == null
          ? null
          : new SuitabilityResponse(
              score.score(),
              score.label(),
              score.breakdown(),
              score.strengthComment(),
              score.weaknessComment());
    }
  }

  public record ParsedResponse(
      List<String> keywords,
      Map<String, Object> qualifications,
      List<String> preferences,
      List<Map<String, Object>> formQuestions) {}

  public record SimilarResponse(Long id, String title, Integer score, LocalDate dueDate) {}

  public record DetailResponse(
      Long id,
      String title,
      String rawContent,
      String url,
      String type,
      LocalDate dueDate,
      Instant collectedAt,
      String parseStatus,
      ParsedResponse parsed,
      SuitabilityResponse suitability,
      String scoreUnavailableReason,
      boolean isRead,
      boolean isBookmarked,
      boolean canGenerateApplication,
      List<SimilarResponse> similar) {}

  @Operation(
      summary = "공고 상세",
      description =
          "parsed 는 parseStatus=done 일 때만, suitability 는 산출된 경우에만 내려간다. canGenerateApplication 은 장학금·채용만 true.")
  @GetMapping("/{id}")
  public ApiResponse<DetailResponse> detail(@CurrentUserId Long userId, @PathVariable Long id) {
    PostingQueryService.Detail detail = service.detail(userId, id);
    ParsedResponse parsed =
        detail.parsed() == null
            ? null
            : new ParsedResponse(
                detail.parsed().keywords(),
                detail.parsed().qualifications(),
                detail.parsed().preferences(),
                detail.parsed().formQuestions());
    return ApiResponse.success(
        new DetailResponse(
            detail.posting().id(),
            detail.posting().title(),
            detail.posting().rawContent(),
            detail.posting().url(),
            detail.posting().type(),
            detail.posting().dueDate(),
            detail.posting().collectedAt(),
            detail.parseStatus(),
            parsed,
            SuitabilityResponse.from(detail.suitability()),
            detail.scoreUnavailableReason(),
            detail.isRead(),
            detail.isBookmarked(),
            detail.canGenerateApplication(),
            detail.similar().stream()
                .map(s -> new SimilarResponse(s.id(), s.title(), s.score(), s.dueDate()))
                .toList()));
  }

  @Operation(summary = "읽음 처리", description = "멱등.")
  @PostMapping("/{id}/read")
  public ApiResponse<Void> read(@CurrentUserId Long userId, @PathVariable Long id) {
    service.markRead(userId, id);
    return ApiResponse.success(null);
  }

  @Operation(summary = "북마크 등록", description = "멱등.")
  @PostMapping("/{id}/bookmark")
  public ApiResponse<Void> bookmark(@CurrentUserId Long userId, @PathVariable Long id) {
    service.bookmark(userId, id, true);
    return ApiResponse.success(null);
  }

  @Operation(summary = "북마크 해제", description = "멱등.")
  @DeleteMapping("/{id}/bookmark")
  public ApiResponse<Void> unbookmark(@CurrentUserId Long userId, @PathVariable Long id) {
    service.bookmark(userId, id, false);
    return ApiResponse.success(null);
  }
}
