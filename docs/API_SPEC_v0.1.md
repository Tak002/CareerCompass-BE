# CareerCompass — API 스펙 v0.1

> Spring Boot 3.x / Java 21 기준 REST API 초안. 모든 인증 엔드포인트(`/auth/*` 제외)는 `Authorization: Bearer {JWT}` 헤더 필수. 응답은 `application/json; charset=UTF-8`.

## 공통 규약

### 응답 포맷

성공:
```json
{
  "ok": true,
  "data": { ... }
}
```

실패:
```json
{
  "ok": false,
  "error": {
    "code": "POSTING_NOT_FOUND",
    "message": "공고를 찾을 수 없습니다",
    "field": null
  }
}
```

### 페이징

```
GET /postings?cursor=eyJ...&limit=20
```

응답에 `nextCursor` 포함. cursor 없으면 마지막 페이지.

### HTTP 상태 코드

| 코드 | 의미 |
|---|---|
| 200 | 정상 |
| 201 | 생성 완료 |
| 400 | 검증 실패 |
| 401 | 인증 필요 |
| 403 | 권한 부족 |
| 404 | 리소스 없음 |
| 409 | 충돌 (중복 등) |
| 422 | 비즈니스 룰 위반 |
| 429 | Rate limit |
| 500 | 서버 오류 |
| 503 | 외부 의존성 장애 (LLM 등) |

---

## 1. 인증 (`/auth`)

### `POST /auth/social/{provider}`

소셜 로그인. `provider`는 `kakao` 또는 `google`.

요청:
```json
{
  "accessToken": "...",
  "deviceId": "uuid-v4",
  "fcmToken": "fcm-token-string"
}
```

응답 201:
```json
{
  "ok": true,
  "data": {
    "accessToken": "jwt...",
    "refreshToken": "...",
    "user": {
      "id": 1,
      "email": "user@konkuk.ac.kr",
      "name": null,
      "onboardingDone": false
    }
  }
}
```

### `POST /auth/refresh`

리프레시 토큰으로 토큰 쌍 재발급. 인증 헤더 불필요.

요청:
```json
{ "refreshToken": "..." }
```

응답 200:
```json
{
  "ok": true,
  "data": {
    "accessToken": "jwt...",
    "refreshToken": "..."
  }
}
```

**정책 (v0.2 확정, BE #11):**

- **회전(rotation)**: 재발급마다 refresh 토큰을 새로 발급한다. 응답의 새 refreshToken 으로 반드시 교체 저장할 것.
- **재사용 탐지**: 이미 소비된 refresh 가 다시 오면 **해당 기기 세션의 토큰 계보 전체를 폐기**하고 `AUTH_INVALID`(401) 를 준다. 다른 기기 세션은 끊지 않는다.
- **유예(grace) 30초**: 회전 직후 30초 안에 소비된 토큰이 다시 오면 네트워크 경합으로 보고 정상적으로 새 쌍을 준다. FE 의 single-flight 재발급이 일시 실패를 공유해도 안전하다.
- 만료·무효·폐기된 refresh 는 전부 `AUTH_INVALID`(401) — FE 는 이 코드에서 세션 만료 안내 후 로그인 화면으로 보낸다.
- 액세스 토큰: HS256 JWT, `sub`=userId, 수명 30분. 만료된 액세스 토큰은 `AUTH_REQUIRED`(401), 서명 무효·형식 오류는 `AUTH_INVALID`(401).

### `POST /auth/logout`

**이 기기**의 리프레시 무효화 + FCM 토큰 해제 + 지문 등록 폐기. 인증 필요.

요청:
```json
{ "deviceId": "uuid-v4" }
```

**정책 (v0.2 확정, BE #12):** 무효화 범위는 요청한 기기뿐이다(전 기기 아님). 액세스 토큰은 블랙리스트 없이 만료(≤30분)까지 유효하다 — 로그아웃 뒤 도착한 옛 액세스 토큰 요청은 만료 전이면 통과하고, 만료 후엔 `AUTH_REQUIRED`. 이미 로그아웃된 상태의 재요청도 멱등하게 200.

### `POST /auth/biometric/register`

지문 빠른 로그인 기기 등록. 인증 필요.

요청:
```json
{ "deviceId": "uuid-v4" }
```

**정책 (v0.2 확정, BE #13):** 등록은 (계정, 기기) 단위로 귀속된다 — 한 기기를 여러 계정이 써도 서로의 등록을 덮지 않는다. 지문 성공 뒤 세션 검증은 별도 엔드포인트 없이 `GET /users/me` 로 갈음한다. 로그아웃·계정 삭제 시 해당 계정×기기의 등록이 폐기된다.

---

## 2. 사용자 프로필 (`/users/me`)

### `GET /users/me`

내 프로필 전체 조회.

응답:
```json
{
  "ok": true,
  "data": {
    "id": 1,
    "name": "정일혁",
    "school": "건국대학교",
    "department": "컴퓨터공학부",
    "gpa": 3.87,
    "gradYear": 2027,
    "jobInterests": [
      { "code": "backend", "priority": 1 },
      { "code": "frontend", "priority": 2 }
    ],
    "tags": ["AI", "스타트업", "환경"],
    "onboardingDone": true,
    "completion": 78
  }
}
```

### `PATCH /users/me`

프로필 부분 수정.

요청:
```json
{
  "name": "정일혁",
  "school": "건국대학교",
  "department": "컴퓨터공학부",
  "gpa": 3.87,
  "gradYear": 2027
}
```

### `PUT /users/me/job-interests`

희망 직무 전체 교체. 최소 1, 최대 3.

```json
{
  "interests": [
    { "code": "backend", "priority": 1 },
    { "code": "frontend", "priority": 2 }
  ]
}
```

### `PUT /users/me/tags`

관심 분야 태그. 최소 1, 최대 5.

```json
{ "tags": ["AI", "스타트업", "환경"] }
```

---

## 3. 경험 카드 (`/experiences`)

### `GET /experiences?type=project&cursor=...&limit=20`

경험 카드 목록 (최신 등록순).

### `POST /experiences`

경험 카드 생성. `type`은 `project` / `award` / `intern` / `activity` / `cert` 중 하나.

요청 (프로젝트 예시):
```json
{
  "type": "project",
  "title": "CareerCompass",
  "startDate": "2025-09-01",
  "endDate": null,
  "data": {
    "role": "프론트엔드",
    "techs": ["Android", "Kotlin", "Compose"],
    "summary": "공고 자동 분석 + AI 자소서 생성 서비스",
    "link": "https://github.com/..."
  }
}
```

### `PATCH /experiences/{id}`

경험 카드 수정.

### `DELETE /experiences/{id}`

경험 카드 삭제.

---

## 4. 과거 지원서 (`/past-applications`)

### `POST /past-applications/upload` (multipart/form-data)

PDF/DOCX/TXT 업로드. 최대 10MB.

요청:
```
file: <binary>
label: "2024 카카오 인턴 자소서"
```

응답 201:
```json
{
  "ok": true,
  "data": {
    "id": 7,
    "label": "2024 카카오 인턴 자소서",
    "filePath": "s3://...",
    "items": [
      { "id": 1, "category": "motivation", "content": "...", "confident": true },
      { "id": 2, "category": "other", "content": "...", "confident": false }
    ]
  }
}
```

### `GET /past-applications`

내 과거 지원서 목록.

### `PATCH /past-applications/{appId}/items/{itemId}`

항목 카테고리 수동 조정.

```json
{ "category": "aspiration" }
```

### `DELETE /past-applications/{id}`

지원서 삭제 (S3 파일 포함).

---

## 5. 게시판 & 공고 (`/boards`, `/postings`)

### `POST /boards/detect`

URL 입력 시 사이트 구조 자동 감지. 등록 전 미리보기.

요청:
```json
{ "url": "https://konkuk.ac.kr/board/notice" }
```

응답:
```json
{
  "ok": true,
  "data": {
    "detectStatus": "success",
    "selectors": { "title": "...", "link": "...", "date": "..." },
    "preview": [
      { "title": "...", "url": "...", "date": "2026-05-10" },
      ...
    ]
  }
}
```

`detectStatus`는 `success` / `login_required` / `spa` / `blocked` / `failed`.

### `POST /boards`

게시판 등록 확정.

요청:
```json
{
  "url": "https://konkuk.ac.kr/board/notice",
  "name": "건국대 학교 공지사항",
  "type": "scholarship",
  "cycleHours": 24
}
```

### `GET /boards`

내 게시판 목록 (status, fail_count 포함).

### `PATCH /boards/{id}` / `DELETE /boards/{id}`

게시판 수정/삭제. `isActive` 토글 가능.

### `POST /boards/{id}/retry`

수집 실패 게시판 재시도.

### `GET /postings`

공고 목록.

쿼리 파라미터:
- `boardIds[]` — 게시판 필터
- `types[]` — 공고 유형
- `minScore` — 적합도 최소값
- `unreadOnly` — 읽지 않은 것만
- `sort` — `collected_desc` / `due_asc` / `score_desc`
- `cursor`, `limit`

응답:
```json
{
  "ok": true,
  "data": {
    "postings": [
      {
        "id": 101,
        "title": "2026 카카오 SW 인턴십",
        "type": "recruit",
        "board": { "id": 3, "name": "공식 채용" },
        "dueDate": "2026-05-25",
        "collectedAt": "2026-05-18T07:00:00+09:00",
        "score": 88,
        "scoreLabel": "very_suitable",
        "isRead": false,
        "isBookmarked": false
      }
    ],
    "nextCursor": "..."
  }
}
```

### `GET /postings/{id}`

공고 상세 (구조화 결과 + 적합도 분석 + 유사 공고 포함).

응답:
```json
{
  "ok": true,
  "data": {
    "id": 101,
    "title": "...",
    "rawContent": "...",
    "url": "https://...",
    "parsed": {
      "keywords": ["Spring", "Kotlin", "Redis", ...],
      "qualifications": { "year": "2학년 이상", "gpa": null },
      "preferences": ["Java/Kotlin 백엔드 경험", "RDB 1년+"],
      "formQuestions": [
        { "order": 1, "question": "지원 동기를 작성해 주세요", "maxChars": 500 }
      ]
    },
    "suitability": {
      "score": 88,
      "label": "very_suitable",
      "breakdown": [
        { "axis": "field_similarity", "score": 95, "weight": 40 },
        { "axis": "qualification", "score": 88, "weight": 30 },
        { "axis": "preference", "score": 78, "weight": 20 },
        { "axis": "competition", "score": 80, "weight": 10 }
      ],
      "strengthComment": "...",
      "weaknessComment": "..."
    },
    "similar": [ ... ]
  }
}
```

### `POST /postings/{id}/bookmark` / `DELETE`

북마크 토글.

### `POST /postings/{id}/read`

읽음 처리.

---

## 6. 지원서 작성 (`/applications`)

### `POST /applications`

공고 기반 지원서 생성 (AI 초안 트리거).

요청:
```json
{
  "postingId": 101,
  "tone": "formal"
}
```

응답 (201):
```json
{
  "ok": true,
  "data": {
    "id": 42,
    "status": "generating",
    "items": [
      { "id": 1, "order": 1, "question": "지원 동기...", "maxChars": 500, "status": "done", "answer": "..." },
      { "id": 2, "order": 2, "question": "강점과 약점...", "maxChars": 400, "status": "loading", "answer": null }
    ]
  }
}
```

`status`는 `generating` / `ready` / `partial_failed` / `saved`.

### `GET /applications/{id}/stream` (SSE)

생성 진행 상황 실시간 스트림. 각 항목 완료 시 이벤트 발송.

```
event: item_done
data: {"itemId": 2, "answer": "..."}
```

### `POST /applications/{id}/items/{itemId}/regenerate`

항목별 재생성. `tone`, `emphasizeCardIds` 옵션.

요청:
```json
{
  "tone": "casual",
  "emphasizeCardIds": [5, 12]
}
```

### `PATCH /applications/{id}/items/{itemId}`

수동 편집.

```json
{ "answer": "..." }
```

### `POST /applications/{id}/save`

최종 저장. 이력 자동 생성.

### `PATCH /applications/{id}/result`

지원 결과 입력.

```json
{ "result": "pass" }
```

값: `pending` / `pass` / `fail` / `none`.

### `GET /applications`

지원 이력 목록 (status 필터 지원).

### `DELETE /applications/{id}`

이력 삭제 (연결된 지원서 함께 삭제).

---

## 7. 신규 기능 (`/feed`, `/roadmap`, `/export`)

### `GET /feed/for-you`

AI 추천 — 톱 픽 + 강점 기반 + 취약점 보완 카테고리별 최대 5건.

응답:
```json
{
  "ok": true,
  "data": {
    "topPick": { "postingId": 101, "reason": [...] },
    "byStrength": [ { "postingId": 102, "reason": "..." }, ... ],
    "byGap": [ { "postingId": 103, "reason": "어학·인턴 보완용" }, ... ]
  }
}
```

### `GET /roadmap/compare?cohort=peer`

`cohort`: `peer` (같은 학과·학년 동기) / `senior` (합격 선배) / `me_only` (내 성장).

응답:
```json
{
  "ok": true,
  "data": {
    "cohort": "peer",
    "sampleSize": 86,
    "metrics": [
      { "name": "프로젝트 수", "me": 4, "peerAvg": 2 },
      { "name": "인턴 경험", "me": 0, "peerAvg": 0.8 }
    ],
    "suggestions": [
      { "semester": "3-2", "action": "SQLD + 토익 800+", "expectedLift": 12 }
    ]
  }
}
```

### `POST /export`

강점 데이터 export.

요청:
```json
{
  "format": "markdown",
  "sections": ["basic", "skills", "projects", "awards", "summary"]
}
```

응답:
```json
{
  "ok": true,
  "data": {
    "format": "markdown",
    "content": "# 정일혁\n*컴퓨터공학부 · 건국대*\n..."
  }
}
```

`format`: `markdown` / `notion` / `html` / `plain`.

---

## 8. 알림 (`/notifications`)

### `GET /notifications`

알림 목록.

### `POST /notifications/read-all`

모두 읽음.

### `GET /notifications/settings` / `PUT`

설정 조회/수정.

```json
{
  "newPosting": true,
  "dueSoon": true,
  "boardError": true,
  "weeklyReport": false,
  "quietHours": { "start": "23:00", "end": "08:00" },
  "weekendOff": false,
  "perBoard": [
    { "boardId": 3, "enabled": true }
  ]
}
```

---

## 9. 에러 코드 정의

| 코드 | HTTP | 설명 |
|---|---|---|
| `INVALID_INPUT` | 400 | 요청 검증 실패 |
| `AUTH_REQUIRED` | 401 | 토큰 없음/만료 |
| `AUTH_INVALID` | 401 | 토큰 무효 |
| `PERMISSION_DENIED` | 403 | 권한 부족 |
| `RESOURCE_NOT_FOUND` | 404 | 일반 미발견 |
| `POSTING_NOT_FOUND` | 404 | 공고 없음 |
| `DUPLICATE_BOARD` | 409 | 동일 URL 중복 |
| `LIMIT_EXCEEDED` | 422 | 경험 카드 30개 초과 등 |
| `PROFILE_INCOMPLETE` | 422 | 적합도 산출 불가 |
| `PARSING_FAILED` | 422 | 공고 파싱 실패 |
| `BOARD_BLOCKED` | 422 | 접근 차단 게시판 |
| `RATE_LIMITED` | 429 | 호출 횟수 초과 |
| `LLM_UNAVAILABLE` | 503 | LLM API 장애 |
| `INTERNAL_ERROR` | 500 | 기타 서버 오류 |

---

## 10. 변경 이력

- v0.2 (2026-09-05) — §1 인증 정책 확정: refresh 회전·재사용 탐지(계보 폐기)·유예 30초, 로그아웃 범위(이 기기)·액세스 토큰 비블랙리스트, 지문 등록의 계정×기기 귀속. FE#79 차단 해소용 — FE 저장소에 공유 필요.
- v0.1 (2026-05-18) — 초안 작성. 명세서 F1~F4 + 신규 기능 반영.
