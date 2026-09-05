package com.careercompass.application.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

  Optional<Application> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

  List<Application> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId);

  List<Application> findAllByUserIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
      Long userId, String status);
}
