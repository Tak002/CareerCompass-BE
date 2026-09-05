package com.careercompass.user.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJobInterestRepository extends JpaRepository<UserJobInterest, Long> {

  List<UserJobInterest> findAllByUserIdOrderByPriority(Long userId);

  void deleteAllByUserId(Long userId);
}
