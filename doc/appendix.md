# 부록. 전체 지도 · 개발 순서와 분담 · 확장 목록

> 1부~5부에서 설명한 26개 테이블을 **한눈에 보는 지도**입니다.
> 이 부록 하나만 보면 "내가 맡은 기능이 어떤 테이블을 쓰는지", "어떤 순서로 만드는지", "무엇을 나중으로 미루는지"를 알 수 있습니다.

---

## 부록 A. 테이블 26개 한눈에

중요도는 제 판단입니다. **★ 핵심**(없으면 서비스가 안 됨), **○ 일반**, **△ 뒤로 미뤄도 됨**(없어도 예매는 돌아감).

| 부 | 영역 | 테이블 | 중요도 | 한 줄 설명 |
|---|---|---|---|---|
| 1부 | 유저 | `user_member` | ★ | 예매자 계정 |
| | | `terms` | ○ | 약관 내용 (버전별) |
| | | `user_terms_agreement` | ○ | 누가 어떤 약관에 동의했는지 |
| | | `seller_business` | ★ | 판매자 사업체 (승인 대상) |
| | | `seller_member` | ★ | 판매자 계정 (대표·직원) |
| | | `seller_invitation` | △ | 직원 초대 링크 |
| | | `admin_member` | ○ | 관리자 계정 |
| 2부 | 공연장 | `venue` | ★ | 공연장 (건물) |
| | | `venue_zone` | ★ | 공연장의 구역 |
| | | `venue_seat` | ★ | 공연장의 좌석 원본 |
| | | `venue_request` | △ | 공연장 등록 신청 기록 |
| 3부 | 공연 | `performance` | ★ | 공연 |
| | | `performance_image` | ○ | 공연 상세 이미지 |
| | | `performance_schedule` | ★ | 공연 회차 (판매 상태 보유) |
| | | `schedule_postponement` | ○ | 회차 연기 이력 |
| | | `seat_grade` | ★ | 좌석 등급과 가격 |
| | | `zone_grade` | ★ | 구역별 등급 지정 |
| | | `schedule_seat` | ★ | 회차별 판매 좌석 (프로젝트의 핵심) |
| | | `cancel_policy` | ○ | 취소 규정 |
| | | `cancel_policy_rule` | ○ | 취소 규정 구간 |
| | | `common_guide` | △ | 모든 공연 공통 안내 |
| 4부 | 예매·결제 | `booking` | ★ | 예매 (주문서) |
| | | `booking_seat` | ★ | 예매 좌석 (스냅샷) |
| | | `payment` | ★ | 결제 (토스) |
| | | `refund` | ★ | 환불 |
| 5부 | 운영 | `audit_log` | △ | 판매자·관리자 행동 기록 |

**테이블 없이 구현하는 것** (Redis나 코드로 처리)
- 대기열, 캡챠: 서비스 고정 설정, 테이블 없음
- 로그인 토큰 블랙리스트, 이메일 인증번호: Redis
- 예매 수수료 정책 기본값: 설정 파일

---

## 부록 B. 기능과 테이블 전체 연결표

### 예매자

| 기능 | 쓰는 테이블 | 부 |
|---|---|---|
| 약관 조회 | `terms` | 1 |
| 회원가입 (본인인증, 약관 동의) | `user_member`, `user_terms_agreement`, `terms` | 1 |
| 로그인·로그아웃 | `user_member` (+ Redis) | 1 |
| 내 정보 조회·수정, 탈퇴 | `user_member` | 1 |
| 공연 목록·검색 | `performance`, `performance_schedule`, `venue` | 3 |
| 공연 상세 | `performance`, `performance_image`, `performance_schedule`, `seat_grade`, `zone_grade`, `cancel_policy(_rule)`, `common_guide`, `seller_business`, `venue` | 3 |
| 구역 요약 → 구역 상세 좌석 조회 | `schedule_seat`, `zone_grade`, `venue_zone` | 2, 3 |
| 좌석 점유 (매수 검사 + PENDING 예매 생성) | `schedule_seat`, `booking`, `booking_seat` | 3, 4 |
| 결제 준비·승인 | `booking`, `payment`, `schedule_seat` | 4 |
| 내 예매 목록·상세 | `booking`, `booking_seat`, `performance_schedule` | 4 |
| 예매 취소 (전체·일부) | `booking_seat`, `booking`, `refund`, `payment`, `schedule_seat` | 4 |
| 연기된 회차 무료 취소 | 위와 같음 + `schedule_postponement` | 3, 4 |

### 판매자

| 기능 | 쓰는 테이블 | 부 |
|---|---|---|
| 판매자 가입 신청 (사업체 + 대표 계정) | `seller_business`, `seller_member` | 1 |
| 로그인 (승인된 사업체만) | `seller_member`, `seller_business` | 1 |
| 직원 초대·가입 | `seller_invitation`, `seller_member` | 1 |
| 공연장 선택 | `venue` | 2 |
| 공연장 신청 | `venue_request` | 2 |
| 공연 등록·수정·공개 | `performance`, `performance_image` | 3 |
| 등급·가격, 구역별 등급 설정 | `seat_grade`, `zone_grade` | 3 |
| 회차 추가 | `performance_schedule` | 3 |
| 판매 좌석 생성 (오픈 전날 자동, 앞당겨 실행 가능) | `performance_schedule`, `schedule_seat` | 3 |
| 판매 중지·재개 | `performance_schedule` | 3 |
| 회차 연기 | `performance_schedule`, `schedule_postponement`, `audit_log` | 3, 5 |
| 회차·공연 취소 (일괄 환불) | `performance_schedule`, `booking`, `refund`, `payment`, `audit_log` | 4, 5 |
| 판매자 보류석 지정·해제 | `schedule_seat` | 3 |
| 취소 규정 설정 | `cancel_policy`, `cancel_policy_rule` | 3 |
| 예매 현황 조회 | `booking`, `booking_seat` | 4 |
| 배송 발송 처리 (택배사, 송장번호) | `booking` | 4 |
| 내 사업체 변경 이력 조회 | `audit_log` | 5 |

### 관리자

| 기능 | 쓰는 테이블 | 부 |
|---|---|---|
| 로그인 | `admin_member` | 1 |
| 판매자 사업체 승인·반려 | `seller_business`, `audit_log` | 1, 5 |
| 공연장 생성 (JSON → 좌석 생성) | `venue`, `venue_zone`, `venue_seat` | 2 |
| 공연장 신청 처리 | `venue_request`, `venue` | 2 |
| 공통 안내 관리 | `common_guide`, `audit_log` | 3, 5 |
| 환불 실패 목록·수동 재처리 | `refund`, `audit_log` | 4, 5 |
| 전체 감사 로그 조회 | `audit_log` | 5 |
| 회원 정지 | `user_member` | 1 |

### 시스템 (자동 작업)

| 작업 | 주기 | 쓰는 테이블 | 부 |
|---|---|---|---|
| 판매 좌석 생성 | 1시간 | `performance_schedule`, `schedule_seat` | 3 |
| 만료 점유 정리 | 1분 | `schedule_seat`, `booking` | 5 |
| 결제 불일치 보정 | 5분 | `payment`, `booking`, `refund` | 5 |
| 환불 재시도·일괄 환불 처리 | 1~5분 | `refund` | 5 |
| 끝난 회차 좌석 정리 | 하루 1회 | `schedule_seat` | 3 |
| 앱 시작 시 공연장 시드 생성 | 1회 | `venue`, `venue_zone`, `venue_seat` | 2 |

---

## 부록 C. 테이블을 만드는 순서

다른 테이블을 가리키는 컬럼(FK 컬럼)은 가리켜지는 쪽이 먼저 있어야 시드와 테스트가 편해서, **위에서 아래 순서**로 만들면 막히지 않습니다. (논리 FK라 DB가 강제하지는 않습니다.)

| 단계 | 테이블 | 비고 |
|---|---|---|
| 1 | `user_member`, `admin_member`, `terms`, `seller_business`, `seller_member` | 계정. 다른 모든 것의 기반 |
| 2 | `user_terms_agreement`, `seller_invitation` | 1단계 이후 |
| 3 | `venue`, `venue_zone`, `venue_seat` (+ 시드 생성 스크립트) | 시드가 있어야 이후 개발이 편함 |
| 4 | `cancel_policy`, `cancel_policy_rule`, `performance`, `performance_image` | 공연 등록 |
| 5 | `seat_grade`, `zone_grade`, `performance_schedule`, `schedule_seat` | 등급 → 회차 → (오픈 전날) 좌석 복사 |
| 6 | `booking`, `booking_seat`, `payment`, `refund` | 예매·결제 |
| 7 | `schedule_postponement`, `common_guide`, `venue_request`, `audit_log` | 곁가지 (언제든 추가 가능) |

**테이블은 Flyway SQL 마이그레이션으로 만듭니다.** (`ddl-auto`는 `none`이나 `validate`로 둡니다.) 부분 UNIQUE 인덱스(`booking_seat`, 계정 이메일, `ci_hash`), `jsonb`, `fillfactor` 같은 PostgreSQL 기능은 JPA 자동 생성으로 만들 수 없어서 SQL이 필요합니다. 이 부분은 재환님이 맡는 것을 추천합니다.

**FK는 대부분 논리 FK입니다.** 문서의 FK 표시는 "이 컬럼이 저 테이블의 번호를 가리킨다"는 뜻이고, DB 제약은 걸지 않습니다. 엔티티에는 `Long userId`처럼 번호만 저장하고, 자주 조회하는 컬럼은 인덱스를 직접 만듭니다. (PostgreSQL은 FK를 걸어도 컬럼에 인덱스를 자동으로 만들지 않습니다.) 존재하지 않는 번호를 막는 일은 서비스 코드가 책임지고, DB 제약은 꼭 필요한 곳(부분 UNIQUE 등)에만 SQL로 겁니다.

---

## 부록 D. 개발 순서와 분담

팀 구성: 재환님(주니어 1명) + 초보자 2명 (A: 예매자 쪽, B: 관리자 쪽)

### 원칙

1. **혼자 완결되는 것부터** 시작합니다. 다른 사람의 코드를 기다리지 않아도 되는 기능이 초보자에게 맞습니다.
2. **동시성, 결제, 스케줄러는 재환님 몫**입니다. 실수하면 돈과 좌석이 꼬입니다.
3. 한 사람이 **세로 한 줄**(엔티티 → 레포지토리 → 서비스 → 컨트롤러 → 테스트)을 통째로 맡습니다.
4. 서로 얽히는 곳은 **API 명세와 시드 데이터**로 분리합니다.

### 역할 분담

| 담당 | 맡는 것 |
|---|---|
| **재환님** | 공통 틀(`ApiResponse`, 전역 예외), **세 계정의 가입·로그인·JWT·접근 제한**, 판매자 도메인 전부(가입 신청, 초대, 공연 등록, 회차, 등급·가격, 취소 규정, 연기), 오픈 전날 좌석 생성(스케줄러), 좌석 점유와 1인 매수 검사, 예매·결제(Mock → 토스), 취소·환불, 스케줄러, 좌석 생성 스크립트, 부하 테스트 |
| **초보자 A** | 약관 조회, 공연 목록·검색·상세 조회, 내 정보 조회·수정·탈퇴, 내 예매 목록·상세 |
| **초보자 B** | 공연장 조회, 공통 안내 CRUD, 판매자 사업체 승인·반려, 공연장 신청 처리, 감사 로그 조회, 환불 실패 목록 |

### 주차별 진행 (한 달 기준, 제 추정)

| 주차 | 재환님 | 초보자 A | 초보자 B |
|---|---|---|---|
| 1 | 공통 틀, 세 계정의 가입·로그인·JWT | 약관 조회, 공연 목록 조회 | 공연장 조회, 공통 안내 CRUD |
| 2 | 공연·회차·등급 등록, 좌석 생성(스케줄러) | 공연 상세 조회, 내 정보 | 사업체 승인·반려 |
| 3 | 좌석 점유, 예매, 결제(Mock) | 탈퇴, 공연 조회 다듬기 | 공연장 신청 처리, 감사 로그 조회 |
| 4 | 취소·환불, 스케줄러, 부하 테스트 | 내 예매 조회 | 환불 실패 목록, 통합 테스트 |

- **재환님의 1주차 최우선**은 JWT 뼈대와 계정 종류 분리입니다. 나머지 모든 기능이 로그인 정보를 필요로 해서, 이게 늦어지면 전체가 같이 밀립니다.
- 로그인이 필요 없는 공개 조회 기능부터 시작하고, 로그인이 완성되면 접근 제한을 한 줄로 붙입니다.
- 사업체 승인(B)은 판매자 가입 신청(재환님)과 **"사업체 상태" 값 하나**로만 만납니다.

### 같이 일하는 약속

- 구현 전에 **API 명세(요청·응답 예시)**를 먼저 정합니다.
- 재환님이 **시드 데이터**(공연장, 공연, 회차)를 먼저 넣어 둡니다. A가 조회 기능을 바로 만들 수 있습니다.
- PR은 **기능 하나 단위**로 작게 올립니다.
- 리뷰 기준은 "동작하는가"보다 먼저 **"문서(이 설계)와 같은가"**입니다.
- 초보자가 건드리지 않는 파일: 결제, 좌석 점유, 스케줄러, 보안 설정.

---

## 부록 E. 한 달 범위와 확장 목록

### 한 달 안에 만드는 것 (필수)

- 세 계정의 가입·로그인, 판매자 승인
- 공연장 시드(샘플 공연장) + 관리자 공연장 생성 API
- 공연 등록 → 등급·가격 → 회차 추가 → 오픈 전날 좌석 생성
- 공연 조회, 구역 요약·좌석 조회
- 좌석 점유(락, 1인 매수 검사 포함) → 예매 → 결제(Mock → 토스) → 내 예매
- 취소(전체·일부)와 환불, 환불 실패 재처리
- 스케줄러: 만료 점유 정리, 결제 불일치 보정
- 동시성 테스트와 부하 테스트 (프로젝트의 핵심 이야기)

### 시간이 남으면

대기열, 캡챠, Redis 좌석 캐시, 직원 초대, 공연 연기, 감사 로그 화면

### 확장으로 미루는 것

| 확장 | 설명 | 관련 테이블 |
|---|---|---|
| 가격 종류 | 일반·청소년·장애인 할인 등 | `seat_grade` 아래에 가격 종류 테이블 추가 |
| 판매 정책 관리 | 대기열·캡챠·수수료 기본값을 관리자가 화면에서 변경 | 설정 테이블 추가 |
| 배송 상세 | 배송 상태 추적, 배송비 | `booking`의 배송 컬럼 → 배송 테이블로 분리 |
| 공연 검수 | 공개 전에 관리자 승인 | `performance.status`에 `REVIEWING` 추가 |
| 사업체 정지 | 문제 있는 사업체 이용 정지 | `seller_business.status`에 `SUSPENDED`(이미 값 있음) 활용 |
| 휠체어석, 사선 쐐기 구역 | 샘플 공연장 도면의 제외 구역 | `venue_seat`에 속성 컬럼 추가 |
| 공연장 신청 고도화 | 사이트 안 채팅·첨부 | `venue_request` 확장 |
| 알림 | 환불 실패·연기 공지를 이메일·슬랙으로 | 알림 테이블 추가 |
| 로그 보관 | 오래된 `audit_log`를 파일로 내보내 S3 등으로 이동 | 배치 작업 |
| 대용량 대비 | `schedule_seat` 파티셔닝, 끝난 회차 정리 주기 조정 | 기존 테이블 |
| 두 번째 샘플 공연장 | 소극장(좌석 구조가 다른 예) | 시드 파일 추가 |

---

## 부록 F. 이전 문서와 달라진 점

처음 만든 설계 문서(`erd-ticketing.md`, `table-guide-for-team.md`)와 이 문서 세트가 다른 부분이 있습니다. **이 문서 세트(1부~5부)가 최종**입니다.

| 항목 | 이전 | 최종 |
|---|---|---|
| 계정 로그인 | `login_id` | **이메일** |
| 공연 상태 | `DRAFT/SCHEDULED/ON_SALE/PAUSED/ENDED/FINISHED/CANCELED` | `DRAFT/PUBLISHED/CANCELED` (판매 상태는 회차) |
| 예매 수수료, 대기열, 캡챠 | 모두 공연 컬럼 | 수수료만 공연 컬럼, 대기열·캡챠는 서비스 고정 |
| 좌석 좌표 | `pos_x`, `pos_y` | 제거 (프론트가 `layout_key`로 도면을 고름) |
| 좌석 열·번호 | 숫자 | 문자열 + `sort_order` |
| 예매 상태 | `REFUND_FAILED` 포함 | 제외 (환불 상태는 `refund`), `PARTIAL_CANCELED` 추가 |
| 부분 취소 | 없음 | 지원 (`booking_seat`별 취소) |
| 취소 규정 | 예매가 규정을 참조 | 예매에 JSON(`jsonb`)으로 복사 |
| `schedule_seat` | 영구 보관 | 끝난 회차는 일정 기간 뒤 삭제 |
| `schedule_seat.zone_id` | 없음 | 추가 |
| `seller_member.last_login_at`, `ci_hash` | 대표만 `ci_hash` | `last_login_at` 제거, 담당자도 본인인증 |
| 삭제 방식 | 일부만 | 계정 테이블은 `deleted_at`(소프트 삭제), 공연장은 `status` |

다른 문서(`erd-ticketing.md`, `table-guide-for-team.md`)를 팀에 공유하지 않도록 주의해 주세요. 이 값들이 서로 달라서 헷갈릴 수 있습니다.

---

## 부록 G. 2026-11-10 이후 변경 사항 (1~5부에 반영 완료)

| 항목 | 변경 | 위치 |
|---|---|---|
| 판매 좌석 생성 시점 | 회차 추가 때 → **판매 오픈 24시간 전** (스케줄러). `performance_schedule.seats_generated_at` 추가 | 3부 3-5, 5부 |
| 등급·가격 수정 가능 시점 | 예매가 생기기 전까지 → **좌석 생성 전까지** (회차 일시는 예매가 생긴 뒤 수정 불가) | 3부 3-5 |
| 판매자 보류석 지정 | 좌석이 만들어진 뒤에만 가능 (앞당겨 생성 가능) | 3부 |
| 예매자 `ci_hash` | 탈퇴 제외 부분 UNIQUE, NULL 허용. 탈퇴 시 개인정보 비식별 처리 | 1부 |
| `booking.performance_id` | 추가 (공연 단위 매수 검사·조회) | 4부 |
| 좌석 점유와 예매 생성 | **한 트랜잭션**으로 합침 (점유 시점에 PENDING 예매 생성) | 4부 4-6 |
| 1인 최대 매수 | 사용자·공연 단위 잠금 + `booking_seat` 활성 수 집계. 취소분 제외, 한도는 예매 뒤 늘리기만 가능 (임시) | 3부, 4부 4-7 |
| `refund` | `idempotency_key` 추가, 상태 `PROCESSING` 추가 | 4부, 5부 |
| 연기 무료 취소 판정 | 최신 연기 이력 + 예매 생성 시각 기준 규칙 | 3부 |
| `schedule_seat` 설정 | 만료 정리용 부분 인덱스, `fillfactor` | 3부 3-5 |
| 구현 방침 | Flyway SQL 마이그레이션, 대부분 논리 FK | 부록 C |

나중에 할 일(운영·성능 점검 항목, 대기열·캡챠·매크로 설계 등)은 `doc/later/` 폴더에 모아 두었습니다.

---

## 마무리

- 테이블은 **26개**이고, 그중 ★ 핵심은 **15개**입니다. 나머지는 곁가지라서 순서대로 만들면 됩니다.
- 처음 보면 무섭지만, 한 부씩 읽으면 모두 **"누가, 무엇을, 얼마에"** 라는 하나의 이야기입니다.
- 막히면 1부부터 5부 문서의 Q&A를 먼저 찾아보고, 그래도 안 풀리면 질문해 주세요.
