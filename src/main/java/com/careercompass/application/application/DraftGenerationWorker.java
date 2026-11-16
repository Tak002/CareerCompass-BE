package com.careercompass.application.application;

import com.careercompass.analysis.gateway.LlmGateway;
import com.careercompass.application.domain.Application;
import com.careercompass.application.domain.ApplicationItem;
import com.careercompass.application.domain.ApplicationItemRepository;
import com.careercompass.application.domain.ApplicationRepository;
import com.careercompass.common.api.ErrorCode;
import com.careercompass.common.exception.BusinessException;
import com.careercompass.posting.domain.Posting;
import com.careercompass.posting.domain.PostingRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 초안 생성 워커 (#36, #37) — 항목별 독립 생성: 하나가 실패해도 나머지는 나간다(partial_failed). 항목 완료마다 SSE 로 즉시 알린다. 기본
 * 400~600자(제한 없을 때)는 게이트웨이 계약.
 */
@Component
public class DraftGenerationWorker {

  private static final Logger log = LoggerFactory.getLogger(DraftGenerationWorker.class);

  private final ApplicationRepository applicationRepository;
  private final ApplicationItemRepository itemRepository;
  private final PostingRepository postingRepository;
  private final DraftContextBuilder contextBuilder;
  private final LlmGateway llmGateway;
  private final SseHub sseHub;
  private final ApplicationService applicationService;

  public DraftGenerationWorker(
      ApplicationRepository applicationRepository,
      ApplicationItemRepository itemRepository,
      PostingRepository postingRepository,
      DraftContextBuilder contextBuilder,
      LlmGateway llmGateway,
      SseHub sseHub,
      ApplicationService applicationService) {
    this.applicationRepository = applicationRepository;
    this.itemRepository = itemRepository;
    this.postingRepository = postingRepository;
    this.contextBuilder = contextBuilder;
    this.llmGateway = llmGateway;
    this.sseHub = sseHub;
    this.applicationService = applicationService;
  }

  /** 지금 이 서버에서 생성이 도는 지원서 — 멱등 재호출(#57)이 같은 항목을 두 번 생성(LLM 비용 2배)하지 않게 막는다. */
  private final java.util.Set<Long> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @Async
  public void generateAsync(Long applicationId) {
    if (!inFlight.add(applicationId)) {
      return; // 이미 생성 중 — 재호출은 현재 상태만 돌려받는다
    }
    try {
      generate(applicationId);
    } finally {
      inFlight.remove(applicationId);
    }
  }

  private void generate(Long applicationId) {
    Application application = applicationRepository.findById(applicationId).orElse(null);
    if (application == null || !application.generating()) {
      return;
    }
    Posting posting = postingRepository.findById(application.postingId()).orElse(null);
    if (posting == null) {
      return;
    }
    DraftContextBuilder.Context context = contextBuilder.build(application.userId(), posting);

    List<ApplicationItem> items =
        itemRepository.findAllByApplicationIdOrderByItemOrder(applicationId);
    for (ApplicationItem item : items) {
      if (!"loading".equals(item.status())) {
        continue;
      }
      draftSingle(application, item, context, application.tone(), null);
    }

    // 이전 실행(재시작 전)에서 실패한 항목도 있을 수 있으니 결과는 행 상태로 다시 센다
    boolean allDone =
        itemRepository.findAllByApplicationIdOrderByItemOrder(applicationId).stream()
            .allMatch(item -> "done".equals(item.status()));
    application.generationFinished(allDone);
    applicationRepository.save(application);
    sseHub.complete(applicationId, application.status());
  }

  /** 재생성 (#38) — 새 답변 생성 성공 시에만 이전 버전을 보관하고 교체한다. LLM 장애는 503 으로 전파되고 기존 답변은 유지된다(항목별 재시도 가능). */
  public ApplicationItem regenerate(
      Long userId, Long applicationId, Long itemId, String tone, List<Long> emphasizeCardIds) {
    if (tone != null && !ApplicationService.TONES.contains(tone)) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "tone 은 formal/casual/confident 중 하나여야 합니다", "tone");
    }
    Application application = applicationService.get(userId, applicationId).application();
    ApplicationItem item = applicationService.getItem(userId, applicationId, itemId);
    Posting posting = postingRepository.findById(application.postingId()).orElseThrow();
    DraftContextBuilder.Context context = contextBuilder.build(userId, posting);
    String resolvedTone = tone == null ? application.tone() : tone;

    List<String> summaries =
        contextBuilder.summariesFor(userId, context, item.question(), emphasizeCardIds);
    String answer =
        llmGateway.draftAnswer(
            item.question(),
            item.maxChars(),
            resolvedTone,
            context.postingTitle(),
            context.keywords(),
            summaries); // 실패 시 여기서 503 전파 — 아래 보관·교체는 실행되지 않는다

    applicationService.archiveVersion(itemId, item.answer(), application.tone());
    return applicationService.editItem(userId, applicationId, itemId, answer);
  }

  /** 수동 추가 항목의 초안 생성 (#36) — 실패하면 failed 로 남고 재생성으로 재시도한다. */
  public ApplicationItem draftNewItem(Long userId, Long applicationId, Long itemId) {
    Application application = applicationService.get(userId, applicationId).application();
    ApplicationItem item = applicationService.getItem(userId, applicationId, itemId);
    Posting posting = postingRepository.findById(application.postingId()).orElseThrow();
    DraftContextBuilder.Context context = contextBuilder.build(userId, posting);
    draftSingle(application, item, context, application.tone(), null);
    return itemRepository.findById(item.id()).orElse(item);
  }

  /** 단일 항목 생성 — 초기 일괄 생성이 쓰는 공통 경로. */
  public boolean draftSingle(
      Application application,
      ApplicationItem item,
      DraftContextBuilder.Context context,
      String tone,
      List<Long> emphasizeCardIds) {
    try {
      List<String> summaries =
          contextBuilder.summariesFor(
              application.userId(), context, item.question(), emphasizeCardIds);
      String answer =
          llmGateway.draftAnswer(
              item.question(),
              item.maxChars(),
              tone,
              context.postingTitle(),
              context.keywords(),
              summaries);
      item.draftReady(answer);
      itemRepository.save(item);
      sseHub.publish(
          application.id(),
          new SseHub.Event("item_done", Map.of("itemId", item.id(), "answer", answer)));
      return true;
    } catch (BusinessException e) {
      if (e.code() != ErrorCode.LLM_UNAVAILABLE) {
        throw e;
      }
      return failItem(application, item, ErrorCode.LLM_UNAVAILABLE);
    } catch (Exception e) {
      log.warn("초안 생성 실패: item={}", item.id(), e);
      return failItem(application, item, ErrorCode.INTERNAL_ERROR);
    }
  }

  /** 항목 실패 — status=failed 로 남기고 item_failed{itemId, code} 를 보낸다 (#59). code 는 §9 값. */
  private boolean failItem(Application application, ApplicationItem item, ErrorCode code) {
    item.draftFailed();
    itemRepository.save(item);
    sseHub.publish(
        application.id(),
        new SseHub.Event("item_failed", Map.of("itemId", item.id(), "code", code.name())));
    return false;
  }
}
