package com.careercompass.pastapplication.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "past_application")
public class PastApplication {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false)
  private String label;

  @Column(name = "file_path", nullable = false)
  private String filePath;

  @Column(name = "file_type", nullable = false)
  private String fileType;

  @Column(name = "file_size_bytes", nullable = false)
  private long fileSizeBytes;

  @Column(name = "extract_status", nullable = false)
  private String extractStatus;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PastApplication() {}

  public PastApplication(
      Long userId, String label, String filePath, String fileType, long fileSizeBytes) {
    this.userId = userId;
    this.label = label;
    this.filePath = filePath;
    this.fileType = fileType;
    this.fileSizeBytes = fileSizeBytes;
    this.extractStatus = "pending";
  }

  @PrePersist
  void prePersist() {
    this.createdAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long userId() {
    return userId;
  }

  public String label() {
    return label;
  }

  public String filePath() {
    return filePath;
  }

  public String fileType() {
    return fileType;
  }

  public String extractStatus() {
    return extractStatus;
  }

  public void markExtracted() {
    this.extractStatus = "done";
  }

  public void markExtractFailed() {
    this.extractStatus = "failed";
  }
}
