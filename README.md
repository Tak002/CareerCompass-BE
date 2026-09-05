# CareerCompass-BE

건국대학교 졸업 프로젝트 **CareerCompass** 의 백엔드 서버.

개인 맞춤 장학금·공모전·채용공고를 모아 적합도를 분석하고, 경험 카드를 바탕으로 자기소개서 초안을 만들어 주는 앱의 서버 쪽이다.

담당은 **조영탁 (@Tak002)** — 2026-05-18 제안서 「팀원 정보」의 역할 분담이 백엔드다.

> 2026-03-06 제안서(`… – CamBridge`)는 **폐기된 주제의 문서**다. 팀 역할은 그 뒤 다시 나뉘었으니 그 문서를 근거로 삼지 않는다.

## 현재 상태

**코드 없음.** 이 저장소는 착수 시점의 빈 저장소이고, 해야 할 일은 전부 이슈로 등록해 뒀다. 총 **50건**, 마일스톤 10개(M0~M9)로 나뉜다.

> 조영탁님은 아직 조직 초대를 수락하지 않아 이슈 담당자로 배정되지 않았다. 수락하면 전부 배정한다.

## 로컬 실행

```
docker compose up --build
```

서버 http://localhost:8080 · 헬스체크 `GET /actuator/health` · API 문서(local/dev 전용) `/swagger-ui`.
DB만 띄우고 앱은 IDE에서 돌리려면 `docker compose up db` 후 `local` 프로파일로 실행한다.
빌드·테스트는 `./gradlew build`(단위) / `./gradlew integrationTest`(Testcontainers, Docker 필요).

## 기술 스택

제안서에서 확정한 대로 간다.

| 층 | 선택 |
| --- | --- |
| 언어·프레임워크 | Java 21 · Spring Boot 3.x |
| DB | PostgreSQL |
| 서버 | AWS (EC2, RDS) |
| 인프라 | Docker |

## 정본 문서

| 문서 | 위치 |
| --- | --- |
| API 스펙 v0.1 (엔드포인트 40개) | [`docs/API_SPEC_v0.1.md`](docs/API_SPEC_v0.1.md) |
| 상세 기능 스펙·요구사항 명세서 (F1~F4) | 팀 문서 폴더 (교수님 제출본) |
| ERD — PostgreSQL 16 엔티티 | [Figma 11 ERD](https://www.figma.com/design/osiio5ZAcJreMsKUqjY2QW?node-id=29-2) |
| 시스템 아키텍처 | [Figma 12 Architecture](https://www.figma.com/design/osiio5ZAcJreMsKUqjY2QW?node-id=31-2) |
| 화면 설계 50종 | [Figma 파일 전체](https://www.figma.com/design/osiio5ZAcJreMsKUqjY2QW) |

## 다른 저장소와의 관계

**AI/NLP 는 별도 저장소다** — [CareerCompass-AI](https://github.com/Team-CareerCompass/CareerCompass-AI) (서성덕 @kislevite, 이슈 30건). 공고 구조화 파싱·적합도 산출·지원서 초안 생성·추천이 그쪽에 있고, **이 서버가 그것을 호출한다.** 저장·인증·API 노출은 여기가 하고 모델과 프롬프트는 그쪽이 다룬다. 경계는 [CareerCompass-AI #1](https://github.com/Team-CareerCompass/CareerCompass-AI/issues/1) 에서 확정한다.

### 클라이언트와의 관계

Android 클라이언트는 [Team-CareerCompass/CareerCompass-FE](https://github.com/Team-CareerCompass/CareerCompass-FE) 다. **FE 는 이 API 스펙대로 §1~§5 를 이미 구현했다** — 로그인·온보딩 4단계·공고 피드·필터·공고 상세·게시판 등록까지 화면과 데이터 계층이 서 있고, `core/network` 의 `BASE_URL` 만 자리표시자다. 서버가 스펙과 다른 모양을 주면 FE 의 `ApiWireContractSmokeTest`(전 엔드포인트 route·body·응답 스키마 검증)가 먼저 깨진다.

**FE 가 서버 때문에 막혀 있는 것** — `blocks:FE` 라벨이 붙은 이슈들이다.

| 막힌 것 | 이슈 |
| --- | --- |
| 학교·직무 목록 엔드포인트가 없어 FE 가 로컬 상수를 쓴다 | #17 |
| refresh 재사용·로그아웃 무효화 범위 정책이 스펙에 없다 ([FE#79](https://github.com/Team-CareerCompass/CareerCompass-FE/issues/79)) | #11 |
| 공고 파싱 처리 상태 필드가 응답 계약에 없다 | #27 |
| 적합도 분석 축 문구·강점/약점 코멘트가 서버 연동 전이다 | #33 |
| 실서버 주소(HTTPS)가 없다 | #7 |

## 이슈 지도

| 마일스톤 | 범위 | 이슈 |
| --- | --- | --- |
| **M0 기반** | 프로젝트 골격·DB 스키마·공통 응답 규약·Docker·CI·AWS 배포·통합 테스트 기반 | #1 ~ #9 |
| **M1 인증** | 소셜 로그인·JWT·토큰 재발급·로그아웃·지문 등록·Security | #10 ~ #14 |
| **M2 프로필** | 프로필·희망 직무·학교/직무 목록·경험 카드·과거 지원서 업로드와 분류 | #15 ~ #20 |
| **M3 수집** | 게시판 구조 감지·실패 분류·게시판 CRUD·주기 수집·재시도·수집 예절 | #21 ~ #27 |
| **M4 공고** | 공고 목록(필터·정렬·페이징)·상세·유사 공고·읽음/북마크 | #28 ~ #30 |
| **M5 분석** | 구조화 파싱·적합도 4축 산출·코멘트·산출 불가 계약·LLM 게이트웨이 | #31 ~ #35 |
| **M6 지원서** | 초안 생성·SSE 스트리밍·재생성/버전·저장·지원 이력 | #36 ~ #40 |
| **M7 신규 기능** | For You 추천·커리어 로드맵·강점 Export | #41 ~ #43 |
| **M8 알림** | FCM 파이프라인·알림 목록·설정·발송 트리거 3종 | #44 ~ #47 |
| **M9 운영·품질** | 개인정보 처리·관측·FE 계약 대조 | #48 ~ #50 |

### 먼저 할 것

M0 없이는 다른 어떤 이슈도 시작할 수 없다. 그다음은 **M1 인증 → M2 프로필 → M3 수집 → M4 공고** 순서가 FE 를 가장 빨리 실서버에 붙인다. M5 분석부터는 LLM 비용·정책 판단이 붙으니 #35(LLM 게이트웨이)와 #48(개인정보)을 먼저 정하고 들어간다.

## 이슈 규약

- `area:*` 라벨로 범위를, 마일스톤으로 순서를 표시한다.
- `blocks:FE` 는 **클라이언트가 그것 때문에 못 만드는 것**이다. 우선순위가 높다.
- 스펙과 다르게 구현하기로 정했으면 `docs/API_SPEC_v0.1.md` 를 고치고 FE 저장소에 이슈로 알린다. 조용히 다르게 만들지 않는다.
