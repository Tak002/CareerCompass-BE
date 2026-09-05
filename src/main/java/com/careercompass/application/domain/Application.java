package com.careercompass.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;

/** 지원서 + 지원 이력 (§6). status: generating → ready | partial_failed → saved. */
@Entity
@Table(name = "application")
public class Application {

  public static final Set<String> RESULTS = Set.of("pending", "pass", "fail", "none");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "posting_id", nullable = false, updatable = false)
  private Long postingId;

  @Column(nullable = false)
  private String status = "generating";

  @Column(nullable = false)
  private String tone = "formal";

  @Column(nullable = false)
  private String result = "none";

  @Column(name = "score_snapshot")
  private Integer scoreSnapshot;

  @Column(name = "saved_at")
  private Instant savedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected Application() {}

  public Application(Long userId, Long postingId, String tone) {
    this.userId = userId;
    this.postingId = postingId;
    this.tone = tone;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    this.updatedAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long userId() {
    return userId;
  }

  public Long postingId() {
    return postingId;
  }

  public String status() {
    return status;
  }

  public String tone() {
    return tone;
  }

  public String result() {
    return result;
  }

  public Integer scoreSnapshot() {
    return scoreSnapshot;
  }

  public Instant savedAt() {
    return savedAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public boolean generating() {
    return "generating".equals(status);
  }

  public void generationFinished(boolean allDone) {
    if (!"saved".equals(status)) {
      this.status = allDone ? "ready" : "partial_failed";
    }
  }

  public void save(Integer scoreSnapshot, Instant at) {
    this.status = "saved";
    this.scoreSnapshot = scoreSnapshot;
    this.savedAt = at;
  }

  public void updateResult(String result) {
    this.result = result;
  }
}
