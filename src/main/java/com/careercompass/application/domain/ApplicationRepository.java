package com.careercompass.application.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

  Optional<Application> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

  List<Application> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId);

  List<Application> findAllByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
      Long userId, String status);

  /** 공고당 진행 중(generating·ready·partial_failed) 지원서 — #57 멱등 생성의 재사용 대상. */
  Optional<Application> findFirstByUserIdAndPostingIdAndStatusInAndDeletedAtIsNullOrderByIdDesc(
      Long userId, Long postingId, java.util.Collection<String> statuses);
}
