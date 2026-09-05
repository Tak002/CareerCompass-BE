package com.careercompass.posting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "posting")
public class Posting {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "board_id", nullable = false, updatable = false)
  private Long boardId;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String url;

  @Column(name = "url_hash", nullable = false, updatable = false)
  private String urlHash;

  @Column(name = "raw_content")
  private String rawContent;

  @Column(nullable = false)
  private String type = "other";

  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column(name = "posted_date")
  private LocalDate postedDate;

  @Column(name = "collected_at", nullable = false, updatable = false)
  private Instant collectedAt;

  protected Posting() {}

  public Posting(
      Long boardId,
      String title,
      String url,
      String urlHash,
      String rawContent,
      String type,
      LocalDate dueDate,
      LocalDate postedDate) {
    this.boardId = boardId;
    this.title = title;
    this.url = url;
    this.urlHash = urlHash;
    this.rawContent = rawContent;
    this.type = type;
    this.dueDate = dueDate;
    this.postedDate = postedDate;
  }

  @PrePersist
  void prePersist() {
    this.collectedAt = Instant.now();
  }

  public Long id() {
    return id;
  }

  public Long boardId() {
    return boardId;
  }

  public String title() {
    return title;
  }

  public String url() {
    return url;
  }

  public String rawContent() {
    return rawContent;
  }

  public String type() {
    return type;
  }

  public LocalDate dueDate() {
    return dueDate;
  }

  public LocalDate postedDate() {
    return postedDate;
  }

  public Instant collectedAt() {
    return collectedAt;
  }
}
