package com.careercompass.user.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTagRepository extends JpaRepository<UserTag, Long> {

  List<UserTag> findAllByUserIdOrderById(Long userId);

  void deleteAllByUserId(Long userId);
}
