package com.careercompass.application.application;

import com.careercompass.analysis.domain.SuitabilityScoreRepository;
import com.careercompass.application.domain.Application;
import com.careercompass.application.domain.ApplicationItem;
import com.careercompass.application.domain.ApplicationItemRepository;
import com.careercompass.application.domain.ApplicationItemVersion;
import com.careercompass.application.domain.ApplicationItemVersionRepository;
import com.careercompass.application.domain.ApplicationRepository;
import com.careercompass.board.domain.BoardRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingParsed;
import com.careercompass.posting.domain.PostingParsedRepository;
import com.careercompass.posting.domain.PostingRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지원서 생성·편집·이력 (§6, #36~#40). 편집 충돌은 「마지막 쓰기 우선」(30초 자동 임시 저장 전제, #39 결정). */
@Service
public class ApplicationService {

  public static final Set<String> TONES = Set.of("formal", "casual", "confident");
  private static final Set<String> GENERATABLE_TYPES = Set.of("scholarship", "recruit");
  private static final int MAX_VERSIONS = 3;

  private final ApplicationRepository applicationRepository;
  private final ApplicationItemRepository itemRepository;
  private final ApplicationItemVersionRepository versionRepository;
  private final PostingRepository postingRepository;
  private final PostingParsedRepository parsedRepository;
  private final BoardRepository boardRepository;
  private final SuitabilityScoreRepository scoreRepository;
  private final Clock clock;

  public ApplicationService(
      ApplicationRepository applicationRepository,
      ApplicationItemRepository itemRepository,
      ApplicationItemVersionRepository versionRepository,
      PostingRepository postingRepository,
      PostingParsedRepository parsedRepository,
      BoardRepository boardRepository,
      SuitabilityScoreRepository scoreRepository,
      Clock clock) {
    this.applicationRepository = applicationRepository;
    this.itemRepository = itemRepository;
    this.versionRepository = versionRepository;
    this.postingRepository = postingRepository;
    this.parsedRepository = parsedRepository;
    this.boardRepository = boardRepository;
    this.scoreRepository = scoreRepository;
    this.clock = clock;
  }

  public record ApplicationWithItems(Application application, List<ApplicationItem> items) {}

  @Transactional
  public ApplicationWithItems create(Long userId, Long postingId, String tone) {
    String resolvedTone = tone == null ? "formal" : tone;
    if (!TONES.contains(resolvedTone)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "tone 은 formal/casual/confident 중 하나여야 합니다", "tone");
    }
    Posting posting = ownedPosting(userId, postingId);
    if (!GENERATABLE_TYPES.contains(posting.type())) {
      // 명세서 F3-3 — 장학금·채용만 지원서 작성 가능 (공모전·대외활동은 지원 방식이 다양)
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "장학금·채용 공고에서만 지원서를 생성할 수 있습니다", "postingId");
    }

    Application application =
        applicationRepository.save(new Application(userId, postingId, resolvedTone));

    List<ApplicationItem> items = new ArrayList<>();
    PostingParsed parsed = parsedRepository.findById(postingId).orElse(null);
    if (parsed != null && parsed.formQuestions() != null) {
      int order = 1;
      for (Map<String, Object> question : parsed.formQuestions()) {
        Object maxChars = question.get("maxChars");
        items.add(
            itemRepository.save(
                new ApplicationItem(
                    application.id(),
                    order++,
                    String.valueOf(question.get("question")),
                    maxChars instanceof Number number ? number.intValue() : null)));
      }
    }
    if (items.isEmpty()) {
      // 양식 미인식 — 항목 없이 시작, 사용자가 직접 추가한다 (#36 계약)
      application.generationFinished(true);
    }
    return new ApplicationWithItems(application, items);
  }

  @Transactional(readOnly = true)
  public ApplicationWithItems get(Long userId, Long applicationId) {
    Application application = owned(userId, applicationId);
    return new ApplicationWithItems(
        application, itemRepository.findAllByApplicationIdOrderByItemOrder(application.id()));
  }

  @Transactional
  public ApplicationItem addItem(
      Long userId, Long applicationId, String question, Integer maxChars) {
    if (question == null || question.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "question 은 비울 수 없습니다", "question");
    }
    Application application = owned(userId, applicationId);
    int nextOrder = itemRepository.countByApplicationId(application.id()) + 1;
    return itemRepository.save(
        new ApplicationItem(application.id(), nextOrder, question.strip(), maxChars));
  }

  @Transactional(readOnly = true)
  public ApplicationItem getItem(Long userId, Long applicationId, Long itemId) {
    return ownedItem(userId, applicationId, itemId);
  }

  @Transactional
  public ApplicationItem editItem(Long userId, Long applicationId, Long itemId, String answer) {
    // 글자 수 초과 저장을 서버가 막지 않는다 — 경고는 클라이언트 몫 (F4-3)
    ApplicationItem item = ownedItem(userId, applicationId, itemId);
    item.edit(answer);
    return item;
  }

  /** 재생성 직전 현재 답변 보관 — 최대 3버전, 초과분은 오래된 것부터 삭제 (#38). */
  @Transactional
  public void archiveVersion(Long itemId, String answer, String tone) {
    if (answer == null || answer.isBlank()) {
      return;
    }
    List<ApplicationItemVersion> versions =
        versionRepository.findAllByApplicationItemIdOrderByIdAsc(itemId);
    while (versions.size() >= MAX_VERSIONS) {
      versionRepository.delete(versions.remove(0));
    }
    versionRepository.save(new ApplicationItemVersion(itemId, answer, tone));
  }

  @Transactional(readOnly = true)
  public List<ApplicationItemVersion> versions(Long userId, Long applicationId, Long itemId) {
    ownedItem(userId, applicationId, itemId);
    List<ApplicationItemVersion> versions =
        versionRepository.findAllByApplicationItemIdOrderByIdAsc(itemId);
    List<ApplicationItemVersion> reversed = new ArrayList<>(versions);
    java.util.Collections.reverse(reversed);
    return reversed;
  }

  @Transactional
  public ApplicationItem revert(Long userId, Long applicationId, Long itemId, Long versionId) {
    ApplicationItem item = ownedItem(userId, applicationId, itemId);
    ApplicationItemVersion version =
        versionRepository
            .findById(versionId)
            .filter(v -> v.applicationItemId().equals(itemId))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "버전을 찾을 수 없습니다"));
    archiveVersion(itemId, item.answer(), null); // 현재 본문도 보관해 왕복 가능하게
    item.edit(version.answer());
    return item;
  }

  /** 최종 저장 (#39) — saved 전이 + 적합도 스냅샷, 지원 이력은 이 행이 그대로 담당한다. 멱등. */
  @Transactional
  public Application save(Long userId, Long applicationId) {
    Application application = owned(userId, applicationId);
    Integer snapshot =
        scoreRepository
            .findByUserIdAndPostingId(userId, application.postingId())
            .map(score -> score.score())
            .orElse(null);
    application.save(snapshot, clock.instant());
    return application;
  }

  @Transactional
  public Application updateResult(Long userId, Long applicationId, String result) {
    if (result == null || !Application.RESULTS.contains(result)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "result 는 pending/pass/fail/none 중 하나여야 합니다", "result");
    }
    Application application = owned(userId, applicationId);
    application.updateResult(result);
    return application;
  }

  @Transactional(readOnly = true)
  public List<ApplicationWithItems> list(Long userId, String status) {
    List<Application> applications =
        status == null
            ? applicationRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(userId)
            : applicationRepository.findAllByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
                userId, status);
    return applications.stream()
        .map(
            app ->
                new ApplicationWithItems(
                    app, itemRepository.findAllByApplicationIdOrderByItemOrder(app.id())))
        .toList();
  }

  @Transactional
  public void delete(Long userId, Long applicationId) {
    // 이력 삭제 = 연결된 지원서(항목 포함) 삭제 (F4-4). 행 자체가 지원서라 hard delete 로 동반 제거한다.
    Application application = owned(userId, applicationId);
    applicationRepository.delete(application); // items·versions 는 DB cascade
  }

  public Posting postingOf(Application application) {
    return postingRepository.findById(application.postingId()).orElse(null);
  }

  private Application owned(Long userId, Long applicationId) {
    return applicationRepository
        .findByIdAndUserIdAndDeletedAtIsNull(applicationId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "지원서를 찾을 수 없습니다"));
  }

  private ApplicationItem ownedItem(Long userId, Long applicationId, Long itemId) {
    owned(userId, applicationId);
    return itemRepository
        .findByIdAndApplicationId(itemId, applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "항목을 찾을 수 없습니다"));
  }

  private Posting ownedPosting(Long userId, Long postingId) {
    Posting posting =
        postingRepository
            .findById(postingId)
            .orElseThrow(() -> new BusinessException(ErrorCode.POSTING_NOT_FOUND));
    boolean mine =
        boardRepository
            .findById(posting.boardId())
            .map(board -> board.userId().equals(userId))
            .orElse(false);
    if (!mine) {
      throw new BusinessException(ErrorCode.POSTING_NOT_FOUND);
    }
    return posting;
  }
}
