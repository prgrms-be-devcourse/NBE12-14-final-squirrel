# API 명세서 (필수 기능)

기준: `dev` 브랜치 코드(2026-11-12 확인) + 레포 `doc/*.md`. 상태가 **구현됨**이면 코드 그대로, **미구현**이면 문서 기준 초안입니다.

## 공통

**응답 틀** (`ApiResponse`) — 모든 API 공통. 아래 각 API의 응답은 `data` 안의 내용만 보여주지 않고 전체를 적었습니다.

```json
{ "success": true, "code": "0000", "message": "", "data": { } }
```

```json
{ "success": false, "code": "AUTH001", "message": "이메일 또는 비밀번호가 올바르지 않습니다.", "data": null }
```

- `@Valid` 검증 실패: `400 COMMON000`, `message`에 `"필드: 사유, 필드: 사유"`로 이어서 내려감
- 데이터 없는 성공: `data: null`
- 시간은 `yyyy-MM-ddTHH:mm:ss`, 금액은 원 단위 정수

**URL / 권한** (`SecurityConfig` 기준)

| 구분 | 경로 | 권한 |
| --- | --- | --- |
| 인증 | `/api/v1/auth/**` | 누구나 |
| 공개 조회 | GET `/api/v1/terms`, `/api/v1/performances`, `/api/v1/performances/*`, `/api/v1/schedules/*/zones`, `/api/v1/schedules/*/zones/*/seats` | 누구나 (GET만. 같은 경로의 쓰기 요청은 로그인 필요) |
| 예매자 | `/api/v1/users/**`, `/api/v1/bookings/**`, `/api/v1/payments/**`, `/api/v1/schedules/*/bookings` | 예매자만 (판매자·관리자 계정은 403) |
| 판매자 | `/api/v1/seller/**` | 판매자 (대표·STAFF). 대표만 가능한 기능은 메서드에 `@PreAuthorize("hasRole('SELLER_OWNER')")` |
| 관리자 | `/api/v1/admin/**` | 관리자 |
| 그 밖 | 위에 없는 경로 | 로그인 (계정 종류는 가리지 않음) |

- **권한은 두 단계**입니다. ① 계정 종류·역할은 시큐리티가 세션으로 검사하고(위 표), ② "이 데이터가 내 것인가"는 서비스가 세션의 번호(`account.getId()`, `account.getBusinessId()`)로 조회해서 확인합니다. 남의 것은 403이 아니라 **404**로 답합니다.
- 세션의 역할은 로그인한 시점 값이라, 계정이 비활성화돼도 세션이 만료될 때(마지막 요청 후 30분)까지는 통합니다. 되돌리기 어려운 작업에서 최신 상태가 중요하면 서비스에서 DB로 한 번 더 확인합니다.
- 새 API를 만들면 위 표의 어느 줄에 들어가는지 확인하고, 공개 조회나 예매자 전용이면 `SecurityConfig`의 `PUBLIC_READ_PATHS`, `USER_ONLY_PATHS`에 경로를 추가합니다.

- 인증: 로그인하면 `SESSION` 쿠키가 내려가고, 이후 요청은 그 쿠키만 보냅니다. (HttpOnly, SameSite=Lax, 마지막 요청 후 30분. JWT는 2026-11-26에 없앴고 `Authorization` 헤더는 쓰지 않습니다)
- 페이징 요청: `page`(0부터), `size`. 기본 크기와 최대 크기는 API마다 정합니다. (공통 설정의 최대는 50이고, 사업체 목록은 기본 10·최대 20) 크기가 최대를 넘으면 에러가 아니라 최대로 줄어듭니다.
- **목록 응답 구조 (확정)**: `data`에 `content`(항목 배열), `page`(현재 페이지, 0부터), `size`, `totalElements`, `totalPages`가 들어갑니다. (`PageResponse`)

**공통 에러 코드** (구현됨)

| HTTP | 코드 | 상황 |
| --- | --- | --- |
| 400 | `COMMON000` | 요청 값·형식 오류 |
| 405 | `COMMON001` | 지원하지 않는 메서드 |
| 404 | `COMMON002` | 없는 경로 |
| 409 | `COMMON003` | DB 제약 충돌 |
| 409 | `COMMON004` | 분산락을 기다리는 시간 안에 잡지 못함 (같은 좌석·같은 사용자의 요청이 몰림) |
| 503 | `COMMON005` | 비밀번호 해시 계산이 몰려 1초 안에 차례가 오지 않음 (로그인·가입). `Retry-After: 1` 헤더가 붙고, 잠시 뒤 다시 시도하면 된다 |
| 500 | `COMMON999` | 서버 오류 |
| 401 | `AUTH000` / `AUTH200` / `AUTH201` / `AUTH202` | 인증 실패 / 토큰 없음 / 토큰 무효 / 토큰 만료 |
| 403 | `AUTH100` | 권한 없음 |
| 400 | `AUTH005` | 비밀번호와 비밀번호 확인이 다름 (예매자·판매자 가입, 비밀번호 재설정 공통) |
| 429 | `AUTH004` | 로그인 실패가 많아 잠시 막힘 (로그인 API 공통, 횟수·시간은 `app.auth.login.*`, 임시 정책) |

미구현 API의 에러는 **코드 미정**으로 HTTP 상태와 상황만 적었습니다.

## 목록

| ID | 이름 | 메서드 · URL | 상태 |
| --- | --- | --- | --- |
| AUTH-01 | 약관 조회 | GET `/api/v1/terms` | 구현됨 |
| AUTH-02 | 예매자 가입 | POST `/api/v2/auth/users/signup` | 구현됨 |
| AUTH-06 | 내 정보 조회 | GET `/api/v1/users/me` | 구현됨 |
| AUTH-07 | 내 정보 수정 | PATCH `/api/v1/users/me` | 구현됨 |
| AUTH-08 | 회원 탈퇴 | DELETE `/api/v1/users/me` | 구현됨 |
| AUTH-09 | 판매자 가입 신청 | POST `/api/v1/auth/sellers/signup` | 구현됨 |
| AUTH-12 | 예매자 로그인 | POST `/api/v2/auth/users/login` | 구현됨 |
| AUTH-13 | 판매자 로그인 | POST `/api/v2/auth/sellers/login` | 구현됨 |
| AUTH-14 | 관리자 로그인 | POST `/api/v2/auth/admins/login` | 구현됨 |
| AUTH-15 | 로그아웃 | POST `/api/v2/auth/logout` | 구현됨 |
| PFM-01 | 공연 목록·검색 | GET `/api/v1/performances` | 구현됨 |
| AUTH-17 | 가입 전 이메일 인증번호 발송 (예매자·판매자) | POST `/api/v1/auth/users/email-verification` · `/api/v1/auth/sellers/email-verification` | 구현됨 |
| AUTH-18 | 가입 전 이메일 인증번호 확인 (예매자·판매자) | POST `/api/v1/auth/users/email-verification/confirm` · `/api/v1/auth/sellers/email-verification/confirm` | 구현됨 |
| AUTH-19 | 예매자 비밀번호 재설정 메일 요청 | POST `/api/v1/auth/users/password-reset/request` | 구현됨 |
| AUTH-20 | 판매자 비밀번호 재설정 메일 요청 | POST `/api/v1/auth/sellers/password-reset/request` | 구현됨 |
| AUTH-21 | 재설정 링크 확인 | POST `/api/v1/auth/password-reset/validate` | 구현됨 |
| AUTH-22 | 비밀번호 재설정 | POST `/api/v1/auth/password-reset/confirm` | 구현됨 |
| AUTH-23 | 담당자 초대 (판매자 대표) | POST `/api/v1/seller/invitations` | 구현됨 |
| AUTH-24 | 초대 링크 확인 | POST `/api/v1/auth/sellers/invitations/validate` | 구현됨 |
| AUTH-25 | 담당자 가입 | POST `/api/v1/auth/sellers/invitations/accept` | 구현됨 |
| AUTH-26 | 담당자 목록 (판매자) | GET `/api/v1/seller/members` | 구현됨 |
| AUTH-27 | 담당자 비활성화 (판매자 대표) | POST `/api/v1/seller/members/{memberId}/deactivate` | 구현됨 |
| AUTH-28 | 담당자 복구 (판매자 대표) | POST `/api/v1/seller/members/{memberId}/reactivate` | 구현됨 |
| PFM-02 | 공연 상세 | GET `/api/v1/performances/{performanceId}` | 구현됨 |
| PFM-03 | 구역 요약 | GET `/api/v1/schedules/{scheduleId}/zones` | 구현됨 |
| PFM-04 | 구역 좌석 상세 | GET `/api/v1/schedules/{scheduleId}/zones/{zoneId}/seats` | 구현됨 |
| BKG-01 | 좌석 점유·예매 생성 | POST `/api/v1/schedules/{scheduleId}/bookings` | 구현됨 |
| BKG-02 | 결제 준비 | POST `/api/v1/bookings/{bookingId}/payments` | 구현됨 |
| BKG-03 | 결제 승인 | POST `/api/v1/payments/confirm` | 구현됨 |
| BKG-04 | 내 예매 목록 | GET `/api/v1/bookings` | 구현됨 |
| BKG-05 | 내 예매 상세 | GET `/api/v1/bookings/{bookingId}` | 구현됨 |
| BKG-06 | 예매 취소 | POST `/api/v1/bookings/{bookingId}/cancel` | 구현됨 |
| SLR-01 | 공연장 목록 | GET `/api/v1/seller/venues` | 구현됨 |
| SLR-02 | 공연 등록 | POST `/api/v1/seller/performances` | 구현됨 |
| SLR-03 | 공연 수정 | PUT `/api/v1/seller/performances/{performanceId}` | 구현됨 |
| SLR-04 | 공연 공개 | POST `/api/v1/seller/performances/{performanceId}/publish` | 구현됨 |
| SLR-05 | 내 공연 목록 | GET `/api/v1/seller/performances` | 구현됨 |
| SLR-06 | 내 공연 상세 | GET `/api/v1/seller/performances/{performanceId}` | 구현됨 |
| SLR-08 | 등급·구역 조회 | GET `/api/v1/seller/performances/{performanceId}/grades` | 구현됨 |
| SLR-09 | 등급 추가 | POST `/api/v1/seller/performances/{performanceId}/grades` | 구현됨 |
| SLR-10 | 등급 수정 | PUT `/api/v1/seller/performances/{performanceId}/grades/{gradeId}` | 구현됨 |
| SLR-11 | 구역별 상태·등급 설정 | PATCH `/api/v1/seller/performances/{performanceId}/zone-grades` | 구현됨 |
| SLR-12 | 회차 추가 | POST `/api/v1/seller/performances/{performanceId}/schedules` | 구현됨 |
| SLR-13 | 회차 수정 | PUT `/api/v1/seller/schedules/{scheduleId}` | 구현됨 |
| SLR-14 | 판매 좌석 생성 | POST `/api/v1/seller/schedules/{scheduleId}/seats/generate` | 구현됨 |
| SLR-15 | 회차 취소 | POST `/api/v1/seller/schedules/{scheduleId}/cancel` | 미구현 |
| SLR-16 | 공연 취소 | POST `/api/v1/seller/performances/{performanceId}/cancel` | 미구현 |
| SLR-17 | 판매 현황 | GET `/api/v1/seller/performances/{performanceId}/sales` | 구현됨 |
| SLR-18 | 회차별 예매 목록 | GET `/api/v1/seller/schedules/{scheduleId}/bookings` | 구현됨 (사용 중단 예정, SLR-23 사용) |
| SLR-19 | 취소 규정 목록 | GET `/api/v1/seller/cancel-policies` | 구현됨 |
| SLR-20 | 취소 규정 상세 | GET `/api/v1/seller/cancel-policies/{cancelPolicyId}` | 구현됨 |
| SLR-21 | 취소 규정 생성 | POST `/api/v1/seller/cancel-policies` | 구현됨 |
| SLR-22 | 취소 규정 수정 | PUT `/api/v1/seller/cancel-policies/{cancelPolicyId}` | 구현됨 |
| SLR-23 | 내 사업체 예매 목록 | GET `/api/v1/seller/bookings` | 구현됨 |
| SLR-24 | 내 사업체 예매 상세 | GET `/api/v1/seller/bookings/{bookingId}` | 구현됨 |
| SLR-26 | 회차 판매 일시 중지 | POST `/api/v1/seller/performances/{performanceId}/schedules/pause` | 구현됨 |
| SLR-27 | 회차 판매 재개 | POST `/api/v1/seller/performances/{performanceId}/schedules/resume` | 구현됨 |
| ADM-01 | 사업체 목록·검색 | GET `/api/v1/admin/seller-businesses` | 구현됨 |
| ADM-02 | 사업체 승인 | POST `/api/v1/admin/seller-businesses/{businessId}/approve` | 구현됨 |
| ADM-03 | 사업체 반려 | POST `/api/v1/admin/seller-businesses/{businessId}/reject` | 구현됨 |
| ADM-04 | 공연장 생성 | POST `/api/v1/admin/venues` | 미구현 |
| ADM-05 | 환불 실패 목록 | GET `/api/v1/admin/refunds` | 구현됨 |
| ADM-06 | 환불 수동 재처리 | POST `/api/v1/admin/refunds/{refundId}/retry` | 구현됨 |
| ADM-07 | 사업체 상세 | GET `/api/v1/admin/seller-businesses/{businessId}` | 구현됨 |
| ADM-08 | 전체 예매 목록 | GET `/api/v1/admin/bookings` | 구현됨 |
| ADM-09 | 예매 상세 | GET `/api/v1/admin/bookings/{bookingId}` | 구현됨 |
| IMG-01 | 이미지 업로드 | POST `/api/v1/images` | 구현됨 |
| INQ-01 | 문의 유형 목록 (예매자) | GET `/api/v1/inquiries/categories` | 구현됨 |
| INQ-02 | 문의 작성 (예매자) | POST `/api/v1/inquiries` | 구현됨 |
| INQ-03 | 내 문의 목록 (예매자) | GET `/api/v1/inquiries` | 구현됨 |
| INQ-04 | 내 문의 상세 (예매자) | GET `/api/v1/inquiries/{inquiryId}` | 구현됨 |
| INQ-05 | 문의 수정 (예매자) | PUT `/api/v1/inquiries/{inquiryId}` | 구현됨 |
| INQ-06 | 문의 삭제 (예매자) | DELETE `/api/v1/inquiries/{inquiryId}` | 구현됨 |
| ADM-14 | 문의 목록 | GET `/api/v1/admin/inquiries` | 구현됨 |
| ADM-15 | 문의 상세 | GET `/api/v1/admin/inquiries/{inquiryId}` | 구현됨 |
| ADM-16 | 문의 답변 | POST `/api/v1/admin/inquiries/{inquiryId}/answer` | 구현됨 |
| SLR-34 | 문의 유형 목록 (판매자) | GET `/api/v1/seller/inquiries/categories` | 구현됨 |
| SLR-35 | 문의 작성 (판매자) | POST `/api/v1/seller/inquiries` | 구현됨 |
| SLR-36 | 사업체 문의 목록 | GET `/api/v1/seller/inquiries` | 구현됨 |
| SLR-37 | 사업체 문의 상세 | GET `/api/v1/seller/inquiries/{inquiryId}` | 구현됨 |

---

## 1. 계정·인증

### AUTH-01 약관 조회 — `GET /api/v1/terms` (공개, 구현됨)

Query: `type`(선택) `SERVICE` / `PRIVACY` / `WITHDRAW`. 종류별 최신 버전만.

- 순서는 종류 순(`SERVICE` → `PRIVACY` → `WITHDRAW`)으로 고정입니다.
- `type`을 주면 그 종류만 주고, 그 종류의 약관이 없으면 에러가 아니라 빈 배열(`data: []`)입니다.
- `termId`는 가입 요청의 `agreedTermIds`에 그대로 보냅니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": [
    {
      "termId": 1,
      "type": "SERVICE",
      "version": "1.0",
      "content": "제1조 (목적) ...",
      "required": true
    },
    {
      "termId": 2,
      "type": "PRIVACY",
      "version": "1.0",
      "content": "개인정보 수집·이용 ...",
      "required": true
    }
  ]
}
```

**에러**: 400 `COMMON000` 알 수 없는 `type` 값

### AUTH-02 예매자 가입 — `POST /api/v2/auth/users/signup` (공개, 구현됨)

가입하면 바로 로그인됩니다. `SESSION` 쿠키가 내려가고, 받은 쿠키로 `GET /api/v1/users/me` 같은 업무 API를 바로 쓸 수 있습니다.

**가입 전에 이메일 인증(AUTH-17·18)을 마친 이메일만 가입할 수 있습니다.** 인증은 20분 동안 유효하고 가입에 성공하면 소모됩니다. 가입한 회원은 인증을 마친 것이라 가입 시각이 `email_verified_at`에 들어갑니다.

**요청**

```json
{
  "email": "user@example.com",
  "password": "password1!",
  "passwordConfirm": "password1!",
  "nickname": "홍길동",
  "name": "홍길동",
  "phone": "01012345678",
  "agreedTermIds": [1, 2]
}
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 이메일 형식, 최대 255자, 앞뒤 공백 제거, 대소문자 구분 없음 |
| `password` | 8~25자, 영문·숫자·특수문자 포함 |
| `passwordConfirm` | 필수, `password`와 같아야 함 (다르면 400 `AUTH005`, 서비스에서 확인) |
| `nickname` | 2~20자 |
| `name` | 필수, 최대 50자, 앞뒤 공백 제거 |
| `phone` | 하이픈 없는 휴대폰 번호 (`01X` + 7~8자리) |
| `agreedTermIds` | 최신 필수 약관을 모두 포함 |

**응답 201** (`Set-Cookie: SESSION=...; Path=/; HttpOnly; SameSite=Lax`)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accountType": "USER",
    "role": null
  }
}
```

가입이 실패하면(이메일 중복 `USER000`, 필수 약관 미동의 등) 세션이 만들어지지 않고 회원도 저장되지 않습니다.

**에러** (확인 순서대로): 400 `COMMON000` 형식 오류 · 400 `AUTH307` 이메일 인증을 안 했거나 20분이 지남 · 400 `TERMS000` 필수 약관 미동의 · 400 `TERMS001` 없는 약관 · 400 `TERMS002` 개정 전 약관 · 400 `AUTH005` 비밀번호 확인 불일치 · 409 `USER000` 이메일 중복

### AUTH-06 내 정보 조회 — `GET /api/v1/users/me` (예매자, 구현됨)

회원 번호는 경로로 받지 않고 토큰에서 꺼냅니다. 비밀번호는 내려가지 않습니다. 정지된 회원도 토큰이 살아 있는 동안은 조회할 수 있습니다. (로그인·재발급에서 이미 막힘)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "userId": 5,
    "email": "user@example.com",
    "name": "홍길동",
    "nickname": "홍길동",
    "phone": "01012345678",
    "profileImg": null,
    "createdAt": "2026-11-20T14:03:21"
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 404 `USER002` 회원 없음(탈퇴 후 토큰이 만료되기 전)

### AUTH-07 내 정보 수정 — `PATCH /api/v1/users/me` (예매자, 구현됨)

닉네임·프로필 이미지를 **보낸 값으로 통째로 바꿉니다.** 바꿀 수 있는 값은 이 둘뿐입니다. (이름·휴대폰은 이 API로 수정하지 않음) **화면은 현재 값을 채운 수정 폼을 그대로 보냅니다.** 닉네임만 바꿀 때도 기존 `profileImg`를 함께 보내야 사진이 유지됩니다.

- `nickname`: **필수**. 2~20자, 공백만으로는 불가, 앞뒤 공백은 지우고 저장 (가입과 같은 규칙)
- `profileImg`: 선택. 이미지 업로드(IMG-01)로 올린 **우리 저장소 주소(`IMAGE_BASE_URL` + `/`로 시작하는 https)만** 받고 공백 없음, 2048자 이하. 다른 사이트 주소·http는 400. **생략하거나 `null`이면 사진이 지워집니다.** (빈 문자열 `""`은 400)
- 정지된 회원도 토큰이 살아 있는 동안은 수정할 수 있습니다. (로그인·재발급에서 이미 막힘)

**요청**

```json
{ "nickname": "길동이2", "profileImg": "https://cdn.example.com/profiles/5.png" }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "userId": 5,
    "nickname": "길동이2",
    "profileImg": "https://cdn.example.com/profiles/5.png",
    "updatedAt": "2026-11-21T09:15:00"
  }
}
```

**에러**: 400 `COMMON000` 입력값 오류 · 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 404 `USER002` 회원 없음(탈퇴 후 토큰이 만료되기 전)

### AUTH-08 회원 탈퇴 — `DELETE /api/v1/users/me` (예매자, 구현됨)

`deleted_at` 기록, 개인정보 비식별 처리, 지금 세션 삭제. 요청 본문은 없습니다.

- **진행 중인 예매가 있으면 탈퇴할 수 없습니다(409 `USER003`).** 예매를 취소하거나 공연이 시작된 뒤에 탈퇴합니다. 결제했거나 결제 중인 예매가 주인 없이 남으면 환불·문의를 받을 수 없기 때문입니다.
  - 진행 중인 예매: 결제 제한 시각이 남은 결제 대기(`PENDING`), 공연이 시작하지 않은 결제 완료·일부 취소(`PAID`, `PARTIAL_CANCELED`)
  - 기준은 회차 시작 시각(연기되면 바뀐 시각)입니다. 공연 당일은 취소 불가 구간이라 시작한 뒤에는 취소·환불로 돈이 오갈 일이 없어서, 공연 중이어도 탈퇴할 수 있습니다.
  - 만료(`EXPIRED`), 전체 취소(`CANCELED`), 제한 시각이 지난 결제 대기는 막지 않습니다.
- 개인정보는 설계 1부 "탈퇴 시 처리" 표대로 지웁니다. (email `deleted-{id}@withdrawn.invalid`, 이름·닉네임 `탈퇴회원`, phone `-`, 사진 NULL, 비밀번호는 로그인할 수 없는 값) 같은 이메일·본인인증으로 다시 가입할 수 있습니다.
- 탈퇴한 기기의 세션(SESSION 쿠키)은 지워져 이후 요청은 401입니다. **다른 기기의 세션은 지우지 못합니다.** (회원 번호로 세션을 찾는 설정이 없음) 남은 세션은 마지막 요청 후 30분 뒤 사라지고, 그 사이 내 정보 조회·수정은 404 `USER002`, 재로그인은 막힙니다.

**응답 200**

```json
{  "success": true,  "code": "0000",  "message": "",  "data": null}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 404 `USER002` 이미 탈퇴함 · 409 `USER003` 진행 중인 예매가 있음

### AUTH-09 판매자 가입 신청 — `POST /api/v1/auth/sellers/signup` (공개, 구현됨)

사업체(승인 대기) + 대표자 계정 생성. 승인 전에는 로그인 불가.

**신청 전에 로그인 이메일(`email`)의 인증(AUTH-17·18 판매자용)을 마쳐야 합니다.** (2026-11-24 추가) 인증은 20분 동안 유효하고 신청에 성공하면 소모됩니다. 담당 이메일(`contactEmail`)은 연락용이라 인증하지 않습니다. 예매자로 인증한 이메일로는 판매자 신청을 할 수 없고, 판매자용으로 따로 인증해야 합니다.

**요청**

```json
{
  "email": "owner@company.com",
  "password": "password1!",
  "passwordConfirm": "password1!",
  "name": "김대표",
  "phone": "01099998888",
  "companyName": "(주)가상기획",
  "representativeName": "김대표",
  "businessNumber": "1234567890",
  "contactPhone": "01012345678",
  "contactEmail": "contact@company.com",
  "termsAgreed": true
}
```

| 필드 | 규칙 |
| --- | --- |
| `companyName` | 최대 100자 |
| `passwordConfirm` | 필수, `password`와 같아야 함 (다르면 400 `AUTH005`, 서비스에서 확인) |
| `name` | 가입하는 사람(대표)의 이름. `seller_member.name`에 저장되고, 담당자 가입에서는 담당자 본인의 이름이 들어간다. 최대 50자, 앞뒤 공백 제거 |
| `phone` | 가입하는 사람(대표)의 휴대폰. 하이픈 없는 숫자 |
| `representativeName` | 사업자등록증의 대표자명. 최대 50자 (`name`과 같지 않아도 가입된다) |
| `businessNumber` | 하이픈 없는 숫자 10자리 |
| `termsAgreed` | `true`여야 함 |

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "businessId": 3,
    "status": "PENDING"
  }
}
```

**에러**: 400 `AUTH307` 이메일 인증을 안 했거나 20분이 지남 · 400 `AUTH005` 비밀번호 확인 불일치 · 409 `SELLER000` 이메일 중복 · 409 `SELLER001` 사업자번호 중복(반려된 사업체의 번호도 포함, 재신청 불가) · 400 `COMMON000`

### AUTH-12~15 로그인·로그아웃 (세션, 공개, 구현됨)

**Redis 세션**으로 로그인합니다. 로그인하면 `SESSION` 쿠키가 내려가고, 그 뒤에는 **경로를 바꾸지 않은 업무 API**(`/api/v1/users/me`, `/api/v1/admin/**` 등)를 쿠키만 보내서 호출합니다. 로그아웃하면 서버가 세션을 지워서 **바로** 끊기고, 세션은 마지막 요청 후 **30분**(요청이 오면 연장) 뒤 만료됩니다. (JWT는 2026-11-26에 없앴습니다)

- `SESSION` 쿠키는 `HttpOnly`, `SameSite=Lax`, `Path=/`이고 운영(https)에서는 `Secure`입니다. 스크립트로 읽을 수 없습니다.
- 요청은 `{ "email", "password" }`입니다. 판매자는 사업체가 승인된 뒤에만 로그인되고(`SELLER003`~`SELLER005`), 로그인할 때 이미 세션이 있으면 세션 번호를 새로 바꿉니다.
- 판매자 가입 신청(AUTH-09)은 로그인(세션)을 만들지 않아서 경로가 `/api/v1/auth/sellers/signup` 그대로입니다.
- 관리자는 가입과 비밀번호 변경이 없습니다. 로컬에서는 `db/seed/V19`의 시드 계정(`admin@tickting.local`)으로 로그인하고, **비밀번호는 저장소에 없어서 팀에서 따로 전달**합니다.
- 로그아웃(`POST /api/v2/auth/logout`)은 본문이 없고 로그인 상태가 아니어도 200입니다.
- **CSRF 토큰은 아직 없습니다.** 지금은 `SameSite=Lax` 쿠키에만 의존합니다. 운영 전에 다시 정합니다.

**요청** (예매자 `/api/v2/auth/users/login`, 판매자 `/sellers/login`, 관리자 `/admins/login`)

```json
{ "email": "user@example.com", "password": "password1!" }
```

**응답 200** (`Set-Cookie: SESSION=...; Path=/; HttpOnly; SameSite=Lax`)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accountType": "USER",
    "role": null
  }
}
```

**에러**: 401 `AUTH001` 불일치 · 401 `AUTH003` 비활성 계정 · 403 `SELLER003` 승인 대기 · 403 `SELLER004` 반려 · 403 `SELLER005` 정지 · 429 `AUTH004` 같은 IP 에서 같은 이메일로 5번 틀려 5분 동안 잠김 (비밀번호가 맞아도) · 400 `COMMON000` 요청 값 오류

**로그아웃 응답 200**

```json
{ "success": true, "code": "0000", "message": "", "data": null }
```

### 가입 전 이메일 인증 (AUTH-17·18)

> 구현했습니다(2026-11-24). 2026-11-23에 만든 "가입 후 로그인해서 인증"(내 정보·가입 완료 화면·예매하기 모달)을 **가입 전 인증으로 바꿨습니다.** 정책 수치는 모두 임시이고 설정 `app.auth.email-verification.*`에 있습니다.
> 발송은 **비동기**입니다(2026-11-23 결정). 발송 API는 발송을 접수하면 바로 응답하고, 메일은 서버가 뒤에서 보냅니다. 인기 공연 오픈 때 인증하는 사람이 몰려도 발송이 요청 스레드를 묶어 대기열·예매까지 밀리지 않게 하려는 것입니다.

**왜 필요한가**: 예매자는 **가입하기 전에** 입력한 이메일의 주인임을 인증해야 합니다. 가입 화면에서 이메일을 입력하고 옆의 "이메일 인증" 버튼으로 인증번호(숫자 6자리)를 받아 확인한 뒤, 같은 이메일로 가입 API(AUTH-02·16)를 부릅니다. 인증하지 않은 이메일로 가입하면 400 `AUTH307`입니다. **판매자도 가입 신청(AUTH-09) 전에 로그인 이메일을 인증합니다.**(2026-11-24 변경: 처음에는 판매자를 대상에서 뺐으나, 판매자 로그인 이메일이 비밀번호 찾기 메일을 받는 곳이라 주인 확인이 필요해 넣었다. 판매자 승인은 사업체를 심사하는 것이고 이메일 주인은 확인하지 않는다) 관리자는 가입이 없어 대상이 아닙니다. 가입한 회원은 모두 인증을 마친 것이라, **예매하기(대기열 입장)에서는 인증을 따로 확인하지 않습니다.**

**흐름 (화면)**

1. 이메일 입력 → "이메일 인증" 버튼 → AUTH-17(발송). 입력칸 옆에 재발송 타이머와 인증번호 입력칸을 보여 줍니다.
2. 인증번호 입력 → AUTH-18(확인). 성공하면 **이메일 입력칸을 잠그고** "인증 완료"를 보여 줍니다. 오타를 발견하면 "변경" 버튼으로 인증을 풀고 이메일을 다시 입력·인증합니다. (인증은 그 이메일 문자열에만 유효합니다)
3. 가입 버튼 → AUTH-02(또는 AUTH-16)에 **인증한 이메일 그대로** 보냅니다. 인증이 끝나지 않았으면 가입 버튼을 막습니다.

서버는 화면과 상관없이 가입 API에서 인증을 확인합니다. (화면만 막으면 API를 직접 부르는 요청이 가입할 수 있어서)

**정책** (값은 임시, 설정 `app.auth.email-verification.*`)

| 항목 | 값 |
| --- | --- |
| 인증번호 | 숫자 6자리 |
| 유효 시간 | 5분 |
| 틀린 횟수 | 5번 틀리면 그 인증번호는 폐기 (새로 받아야 함) |
| 재발송 간격 | 60초 (이메일 기준) |
| 하루 발송 한도 | 이메일당 10회, 한국 시간 자정에 초기화 |
| 재발송하면 | 이전 인증번호는 바로 무효 |
| 인증에 성공하면 | 인증번호는 바로 삭제 (한 번만 쓰임). 그 이메일이 **20분 동안** 인증됨 |
| 인증이 유효한 시간 | 20분. 넘으면 다시 인증 (인증번호 확인 응답의 `verifiedUntil`) |
| 가입에 성공하면 | 그 이메일의 인증은 바로 소모됨. 가입이 실패하면(약관·비밀번호 확인 등) 그대로라서 인증을 다시 하지 않고 고쳐서 가입할 수 있음 |
| 발송 방식 | 비동기. 접수하면 바로 응답하고, 서버 안의 발송 스레드가 보냄 (서버마다 발송 스레드 10개, 대기 200개) |
| 발송 대기가 가득 차면 | 바로 거절(503 `AUTH304`). 재발송 간격·하루 횟수에 넣지 않음 |
| 발송이 나중에 실패하면 | 하루 횟수는 돌려주고 그 인증번호는 폐기. 60초 간격은 그대로라 다시 받으려면 60초 뒤. (그사이 다시 받아서 새 번호가 생겼다면 새 번호는 그대로) |
| 메일 서버 응답 | 연결 3초, 읽기 5초, 쓰기 5초 안에 응답이 없으면 실패로 처리 |

- 로그인 없이 부르는 공개 API이고 인증할 이메일을 요청으로 받습니다. 이메일은 앞뒤 공백을 지우고 소문자로 맞춰 같은 이메일로 봅니다. (가입과 같은 규칙)
- **이미 가입한(탈퇴하지 않은) 이메일로는 번호를 보내지 않고 409 `USER000`입니다.** 가입 API도 중복을 알려 주므로 노출이 늘지 않고, 남의 메일함에 메일을 보내는 길이 줄고, 한도 키도 만들지 않습니다.
- 인증번호·인증 완료 표시는 Redis에만 둡니다. 키에는 이메일의 SHA-256 해시만 남기고 이메일 원문은 두지 않습니다. 인증을 마친 이메일로 가입하면 가입 시각이 `user_member.email_verified_at`에 남습니다.
- 인증 표시가 있는 상태에서 같은 이메일로 동시에 가입해도 한 명만 만들어집니다. (마지막 방어선은 DB의 이메일 UNIQUE 인덱스입니다)
- 메일은 HTML과 텍스트를 함께 담고(2026-11-24 HTML 추가, 서비스 화면과 같은 색) 보낸 사람 이름은 "리셀노"입니다. 인증번호·유효 시간·"본인이 요청하지 않았다면 무시" 안내만 담고 링크는 넣지 않습니다. 서비스 이름은 설정값입니다.
- **발송에는 캡챠를 겁니다**(2026-11-24). 로그인 없는 공개 API라 이메일을 바꿔 가며 보내는 공격을 이메일별 한도만으로는 막지 못하기 때문입니다. 요청 헤더 `X-Captcha-Token`에 reCAPTCHA v3 토큰을 보내고, 프론트가 토큰을 받을 때 넘기는 action은 `email_verify_send`입니다. 확인(`/confirm`)과 가입에는 걸지 않습니다. 규칙은 아래 "예매하기(대기열 입장)의 캡챠"와 같습니다. (토큰은 한 번만 쓰고 약 2분 뒤 만료되므로 재발송마다 새로 받습니다) IP별 한도는 없습니다. (`doc/seller-progress.md`의 "후속 TODO" 참고. IP는 Nginx 뒤라 Vercel 서버 주소로 보여 넣기 어렵습니다. 비밀번호 찾기와 같은 이유)

#### AUTH-17 가입 전 이메일 인증번호 발송 — `POST /api/v1/auth/users/email-verification` (공개, 캡챠)

헤더 `X-Captcha-Token`: reCAPTCHA v3 토큰 (action `email_verify_send`).

> **판매자용은 `POST /api/v1/auth/sellers/email-verification`입니다.** 요청·응답·에러·캡챠가 같고 다른 것은 둘입니다.
> ① 이미 가입한 이메일의 판단 기준이 판매자 계정입니다(409 `SELLER000`). 예매자로 가입한 이메일도 판매자 인증은 받을 수 있습니다. 두 계정은 테이블이 다릅니다.
> ② **인증 상태와 횟수 제한을 계정 종류별로 따로 둡니다.** 예매자로 인증한 이메일로 판매자 가입 신청을 할 수 없고(400 `AUTH307`), 반대도 같습니다. 재발송 간격(60초)과 하루 한도(10회)도 따로 셉니다.

**요청**

```json
{ "email": "user@example.com" }
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 이메일 형식, 최대 255자, 앞뒤 공백 제거, 대소문자 구분 없음 |

발송을 **접수**하면 바로 응답합니다. 200은 메일이 도착했다는 뜻이 아니고, 메일은 곧이어 서버가 보냅니다. 메일 서버 상태에 따라 늦거나 가지 않을 수 있고, 그 실패는 이 응답으로 알려 주지 않습니다. (위 "발송이 나중에 실패하면") 발송 대기가 가득 차서 접수하지 못한 경우만 503 `AUTH304`로 알립니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "expiresAt": "2026-11-22T14:05:00",
    "resendAvailableAt": "2026-11-22T14:01:00"
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `expiresAt` | 이번 인증번호의 유효 시각 (5분 뒤) |
| `resendAvailableAt` | 다시 보낼 수 있는 시각 (60초 뒤) |

**에러** (확인 순서대로): 403 `CAPTCHA000` 캡챠 토큰이 없거나 실패 · 503 `CAPTCHA001` 캡챠 서버(구글)에 연결할 수 없음 · 400 `COMMON000` 형식 오류 · 409 `USER000` 이미 가입한 이메일 (판매자용은 `SELLER000`, 메일을 보내지 않음) · 429 `AUTH302` 60초 안에 다시 보냄 · 429 `AUTH303` 오늘 발송 한도(10회) 초과 · 503 `AUTH304` 발송 대기가 가득 참 (`Retry-After: 1`, 간격·하루 횟수에 넣지 않으므로 바로 다시 시도할 수 있음)

#### AUTH-18 가입 전 이메일 인증번호 확인 — `POST /api/v1/auth/users/email-verification/confirm` (공개)

> 판매자용은 `POST /api/v1/auth/sellers/email-verification/confirm`이고 요청·응답·에러가 같습니다. 판매자용으로 보낸 번호는 판매자용 확인에서만 맞습니다.

**요청**

```json
{ "email": "user@example.com", "code": "123456" }
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 인증번호를 받은 이메일 (AUTH-17과 같은 규칙) |
| `code` | 숫자 6자리 |

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "verifiedUntil": "2026-11-22T14:22:10"
  }
}
```

`verifiedUntil`까지 가입을 마쳐야 합니다. 넘기면 가입이 400 `AUTH307`이라 다시 인증합니다.

**에러** (확인 순서대로): 400 `COMMON000` 형식 오류 · 400 `AUTH306` 유효한 인증번호가 없음 (만료, 5번 틀려 폐기, 받은 적 없음 — 다시 받아야 함) · 400 `AUTH305` 인증번호가 틀림 (메시지에 남은 횟수, 예: "인증번호가 올바르지 않습니다. (남은 횟수 3회)")

- 5번째로 틀리면 그 인증번호가 폐기되므로 `AUTH305`가 아니라 `AUTH306`입니다.
- 같은 인증번호로 동시에 확인해도 한 번만 성공합니다.
- 이미 가입한 이메일인지는 확인하지 않습니다. 가입한 이메일은 번호를 받을 수 없어서 확인할 번호가 없기 때문입니다.

#### 에러 코드

| HTTP | 코드 | 상황 | 화면 처리 |
| --- | --- | --- | --- |
| - | `AUTH301` | 쓰지 않음. 로그인 후 인증이 없어지면서 비웠고 번호를 다시 쓰지 않는다 | - |
| 429 | `AUTH302` | 60초 안에 다시 발송 | "잠시 후 다시 보낼 수 있습니다" |
| 429 | `AUTH303` | 하루 발송 한도 초과 | "오늘은 더 보낼 수 없습니다. 내일 다시 시도해 주세요" |
| 503 | `AUTH304` | 발송 대기가 가득 참 (오픈 직후 등 몰릴 때) | "요청이 많습니다. 잠시 후 다시 시도해 주세요" |
| 400 | `AUTH305` | 인증번호가 틀림 (기회 남음) | 서버 메시지(남은 횟수 포함)를 그대로 보여 줌 |
| 400 | `AUTH306` | 유효한 인증번호가 없음 | "인증번호를 다시 받아 주세요" |
| 400 | `AUTH307` | 인증을 안 했거나 20분이 지난 이메일로 가입함 (예매자 가입 API와 판매자 가입 신청 API가 냄) | 이메일 인증 상태를 풀고 "이메일 인증을 다시 해 주세요" |

#### 화면 참고

- 발송 응답의 `resendAvailableAt`까지 재발송 버튼을 막고 "N초 후 재발송"을 보여 주고, `expiresAt`으로 남은 유효 시간을 보여 줍니다. 응답이 오기 전까지는 버튼을 비활성화해 중복 클릭을 줄입니다. (서버도 막습니다)
- 발송 200은 **메일이 도착했다는 뜻이 아닙니다.** "메일이 오지 않으면 스팸함을 확인하고, 60초 뒤 다시 받아 주세요"를 함께 안내합니다. 메일 서버 실패는 응답으로 오지 않습니다.
- "메일을 여러 통 받았다면 **가장 최근 메일**의 번호를 입력하세요"를 한 줄 넣습니다. 다시 받으면 이전 번호는 바로 무효이기 때문입니다.
- `AUTH305`는 서버 메시지(남은 횟수 포함)를 그대로 보여 주고, `AUTH306`이면 입력칸을 비우고 "다시 받기"를 안내합니다.
- 발송이 `USER000`(409)이면 "이미 가입한 이메일입니다"를 보여 주고 로그인·비밀번호 찾기로 안내합니다. 판매자 화면은 `SELLER000`(409)이면 "이미 판매자로 가입한 이메일입니다"와 판매자 로그인 링크를 보여 줍니다.
- 판매자 가입 신청 화면은 예매자 가입 화면과 같은 부품(`EmailVerifyField`, `kind="seller"`)을 씁니다. 로그인 이메일 옆에서 인증하고, 인증 전에는 신청 버튼을 막습니다.
- 인증을 마친 뒤 이메일 입력칸은 잠그고 "변경" 버튼을 둡니다. 20분(`verifiedUntil`)이 지나면 인증 상태를 풀어 다시 인증하게 합니다.
- 가입이 `AUTH307`이면 인증 상태를 풀고 다시 인증하게 합니다. 가입이 다른 이유로 실패한 경우에는 인증이 남아 있으므로 풀지 않습니다.

#### 예매하기(대기열 입장)의 변경

2026-11-23에는 `POST /api/v1/schedules/{scheduleId}/queue`가 줄을 서기 전에 이메일 인증 여부를 확인해 미인증이면 403 `AUTH307`로 거절했습니다. **2026-11-24에 이 확인을 없앴습니다.** 가입한 회원은 모두 인증을 마친 상태라 걸릴 일이 없고, 오픈 순간 모든 입장에 DB 조회가 한 번씩 붙기 때문입니다. 좌석 점유는 입장한 예매자만 할 수 있어서 따로 확인하지 않습니다. 탈퇴한 회원은 입장할 수 있어도(토큰·세션이 남은 짧은 동안) 예매 단계의 회원 조회에서 404 `USER002`로 막힙니다.

#### 예매하기(대기열 입장)의 캡챠 (구현됨, 백엔드)

> 같은 캡챠를 가입 전 이메일 인증번호 발송(AUTH-17, action `email_verify_send`)에도 씁니다. 인터셉터가 경로마다 action을 지정하는 방식이라(2026-11-24) 두 곳의 규칙은 같고 action 이름만 다릅니다.

매크로가 좌석 화면에 들어오지 못하게 `POST /api/v1/schedules/{scheduleId}/queue`는 **줄을 서기 전에** 구글 reCAPTCHA v3 토큰을 확인합니다. 구역 요약·구역 좌석 조회·좌석 점유는 입장한 예매자만 쓸 수 있어서, 입장 한 곳만 막으면 됩니다.

- 토큰은 헤더 **`X-Captcha-Token`**으로 보냅니다. 프론트는 요청 직전에 `grecaptcha.execute(사이트 키, { action: "queue_enter" })`로 토큰을 받습니다. 토큰은 한 번만 쓸 수 있고 약 2분 뒤 만료되므로 미리 받아 두지 않습니다. 다시 줄을 설 때(`QUEUE000` 뒤 재입장 포함)마다 새로 받습니다.
- 서버는 구글 응답의 `success`만 보지 않고 **`action`이 `queue_enter`인지, `score`가 기준(`app.captcha.min-score`, 지금 0.5 임시) 이상인지**도 봅니다.
- 상태 확인(`GET`)과 나가기(`DELETE`)는 토큰이 필요 없습니다. 상태 확인은 2~3초마다 불리기 때문입니다.
- 확인 순서: 로그인·계정 종류(시큐리티, 401·403 `AUTH100`) → **캡챠(`CAPTCHA000`·`CAPTCHA001`)** → 회차(`BOOKING002`·`BOOKING012`·`BOOKING004`). 캡챠는 대기열을 끈 상태에서도 확인합니다.
- 구글에 물어볼 수 없으면(연결 실패, 응답 3초 초과, 5xx) 통과시키지 않고 503 `CAPTCHA001`입니다. (임시 정책. 장애 동안 매크로가 들어오는 것보다 막는 쪽을 골랐고, 팀이 다시 정함)
- 검증 구현은 `app.captcha.verifier`로 고릅니다. `recaptcha`는 구글에 실제로 묻고, `mock`(로컬·테스트·부하 테스트)은 토큰이 있으면 통과하고 `fail`이면 실패합니다. 키는 `.env`의 `RECAPTCHA_SECRET_KEY`(서버)와 프론트의 사이트 키입니다.

| HTTP | 코드 | 상황 | 화면 |
| --- | --- | --- | --- |
| 403 | `CAPTCHA000` | 토큰이 없음, 만료·재사용, 점수가 낮음, 다른 화면에서 받은 토큰 | "자동 입력 방지 확인에 실패" 안내. 새 토큰으로 다시 시도 |
| 503 | `CAPTCHA001` | 캡챠 서버(구글)에 연결할 수 없음, 서버의 비밀 키 설정 오류 | 잠시 후 다시 시도 |

### 비밀번호 찾기 (AUTH-19~22)

> 구현했습니다(2026-11-24). 정책 수치는 모두 임시이고 설정 `app.auth.password-reset.*`에 있습니다. 메일 링크의 프론트 주소는 `.env`의 `FRONTEND_BASE_URL`입니다. 발송은 이메일 인증처럼 **비동기**입니다.

**왜 필요한가**: 비밀번호를 잊은 예매자·판매자(대표·담당자)가 로그아웃 상태에서 가입한 이메일로 재설정 링크를 받아 비밀번호를 새로 정합니다. 관리자는 대상이 아닙니다.

**흐름**: ① 요청(이메일 입력 → 링크 메일) → ② 링크를 열면 화면이 먼저 유효 여부 확인 → ③ 새 비밀번호 제출 → 로그인 화면으로 이동. 요청(AUTH-19·20)만 계정 종류별 경로이고, 확인·변경(AUTH-21·22)은 토큰이 계정 종류를 알아서 공통 경로입니다. 이메일 유일성이 예매자·판매자 테이블마다 따로라 같은 이메일이 양쪽에 있을 수 있어서, 요청 때 화면이 종류를 고릅니다.

**정책** (값은 임시)

| 항목 | 값 |
| --- | --- |
| 링크 유효 | 15분 |
| 재요청 간격 | 60초 (계정 종류+이메일당) |
| 하루 요청 한도 | 10회 (계정 종류+이메일당) |
| IP 한도 | **두지 않음** (2026-11-24). 운영에서 프론트(Vercel)를 거치면 앱이 보는 IP가 Vercel 서버의 IP라 모든 사용자가 한 IP로 합산되어 서비스 전체가 막힐 수 있다. 사용자별 IP를 읽을 수 있게 되면 다시 검토 |
| 미가입 이메일의 한도 | 60초·하루 한도 키를 만들지 않음 (임의 이메일로 Redis 키가 늘어나는 것을 막음). 그래서 없는 이메일 요청은 횟수 제한이 없고, 오타를 고쳐 바로 다시 요청할 수 있음 |
| 새 링크를 받으면 | 이전 링크는 유효 유지 |
| 변경에 성공하면 | 그 계정의 재설정 링크를 모두 폐기, 변경 알림 메일 발송 |
| 같은 링크로 동시에 변경 | 한 번만 성공 |
| 링크 형식 | `{프론트 주소}/reset-password#token=…` (예매자·판매자 한 화면) |
| 대상 계정 | 삭제되지 않은 계정이면 상태와 무관 (관리자 제외). 로그인 차단은 로그인이 그대로 맡음 |
| 발송 | 비동기. 200은 접수이고 도착을 보장하지 않음 |
| 미가입 이메일 | 알려 줌 (가입 API도 중복을 알려 주므로 같은 수준) |
| 변경 뒤 | 자동 로그인 없음, 로그인 실패 잠금은 풀지 않음 |

- 다른 기기의 로그인 세션을 끊는 것은 이 기능에 포함하지 않습니다. 세션 방식 변경 PR이 따로 맡습니다. 그 전까지 다른 기기 세션은 남습니다.

#### AUTH-19 예매자 재설정 메일 요청 — `POST /api/v1/auth/users/password-reset/request` (공개, 구현됨)
#### AUTH-20 판매자 재설정 메일 요청 — `POST /api/v1/auth/sellers/password-reset/request` (공개, 구현됨)

두 API는 요청·응답이 같고 계정 종류만 다릅니다. 판매자는 대표·담당자 모두 대상입니다.

**요청**

```json
{ "email": "user@example.com" }
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 이메일 형식, 최대 255자, 앞뒤 공백 제거, 대소문자 구분 없음 |

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "email": "user@example.com",
    "expiresAt": "2026-11-23T14:15:00",
    "resendAvailableAt": "2026-11-23T14:01:00"
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `email` | 입력한 이메일 그대로 (화면에 "○○로 보냈습니다"를 보여 주기 위함) |
| `expiresAt` | 이번 링크의 유효 시각 (15분 뒤) |
| `resendAvailableAt` | 다시 요청할 수 있는 시각 (60초 뒤) |

**에러**: 400 `COMMON000` 형식 오류 · 404 `AUTH308` 가입되지 않은 이메일 (메시지에 예매자/판매자 표기, 반대편 계정의 가입 여부는 말하지 않음) · 429 `AUTH302` 60초 안에 다시 요청 · 429 `AUTH303` 하루 한도 초과 · 503 `AUTH304` 발송 대기가 가득 참

- 메일은 텍스트와 HTML 둘 다 담고, 제목과 본문에 "예매자 계정" 또는 "판매자 계정"을 적습니다. 버튼과 같은 링크를 글자로도 넣고, 유효 시간 15분과 "본인이 요청하지 않았다면 무시" 안내를 담습니다.

#### AUTH-21 재설정 링크 확인 — `POST /api/v1/auth/password-reset/validate` (공개, 구현됨)

링크를 열었을 때 입력 화면을 보여 주기 전에 유효한지 확인합니다. **토큰을 소모하지 않아서** 메일 보안 프로그램이나 링크 미리보기가 링크를 열어도 링크가 죽지 않습니다. 토큰이 주소와 서버 로그에 남지 않도록 `POST` 본문으로 받습니다.

**요청**

```json
{ "token": "..." }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": { "valid": true, "accountType": "USER" }
}
```

| 필드 | 설명 |
| --- | --- |
| `valid` | 링크가 유효한지. 만료·사용됨·없는 토큰은 모두 `false` |
| `accountType` | `USER`(예매자) 또는 `SELLER`(판매자). `valid`가 `false`면 `null`. 이메일은 담지 않음 |

**에러**: 400 `COMMON000` 토큰 누락

- 확인 직후 만료될 수 있어서 이 API는 불편을 줄이는 용도이고, 최종 판단은 AUTH-22가 합니다.

#### AUTH-22 비밀번호 재설정 — `POST /api/v1/auth/password-reset/confirm` (공개, 구현됨)

**요청**

```json
{ "token": "...", "password": "newPassw0rd!", "passwordConfirm": "newPassw0rd!" }
```

| 필드 | 규칙 |
| --- | --- |
| `token` | 필수 |
| `password` | 8~25자, 영문·숫자·특수문자 포함 (가입과 같은 규칙) |
| `passwordConfirm` | 필수, `password`와 같아야 함 (다르면 400 `AUTH005`) |

**응답 200**: 본문 없음 (`data`는 null). 화면이 로그인 화면으로 보냅니다. 자동 로그인은 하지 않습니다.

**에러** (확인 순서대로): 400 `COMMON000` 형식 오류 · 400 `AUTH005` 비밀번호 확인 불일치 · 400 `AUTH309` 링크가 만료되었거나 이미 사용됨

- 순서는 DB·Redis 없이 판단할 수 있는 검사를 먼저 하는 원칙입니다. 그래서 비밀번호 확인이 다르면 링크가 만료됐어도 `AUTH005`가 먼저 나옵니다. (링크는 열 때 AUTH-21이 먼저 확인합니다)

- 성공하면 그 계정의 재설정 링크가 모두 폐기되고 변경 알림 메일("예매자/판매자 계정 비밀번호가 변경되었습니다. 본인이 아니라면 문의해 주세요")이 갑니다.
- 같은 링크로 동시에 요청해도 한 번만 성공합니다.
- 로그인 실패 잠금(`AUTH004`)은 풀지 않습니다. 재설정 자체는 잠금과 무관하게 됩니다.

#### 에러 코드 (제안)

| HTTP | 코드 | 상황 | 화면 처리 |
| --- | --- | --- | --- |
| 404 | `AUTH308` | 요청한 이메일로 가입한 해당 종류의 계정이 없음 | 서버 메시지를 보여 주고 종류·이메일 확인을 안내 |
| 400 | `AUTH309` | 재설정 링크가 만료되었거나 이미 사용됨 | "만료되었거나 이미 사용한 링크입니다. 다시 요청해 주세요" |

#### 화면 참고

- 로그인 화면의 "비밀번호 찾기"는 관리자 탭에서는 숨깁니다. 눌렀을 때 탭 값을 쓰지 않고 예매자/판매자를 다시 고르게 한 뒤 종류에 맞는 요청 API를 부릅니다.
- 요청 200은 **메일이 도착했다는 뜻이 아닙니다.** "메일이 오지 않으면 스팸함을 확인하고 60초 뒤 다시 받아 주세요"를 안내하고, `resendAvailableAt`까지 재요청 버튼을 막습니다.
- `/reset-password`는 `#token=`을 읽어 AUTH-21을 먼저 부릅니다. `valid:false`거나 토큰이 없으면 만료 안내, `true`면 `accountType`에 맞는 제목으로 입력 화면을 보여 줍니다. 읽은 뒤 주소창의 토큰은 지웁니다. AUTH-22가 `AUTH309`여도 같은 만료 안내입니다.
- 같은 이메일로 두 종류 계정이 있으면 메일이 따로 가고, 각 링크는 그 계정만 바꿉니다.

### 담당자 초대·관리 (AUTH-23~28)

> 구현했습니다(2026-11-27). 정책 수치는 임시이고 설정 `app.seller.invitation.*`에 있습니다. 메일 링크의 프론트 주소는 비밀번호 재설정과 같은 `.env`의 `FRONTEND_BASE_URL`입니다.

**왜 필요한가**: 판매자는 회사 1개에 계정이 여러 개입니다. 대표는 판매자 가입 신청으로 생기고, 담당자(STAFF)는 대표가 보낸 **초대 링크로만** 가입합니다. 가입 화면에서 회사를 고르게 하면 어떤 회사가 있는지 드러나서 쓰지 않습니다. 담당자가 퇴사하면 대표가 **비활성화**하고, 다시 들어오면 **복구**하거나 다시 초대합니다. (1부 1-3)

**흐름**: ① 대표가 담당자 이메일 입력(AUTH-23) → 초대 링크 메일 → ② 담당자가 링크를 열면 화면이 먼저 유효 여부 확인(AUTH-24) → ③ 이름·휴대폰·비밀번호 제출(AUTH-25) → 담당자 계정 생성(같은 회사의 비활성 계정이 있으면 그 계정을 다시 사용) → 로그인 화면으로 이동. 대표는 담당자 목록(AUTH-26)에서 비활성화(AUTH-27)·복구(AUTH-28)합니다.

**같은 이메일, 여러 회사**: 한 사람이 회사를 옮길 수 있어서 **같은 이메일로 회사마다 계정이 하나씩** 있을 수 있습니다. 대신 **사용 중(활성) 계정은 이메일당 하나**입니다. 로그인·비밀번호 재설정은 그 이메일의 활성 계정을 씁니다. (활성 계정이 없으면 가장 최근 계정)

```text
A 회사 퇴사 → A 계정 비활성화
B 회사 입사 → B 회사 초대로 B 계정 생성 (A 계정은 비활성으로 남음)
A 회사 재입사 → A 대표가 복구(AUTH-28) 또는 다시 초대
               단, B 계정이 아직 활성이면 409 (이메일당 활성 계정은 하나)
```

**정책** (값은 임시)

| 항목 | 값 |
| --- | --- |
| 링크 유효 | 72시간 |
| 초대·비활성화·복구 | 대표만 (담당자 토큰은 403 `AUTH100`) |
| 목록 | 같은 회사 판매자 누구나 (대표·담당자) |
| 그 이메일의 **활성** 계정이 있음 (어느 회사든) | 초대할 때 409 `SELLER000` |
| 그 이메일의 **같은 회사 비활성** 계정이 있음 | 초대 가능. 가입하면 그 계정을 다시 사용(재활성화)하고 새 비밀번호·이름·휴대폰으로 바꿈 |
| 그 이메일의 **다른 회사 비활성** 계정만 있음 | 초대 가능. 가입하면 이 회사에 새 계정 |
| 같은 회사가 같은 이메일로 다시 초대 | 새 링크를 보내고 **이전 링크는 무효** |
| 가입 이메일 | 초대받은 이메일 그대로 (바꿀 수 없음). 메일을 받은 것이 본인 확인이라 가입 전 이메일 인증(AUTH-17·18)은 하지 않음 |
| 약관 | 받지 않음. 회사의 판매자 약관은 대표가 가입 신청 때 동의했고, 계정별 동의 기록은 판매자 가입에도 없음 |
| 같은 링크로 동시에 가입 | 한 번만 성공 |
| 비활성화 | 대표 본인은 불가. 계정은 지우지 않아서 그 사람이 한 작업 이력이 남음 |
| 비활성화된 계정의 로그인 상태 | **다음 요청부터 401 `AUTH003`**, 세션도 지움 (판매자 요청마다 계정 상태를 확인) |
| 복구 | 예전 비밀번호 그대로. 그 이메일의 활성 계정이 다른 회사에 있으면 409 `SELLER013` (이전 회사에 비활성화를 요청하도록 안내) |
| 초대 요청 횟수 제한 | 두지 않음 (대표만 할 수 있음) |
| 링크 형식 | `{프론트 주소}/seller/invitation#token=…` (토큰이 서버 접근 로그에 남지 않게 `#` 뒤에 둠) |
| 토큰 저장 | 원문은 메일에만 있고 DB(`seller_invitation.token`)에는 SHA-256 해시만 저장 |
| 발송 | 비동기. 200은 접수이고 도착을 보장하지 않음 |
| 가입 뒤 | 자동 로그인 없음 |
| 감사 로그 | 남기지 않음 (5부 5-1 대상 표에 없음) |

#### AUTH-23 담당자 초대 — `POST /api/v1/seller/invitations` (판매자 대표, 구현됨)

**요청**

```json
{ "email": "staff@company.com" }
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 이메일 형식, 최대 255자, 앞뒤 공백 제거, 대소문자 구분 없음 |

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "invitationId": 3,
    "email": "staff@company.com",
    "expiresAt": "2026-11-30T14:00:00"
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `invitationId` | 만든 초대 번호 |
| `email` | 초대한 이메일 (소문자로 맞춘 값) |
| `expiresAt` | 링크 유효 시각 (72시간 뒤) |

**에러** (확인 순서대로): 400 `COMMON000` 형식 오류 · 401 `AUTH200` · 403 `AUTH100` 대표가 아님(담당자·예매자·관리자) · 409 `SELLER000` 그 이메일의 활성 판매자 계정이 있음 · 503 `AUTH304` 발송 대기가 가득 참 (이때 만든 초대는 무효로 돌리므로 바로 다시 요청할 수 있음)

- 사업체 번호와 초대한 사람은 본문으로 받지 않고 로그인 정보에서 꺼냅니다.
- 메일은 텍스트와 HTML 둘 다 담고, 회사 이름·유효 시간·"모르는 초대라면 무시" 안내를 넣습니다. 버튼과 같은 링크를 글자로도 넣습니다.

#### AUTH-24 초대 링크 확인 — `POST /api/v1/auth/sellers/invitations/validate` (공개, 구현됨)

링크를 열었을 때 입력 화면을 보여 주기 전에 유효한지 확인합니다. **토큰을 소모하지 않아서** 메일 보안 프로그램이나 링크 미리보기가 열어도 링크가 죽지 않습니다. 토큰이 주소와 서버 로그에 남지 않도록 `POST` 본문으로 받습니다.

**요청**

```json
{ "token": "..." }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": { "valid": true, "email": "staff@company.com", "companyName": "(주)가상기획" }
}
```

| 필드 | 설명 |
| --- | --- |
| `valid` | 링크가 유효한지. 만료·사용됨·다시 초대해서 무효·없는 토큰은 모두 `false` |
| `email` | 가입할 이메일. 화면이 읽기 전용으로 보여 줌. `valid`가 `false`면 `null` |
| `companyName` | 초대한 회사 이름. `valid`가 `false`면 `null` |

**에러**: 400 `COMMON000` 토큰 누락

- 확인 직후 만료될 수 있어서 이 API는 불편을 줄이는 용도이고, 최종 판단은 AUTH-25가 합니다.

#### AUTH-25 담당자 가입 — `POST /api/v1/auth/sellers/invitations/accept` (공개, 구현됨)

**요청**

```json
{
  "token": "...",
  "password": "password1!",
  "passwordConfirm": "password1!",
  "name": "이담당",
  "phone": "01055556666"
}
```

| 필드 | 규칙 |
| --- | --- |
| `token` | 필수 |
| `password` | 8~25자, 영문·숫자·특수문자 포함 (가입과 같은 규칙) |
| `passwordConfirm` | 필수, `password`와 같아야 함 (다르면 400 `AUTH005`) |
| `name` | 담당자 본인 이름. 최대 50자, 앞뒤 공백 제거 |
| `phone` | 하이픈 없이 숫자만 (가입과 같은 규칙) |

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": { "memberId": 12, "email": "staff@company.com", "companyName": "(주)가상기획", "reactivated": false }
}
```

| 필드 | 설명 |
| --- | --- |
| `memberId` | 담당자 계정 번호 |
| `reactivated` | 같은 회사의 비활성 계정을 다시 사용했으면 `true`, 새로 만들었으면 `false` |

**에러** (확인 순서대로): 400 `COMMON000` 형식 오류 · 400 `AUTH005` 비밀번호 확인 불일치 · 400 `SELLER008` 링크가 없거나 만료·사용됨·무효 · 409 `SELLER000` 그 사이 그 이메일의 활성 판매자 계정이 생김

- 성공하면 초대한 회사의 담당자(`STAFF`, 사용 중) 계정이 되고 링크는 다시 쓸 수 없습니다. 회사는 이미 승인돼 있어서 바로 로그인(AUTH-13)할 수 있습니다.
- `409 SELLER000`이면 링크는 소모되지 않습니다. (계정 생성과 링크 사용이 한 트랜잭션이라 함께 되돌려짐)

#### AUTH-26 담당자 목록 — `GET /api/v1/seller/members` (판매자, 구현됨)

내 회사의 모든 계정(대표 포함, 비활성 포함)입니다. 대표가 먼저, 나머지는 만든 순서입니다. 페이징은 없습니다. 대표·담당자 모두 볼 수 있고, 다른 회사 계정은 나오지 않습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": [
    { "memberId": 7, "email": "owner@company.com", "name": "김대표", "phone": "01099998888", "role": "OWNER", "status": "ACTIVE", "createdAt": "2026-11-20T10:00:00" },
    { "memberId": 12, "email": "staff@company.com", "name": "이담당", "phone": "01055556666", "role": "STAFF", "status": "INACTIVE", "createdAt": "2026-11-27T14:00:00" }
  ]
}
```

**에러**: 401 `AUTH200` · 403 `AUTH100` 대표가 아님

#### AUTH-27 담당자 비활성화 — `POST /api/v1/seller/members/{memberId}/deactivate` (판매자 대표, 구현됨)

Body 없음. 응답 200은 AUTH-26의 항목 한 개(`status: INACTIVE`)입니다.

**에러** (확인 순서대로): 401 `AUTH200` · 403 `AUTH100` 대표가 아님 · 404 `SELLER009` 없는 계정·다른 회사 계정 · 400 `SELLER010` 대표 계정 · 409 `SELLER011` 이미 비활성

- 비활성화된 담당자는 새로 로그인할 수 없고(401 `AUTH003`), 이미 로그인해 있던 세션도 다음 요청에서 401 `AUTH003`이 되며 지워집니다.

#### AUTH-28 담당자 복구 — `POST /api/v1/seller/members/{memberId}/reactivate` (판매자 대표, 구현됨)

Body 없음. 응답 200은 AUTH-26의 항목 한 개(`status: ACTIVE`)입니다. 비밀번호는 예전 그대로입니다. (잊었으면 비밀번호 재설정 AUTH-20)

**에러** (확인 순서대로): 401 `AUTH200` · 403 `AUTH100` 대표가 아님 · 404 `SELLER009` 없는 계정·다른 회사 계정 · 409 `SELLER012` 이미 활성 · 409 `SELLER013` 그 이메일의 활성 계정이 다른 회사에 있음

- `SELLER013`은 그 사람이 다른 회사에서 아직 비활성화되지 않은 경우입니다. 이전 회사에 비활성화를 요청하도록 안내합니다. 이전 회사가 처리해 주지 않는 경우의 별도 수단은 아직 없습니다.
- 동시에 다른 회사에서 같은 이메일 계정이 활성화되면 DB가 막고 409 `SELLER000`이 될 수 있습니다.

#### 에러 코드

| HTTP | 코드 | 상황 | 화면 처리 |
| --- | --- | --- | --- |
| 400 | `SELLER008` | 초대 링크가 없거나 만료·사용됨, 또는 다시 초대해서 무효 | "만료되었거나 이미 사용한 초대 링크입니다. 대표에게 다시 초대를 요청해 주세요" |
| 404 | `SELLER009` | 없는 계정이거나 다른 회사 계정 | 목록 새로고침 |
| 400 | `SELLER010` | 대표 계정을 비활성화하려 함 | 대표 줄에는 버튼을 두지 않음 |
| 409 | `SELLER011` | 이미 비활성 계정 | 목록 새로고침 |
| 409 | `SELLER012` | 이미 활성 계정 | 목록 새로고침 |
| 409 | `SELLER013` | 그 이메일의 활성 계정이 다른 회사에 있어 복구할 수 없음 | 서버 메시지 그대로 (이전 회사에 비활성화 요청 안내) |

#### 화면 참고

- `/seller/invitation`은 `#token=`을 읽어 AUTH-24를 먼저 부릅니다. `valid:false`거나 토큰이 없으면 만료 안내, `true`면 이메일(읽기 전용)과 회사 이름을 보여 주고 이름·휴대폰·비밀번호를 받습니다. 읽은 뒤 주소창의 토큰은 지웁니다.
- 대표의 초대 화면은 AUTH-23의 200을 "메일을 보냈습니다"로 안내하고, 메일이 오지 않으면 다시 초대하라고 안내합니다. (다시 초대하면 이전 링크는 무효)
- 담당자 관리 화면은 AUTH-26 목록에서 `ACTIVE` 담당자에게 "비활성화", `INACTIVE` 담당자에게 "복구" 버튼을 둡니다.

---

## 2. 공연·좌석 조회 (예매자)

### PFM-01 공연 목록·검색 — `GET /api/v1/performances` (공개, 구현됨)

Query: `genre`(`CONCERT/MUSICAL/PLAY/CLASSIC/ETC`), `sort`(`LATEST`), `region`, `keyword`, `page`, `size`

- **응답은 몇 초 전 값일 수 있습니다.** Redis에 잠깐(지금 5초, 임시) 저장해 두고 같은 조건의 요청에 돌려줍니다. 판매자가 공연을 고치거나 판매 상태가 바뀌어도 그 시간이 지나야 반영됩니다. 예매 가능 여부는 점유·결제 단계에서 다시 확인하므로 낡은 화면이 잘못된 예매로 이어지지 않습니다. 검색어(`keyword`)가 있는 요청은 저장하지 않습니다. (설계: `doc/part3-performance-area.md` 3-10)

- 모든 조건은 함께 쓸 수 있고, 비우면 그 조건으로 거르지 않습니다.
- `genre`를 비우면 전체 장르, 주면 그 장르만 나옵니다.
- `region`: 공연장 지역과 **정확히 같은** 공연만 (`서울`, `경기` …). 앞뒤 공백은 지웁니다. 사용 중지된 공연장의 공연도 나옵니다.
- `keyword`: **제목 또는 출연진**에 들어 있으면 나옵니다. (부분 일치, 영문 대소문자 무시, `%`·`_`는 글자 그대로)
- `sort` (기본 `LATEST`). 지금은 `LATEST` 최신 등록순(공연 등록 시각 내림차순)만 있습니다. 등록 시각이 같으면 번호가 큰(나중에 만든) 공연이 먼저 나와서 페이지를 넘겨도 순서가 바뀌지 않습니다. 그 밖의 값은 400 `COMMON000`입니다.
  - 공연 임박순·이름순은 뺐습니다(2026-11-17). 다시 넣을 때 공연 테이블의 값(이름 등)으로 하는 정렬은 기준만 추가하면 되고, 회차 일시로 하는 정렬(임박순)은 목록 쿼리를 바꿔야 합니다.
- `size` 기본 20, 최대 20. 페이지 번호는 0부터.
- `firstStartAt`·`lastStartAt`은 취소된 회차를 뺀 첫·마지막 회차 일시입니다. (회차가 없으면 `null`)
- `venueName`·`region`은 사용 중지된 공연장도 보입니다. (판매자 내 공연 목록과 같음)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "performanceId": 12,
        "title": "가상 콘서트",
        "genre": "CONCERT",
        "posterUrl": "https://cdn.example.com/posters/12.jpg",
        "ageLimit": 12,
        "venueName": "가상 아레나 (샘플)",
        "region": "서울",
        "firstStartAt": "2026-12-20T19:00:00",
        "lastStartAt": "2026-12-21T19:00:00",
        "saleOpenAt": "2026-11-25T20:00:00",
        "saleStatus": "UPCOMING"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`saleStatus`: `PAUSED` / `UPCOMING` / `ON_SALE` / `SOLD_OUT` / `ENDED` (계산값). `PUBLISHED` 공연만 노출.

| `saleStatus` | 조건 (위에서부터 먼저 맞는 것) |
|---|---|
| `ENDED` | 판매 종료 시각이 지남, 또는 마지막 회차가 시작됨, 또는 취소되지 않은 회차가 없음 |
| `PAUSED` | "판매 일시 중지". 아직 시작하지 않은 회차 중 일시 중지(`PAUSED`) 회차가 있고 판매 중(`ON_SALE`) 회차가 하나도 없음. 판매 오픈 전이어도 `PAUSED` |
| `UPCOMING` | 판매 오픈 시각 전 |
| `SOLD_OUT` | 아직 시작하지 않은 판매 중(`ON_SALE`) 회차에 판매 가능(`AVAILABLE`) 좌석이 없음. 임시 점유(`HELD`)만 남아도 매진이고, 점유가 풀리면 다시 `ON_SALE` |
| `ON_SALE` | 그 외 |

| 아직 시작하지 않은 회차 | 목록 |
|---|---|
| 모두 중지 | `PAUSED` |
| 판매 중(좌석 남음) + 중지 | `ON_SALE` |
| 판매 중(매진) + 중지(좌석 남음) | `SOLD_OUT` |
| 모두 중지, 판매 오픈 전 | `PAUSED` |
| 모두 중지, 판매 종료 뒤 | `ENDED` |

종료된 공연도 목록에 남고 `ENDED`로 표시됩니다. 일시 중지·재개(SLR-26·27)는 목록에서는 응답이 저장되는 시간(지금 5초)만큼 늦게 보일 수 있고(상세는 저장분을 지워 바로 반영), 그사이 점유와 결제는 서버가 막습니다.

**에러**: 400 `COMMON000` 없는 `genre`·`sort` 값

### PFM-02 공연 상세 — `GET /api/v1/performances/{performanceId}` (공개, 구현됨)

- 응답은 Redis에 잠깐(지금 5초, 임시) 저장되어 몇 초 전 값일 수 있습니다. (PFM-01과 같음) 404는 저장하지 않습니다. 예외로 판매 일시 중지·재개(SLR-26·27)는 이 공연의 저장분을 지웁니다.
- 공개(`PUBLISHED`)된 공연만 볼 수 있습니다. 작성 중·취소된 공연은 없는 공연과 같은 404입니다.
- `schedules`: 취소된 회차는 빠지고 공연 일시가 빠른 순입니다. (공연 목록의 첫·마지막 회차 일시와 같은 기준)
  - `saleStatus`는 회차마다 PFM-01과 같은 규칙으로 계산합니다. (마지막 회차 자리에 그 회차의 시작 일시, 남은 좌석은 그 회차의 `AVAILABLE`)
  - 시작 전의 일시 중지(`PAUSED`) 회차는 판매 종료가 아니면 `saleStatus`도 `PAUSED`입니다. 연기(`POSTPONED`)는 `saleStatus`에 섞지 않고 `status`로 내려갑니다. 화면은 `status`가 `ON_SALE`이 아니면 그 상태를 먼저 보여 줍니다. `pauseReason`은 `saleStatus`가 `PAUSED`인 회차에만 있고 그 밖은 `null`입니다. 화면은 값이 있을 때만 회차 아래에 사유를 보여 주고, 평소에는 사유 영역을 두지 않습니다. (2026-11-27 변경)
- `grades`: 구역에 지정(`ASSIGNED`)된 등급만, 비싼 순(가격이 같으면 번호순)입니다. 어느 구역에도 붙지 않은 등급은 나오지 않습니다. `name`은 등급 코드(SLR-08의 표)이고 화면 이름과 색은 프론트가 정합니다.
- `cancelPolicy`: 공연이 고른 취소 규정의 구간입니다. 공연에서 먼 구간부터(`daysFrom` 큰 순).
- `commonGuide`의 각 안내는 관리자가 아직 넣지 않았으면 `null`입니다. `venue`·`seller`는 공연의 공연장·사업체를 읽기 전용 연관으로 꺼내므로 항상 있습니다. (행이 없는 깨진 데이터는 `null`이 아니라 500 입니다)
- `seller`에는 사업체 상태·반려 사유·심사자와 판매자 계정 정보가 들어가지 않습니다. (3부 3-8)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "title": "가상 콘서트",
    "genre": "CONCERT",
    "ageLimit": 12,
    "runningTime": 150,
    "castInfo": "가상 밴드",
    "posterUrl": "https://cdn.example.com/posters/12.jpg",
    "images": [
      {
        "url": "https://cdn.example.com/details/12-1.jpg",
        "sortOrder": 1
      }
    ],
    "notice": "공연 시작 30분 전부터 입장 가능합니다.",
    "guide": "촬영·녹음은 금지됩니다.",
    "venue": {
      "venueId": 1,
      "name": "가상 아레나 (샘플)",
      "address": "서울 (가상)",
      "region": "서울"
    },
    "sale": {
      "saleOpenAt": "2026-11-25T20:00:00",
      "saleCloseAt": "2026-12-20T18:00:00",
      "maxPerPerson": 4,
      "receiveType": "BOTH",
      "bookingFee": 2000
    },
    "schedules": [
      {
        "scheduleId": 31,
        "startAt": "2026-12-20T19:00:00",
        "status": "ON_SALE",
        "saleStatus": "UPCOMING",
        "pauseReason": null
      }
    ],
    "grades": [
      {
        "gradeId": 20,
        "name": "VIP",
        "price": 165000
      }
    ],
    "cancelPolicy": [
      {
        "daysFrom": 7,
        "daysTo": null,
        "feeRate": 0,
        "cancelable": true
      },
      {
        "daysFrom": 3,
        "daysTo": 6,
        "feeRate": 10,
        "cancelable": true
      },
      {
        "daysFrom": 1,
        "daysTo": 2,
        "feeRate": 20,
        "cancelable": true
      },
      {
        "daysFrom": 0,
        "daysTo": 0,
        "feeRate": 0,
        "cancelable": false
      }
    ],
    "commonGuide": {
      "bookingGuide": "좌석 선택 후 7분 안에 결제해야 합니다.",
      "refundGuide": "취소 수수료는 공연일까지 남은 기간에 따라 달라집니다."
    },
    "seller": {
      "companyName": "(주)가상기획",
      "representativeName": "김대표",
      "businessNumber": "1234567890",
      "contactPhone": "01012345678",
      "contactEmail": "contact@company.com"
    }
  }
}
```

**에러**: 404 `PERFORMANCE000` 공연 없음 또는 `PUBLISHED`가 아님 · 400 `COMMON000` 공연 번호가 숫자가 아님

### PFM-03 구역 요약 — `GET /api/v1/schedules/{scheduleId}/zones` (공개, 구현됨)

- 응답은 Redis에 잠깐(지금 2초, 임시) 저장되어 `remaining`이 몇 초 전 값일 수 있습니다. 잔여 좌석을 정확히 보려면 PFM-04(저장하지 않음)를 봅니다. 점유는 이 숫자가 아니라 DB의 좌석 상태로 결정합니다.
- 공개(`PUBLISHED`)된 공연의 **취소되지 않은 회차**만 볼 수 있습니다. 공개되지 않은 공연의 회차, 취소된 회차, 없는 회차는 모두 같은 404입니다. (공연 상세의 회차 목록과 같은 기준) 일시 중지(`PAUSED`)·연기(`POSTPONED`) 회차는 볼 수 있습니다.
- `zones`: 공연장의 모든 구역이 화면 순서(`sort_order`, 같으면 구역 번호순)로 나옵니다.
- `remaining`: 지금 살 수 있는(`AVAILABLE`) 좌석 수입니다. 임시 점유(`HELD`)·판매 완료(`SOLD`)·판매자 보류(`RESERVED`)·판매 제외(`BLOCKED`)는 빠집니다.
- `total`: 공연장 구역의 전체 좌석 수입니다. (판매 좌석은 공연장 좌석을 그대로 복사해서 만들어서 같은 값)
- **판매 좌석을 아직 만들지 않은 회차**(판매 오픈 24시간 전보다 이전)는 구역·등급·가격과 `total`은 보이고 `remaining`은 모두 0입니다. 아직 살 수 있는 좌석이 없기 때문입니다.
- `layoutKey`: 공연장의 도면 이름표입니다. 비어 있으면 `null`이고 프론트는 기본 격자 도면을 그립니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "scheduleId": 31,
    "layoutKey": "sample-arena",
    "zones": [
      {
        "zoneId": 12,
        "name": "F2구역",
        "floor": "1층",
        "grade": "R",
        "price": 154000,
        "remaining": 38,
        "total": 220
      }
    ]
  }
}
```

`grade`는 등급 코드(SLR-08의 표)이고 화면 이름과 색은 프론트가 정합니다. `INTENTIONALLY_UNSOLD` 구역은 `grade`·`price`가 `null`이고 판매 제외입니다. 공개 공연에는 `UNSET` 구역이 없습니다. (혹시 있으면 판매하지 않는 구역과 같이 `null`)

**에러**: 404 `PERFORMANCE200` 볼 수 없는 회차(없음, 공개되지 않은 공연, 취소된 회차) · 400 `COMMON000` 회차 번호가 숫자가 아님

### PFM-04 구역 좌석 상세 — `GET /api/v1/schedules/{scheduleId}/zones/{zoneId}/seats` (공개, 구현됨)

- 좌석 상태는 **저장하지 않고 항상 DB에서 읽습니다.** (점유·판매가 바로 보여야 하므로 PFM-01~03과 달리 캐시 대상이 아닙니다)
- 볼 수 있는 회차는 PFM-03과 같습니다. (공개 공연의 취소되지 않은 회차, 일시 중지·연기 회차 포함)
- 구역은 **그 회차 공연장의 구역**이어야 합니다. 다른 공연장의 구역이나 없는 구역은 404입니다.
- **판매 좌석을 아직 만들지 않은 회차**는 고를 좌석이 없어서 404입니다. (PFM-03은 같은 회차에서 잔여석 0으로 보여 줍니다)
- `seats`: 그 구역의 모든 좌석이 상태와 상관없이 구역 안의 표시 순서(`sortOrder`, 같으면 번호순)로 나옵니다. 열·번호는 문자열이라 이름으로 정렬하지 않습니다.
- `seatId`는 **판매 좌석(`schedule_seat`) 번호**이고, 좌석 점유(BKG-01)의 `seatIds`에 그대로 보냅니다. `AVAILABLE`만 고를 수 있습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "zoneId": 12,
    "name": "F2구역",
    "seats": [
      {
        "seatId": 9301,
        "row": "9열",
        "number": "6",
        "sortOrder": 186,
        "status": "AVAILABLE"
      },
      {
        "seatId": 9303,
        "row": "9열",
        "number": "8",
        "sortOrder": 188,
        "status": "HELD"
      }
    ]
  }
}
```

`status`: `AVAILABLE` / `HELD` / `SOLD` / `BLOCKED` / `RESERVED`.

**에러**: 404 `PERFORMANCE200` 볼 수 없는 회차 · 404 `PERFORMANCE201` 그 회차 공연장의 구역이 아님(없는 구역 포함) · 404 `PERFORMANCE202` 판매 좌석을 아직 만들지 않은 회차 · 400 `COMMON000` 번호가 숫자가 아님

---

## 3. 예매·결제·취소 (예매자)

### BKG-01 좌석 점유·예매 생성 — `POST /api/v1/schedules/{scheduleId}/bookings` (예매자)

좌석 점유(7분)와 `PENDING` 예매 생성은 한 트랜잭션.

- `gradeName`: 예매할 때의 등급 코드(SLR-08의 표)를 그대로 저장한 값입니다. 화면 이름은 프론트가 정합니다.

**요청**

```json
{ "seatIds": [9301, 9302] }
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "bookingId": 1001,
    "bookingNo": "20261125KCZB03R",
    "status": "PENDING",
    "expiresAt": "2026-11-25T20:10:00",
    "seats": [
      {
        "bookingSeatId": 5001,
        "zoneName": "F2구역",
        "rowLabel": "9열",
        "seatNumber": "6",
        "gradeName": "R",
        "price": 154000,
        "bookingFee": 2000
      },
      {
        "bookingSeatId": 5002,
        "zoneName": "F2구역",
        "rowLabel": "9열",
        "seatNumber": "7",
        "gradeName": "R",
        "price": 154000,
        "bookingFee": 2000
      }
    ],
    "ticketAmount": 308000,
    "bookingFeeAmount": 4000,
    "totalAmount": 312000
  }
}
```

**에러**

| HTTP | 코드 | 의미 |
|---|---|---|
| 409 | `BOOKING000` | 이미 점유·판매된 좌석 (하나라도 점유할 수 없으면 전체 거절) |
| 409 | `BOOKING001` | 1인 최대 매수 초과 (이미 가진 좌석 + 요청 좌석이 공연의 한도를 넘음) |
| 400 | `BOOKING002` | 판매 중이 아닌 회차 (판매 기간 밖·연기·취소·이미 시작. 공연이 공개 상태가 아니면 `BOOKING004`) |
| 409 | `BOOKING012` | 일시 중지한 회차 (아직 시작하지 않았고 판매 종료 전) |
| 400 | `BOOKING003` | 판매 제외 좌석 (`BLOCKED`, `RESERVED`) |
| 404 | `BOOKING004` | 없는 회차 (공연이 공개 상태가 아닌 것도 같은 404) |
| 404 | `BOOKING005` | 없는 좌석 (다른 회차의 좌석도 같은 404) |
| 400 | `COMMON000` | 좌석 번호가 비었거나 중복 |
| 409 | `COMMON004` | 락을 기다리는 시간(1초) 안에 못 잡음 |

락을 기다리는 시간과 만료 처리 규칙은 `part4` 4-7을 따른다. 만료된 좌석은 스케줄러(1분)가 풀어 줄 때까지 `BOOKING000`이다.

`seats`는 요청한 `seatIds` 순서대로 내려간다. 락 대기 시간(`app.booking.lock-wait`, 1초)과 결제 제한 시간(`app.booking.hold-duration`, 7분)은 `application.yaml`의 설정값이다. 예매자 토큰만 호출할 수 있고(판매자·관리자 403), 예매자 번호는 토큰에서 꺼낸다.

**판매 일시 중지 (2026-11-27)**: 아직 시작하지 않았고 판매 종료 전인 회차가 일시 중지면 409 `BOOKING012` "판매가 일시 중지된 회차입니다."입니다. 대기열 입장(`doc/queue-design.md`)도 같습니다. 판매가 끝났거나 시작한 회차는 `BOOKING002`입니다. 화면은 `BOOKING012`를 받으면 알림 창을 띄우고 확인하면 공연 상세로 보냅니다.

### BKG-02 결제 준비 — `POST /api/v1/bookings/{bookingId}/payments` (예매자)

`receiveType`이 `DELIVERY`면 받는 사람 정보 필수.

**요청**

```json
{
  "receiveType": "DELIVERY",
  "recipientName": "홍길동",
  "recipientPhone": "01012345678",
  "recipientAddress": "서울시 마포구 월드컵북로 1길 1"
}
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "paymentId": 801,
    "orderId": "BK20261125KCZB03R-01",
    "orderName": "가상 콘서트 2매",
    "amount": 312000,
    "status": "READY",
    "expiresAt": "2026-11-25T20:10:00"
  }
}
```

**에러**

| HTTP | 코드 | 의미 |
|---|---|---|
| 404 | `BOOKING006` | 없는 예매 (남의 예매도 같은 404) |
| 409 | `BOOKING007` | 결제 대기(`PENDING`)가 아니거나 결제 제한 시각이 지남. 공연이 공개 상태가 아니어도 같은 코드 |
| 409 | `BOOKING012` | 예매의 회차가 일시 중지 |
| 400 | `PAYMENT000` | 공연이 받지 않는 수령 방법 (현장 전용 공연의 `DELIVERY`, 배송 전용 공연의 `ONSITE`) |
| 400 | `PAYMENT001` | `DELIVERY`인데 받는 사람 이름·연락처·주소 중 비어 있음 |
| 400 | `COMMON000` | `receiveType`이 비었거나 알 수 없는 값 |
| 409 | `COMMON004` | 같은 예매의 다른 결제 요청이 처리 중이라 락을 기다리는 시간(1초) 안에 못 잡음 |

- 이미 결제 준비(`READY`) 건이 있으면 새로 만들지 않고 **같은 `paymentId`·`orderId`를 돌려줍니다.** (새로고침, 결제창 재오픈) 수령 방법과 배송 정보는 요청한 값으로 다시 확정합니다. 이전 시도가 `FAILED`일 때만 일련번호를 올려(`-02`) 새 주문 ID를 만듭니다.
- `ONSITE`이면 받는 사람 정보는 무시하고 비웁니다.
- 이 단계에서는 좌석 점유 시간을 늘리지 않습니다. (연장은 BKG-03 승인을 시작할 때 한 번)
- 예매자 번호는 토큰에서 꺼냅니다. 락 대기 시간은 `app.payment.lock-wait`(1초) 설정값입니다.

**판매 일시 중지 (2026-11-27)**: 예매의 회차가 일시 중지(`PAUSED`)면 409 `BOOKING012`, 메시지 "판매가 일시 중지된 회차라 결제할 수 없습니다."입니다. 결제 준비 건을 만들지 않고 수령 정보도 바꾸지 않습니다. 검사 순서는 예매 404 → 예매 상태·결제 제한 시각 → **회차 중지** → 공연 공개 → 수령 방법입니다. 점유한 좌석은 만료까지 유지되고, 그 안에 재개되면 다시 결제할 수 있습니다.

### BKG-03 결제 승인 — `POST /api/v1/payments/confirm` (예매자)

검증 → 토스 승인(트랜잭션 밖) → 결과 반영 3단계.

**요청**

```json
{ "paymentKey": "tgen_20261125200512AbCdEf", "orderId": "BK20261125KCZB03R-01", "amount": 312000 }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "bookingId": 1001,
    "bookingNo": "20261125KCZB03R",
    "bookingStatus": "PAID",
    "paymentId": 801,
    "paymentStatus": "DONE",
    "method": "카드",
    "amount": 312000,
    "approvedAt": "2026-11-25T20:05:12"
  }
}
```

**에러**

| HTTP | 코드 | 의미 |
|---|---|---|
| 404 | `PAYMENT002` | 없는 주문 ID (남의 결제도 같은 404) |
| 400 | `PAYMENT004` | 요청 금액이 서버가 계산한 금액과 다름. 토스를 부르지 않고 결제는 준비(`READY`)로 남음 |
| 409 | `BOOKING007` | 결제 제한 시각이 지났거나 예매가 결제 대기가 아님. 토스를 부르지 않고 결제는 준비로 남음 |
| 409 | `BOOKING012` | 예매의 회차가 일시 중지. 토스를 부르지 않고 결제는 준비로 남음 |
| 409 | `PAYMENT003` | 결제가 준비(`READY`) 상태가 아님. 이미 승인했거나 다른 승인 요청이 처리 중이거나 실패한 결제 |
| 400 | `PAYMENT005` | 토스가 승인을 거절함. `message`에 토스가 준 사유(카드사 거절, 한도 초과 등)가 들어감. 결제는 `FAILED`, 예매·좌석은 점유 그대로 |
| 502 | `PAYMENT006` | 승인 여부를 알 수 없음(통신 오류·시간 초과 등). 결제는 `PROCESSING`으로 남고 보정 작업이 토스에 조회해 확정함(구현됨, 2026-11-25: 승인을 시작한 지 5분이 지난 결제를 5분마다 조회). 확정되면 예매가 `PAID`가 되고, 그 사이 예매가 만료돼 좌석을 줄 수 없으면 자동 환불되어 `CANCELED`가 됨. 같은 결제를 다시 승인하면 `PAYMENT003` |
| 400 | `COMMON000` | `paymentKey`·`orderId` 비어 있음, `amount` 비어 있거나 0 이하 |
| 409 | `COMMON004` | 같은 예매의 다른 결제 요청이 처리 중이라 락을 기다리는 시간(1초) 안에 못 잡음 |

- **금액은 요청 값을 믿지 않습니다.** 저장한 금액과 비교만 하고 토스에도 저장한 금액을 보냅니다.
- 승인을 시작할 때(처음 시도일 때만) 남은 점유 시간이 `app.payment.confirm-extension`(2분)보다 짧으면 예매 결제 제한 시각과 좌석 점유 시각을 지금 + 2분으로 함께 늘립니다. 토스를 부르는 동안 스케줄러가 좌석을 풀지 않게 하려는 것입니다. 이미 승인을 시도한 결제가 있는 예매는 늘리지 않습니다.
- 토스 승인 요청에는 주문 ID를 멱등키(`Idempotency-Key`)로 보냅니다.
- 토스를 부르는 구간은 DB 트랜잭션과 락 밖입니다. 성공하면 결제 `DONE`, 예매 `PAID`, 좌석 `SOLD`를 한 트랜잭션으로 바꿉니다.
- **결제 대행사 구현**은 `app.payment.gateway`로 고릅니다. `toss`(운영 기본)는 `.env`의 `TOSS_SECRET_KEY`가 필요하고, `mock`은 키 없이 흐름만 확인합니다(`prod` 프로필에서는 등록되지 않음). **로컬에서는 `.env`에 `PAYMENT_GATEWAY`를 반드시 넣어야 앱이 뜹니다.** 키 없이 돌리려면 `PAYMENT_GATEWAY=mock`, 실제 토스 테스트 결제를 쓰려면 `PAYMENT_GATEWAY=toss`와 `TOSS_SECRET_KEY`를 넣습니다. 결제창을 여는 클라이언트 키는 프론트가 읽습니다.
- Mock 은 결제 키 접두사로 결과를 고릅니다. `reject_` 거절, `unavailable_` 결과 모름, `slow_` 느린 성공, 그 밖의 값은 성공입니다.

**판매 일시 중지 (2026-11-27)**: 승인을 시작할 때 예매의 회차가 일시 중지면 409 `BOOKING012`, 메시지는 BKG-02와 같습니다. 토스를 부르지 않고 결제는 `READY`로 남으며 점유 시간을 늘리지 않습니다.

- 검사 순서: 결제·예매 404 → 결제 상태(`PAYMENT003`) → 금액(`PAYMENT004`) → 예매 상태·결제 제한 시각 → **회차 중지**
- 중지 **전에** 승인을 시작한 결제(`PROCESSING`)는 완료됩니다. 응답을 잃은 결제는 보정 작업이 나중에 확정합니다.
- 승인 시작과 중지가 밀리초 단위로 겹치면 통과할 수 있습니다. (알고 감수, `seller-progress.md` DB 관련 TODO)

### BKG-04 내 예매 목록 — `GET /api/v1/bookings` (예매자, 구현됨)

Query: `title`, `status`(`PENDING/PAID/PARTIAL_CANCELED/CANCELED`, 여러 개), `from`, `to`, `page`, `size` — 검색 조건은 SLR-23·ADM-08과 같습니다. (아래 "예매 목록 검색 조건")

**예매 목록 검색 조건 (BKG-04·SLR-23·ADM-08 공통)**

| Query | 설명 |
|---|---|
| `title` | 공연 제목. 일부만 넣어도 찾고 대소문자는 가리지 않습니다. 넣은 `%`·`_`는 글자로 찾습니다. 100자 이하 |
| `status` | 예매 상태. 여러 개를 고를 수 있습니다(`status=PAID&status=CANCELED`). 비우면 역할별 기본 상태(예매자: 만료 제외 전부, 판매자: `PAID`·`PARTIAL_CANCELED`, 관리자: 전부) |
| `from`, `to` | **예매한 날짜** 구간(`2026-11-01`, 둘 다 포함). 하나만 줘도 됩니다. `from`이 `to`보다 늦으면 400 `COMMON000` |

- 정렬은 최신 예매순 하나로 고정입니다. 정렬마다 인덱스가 필요해서 고르게 하지 않았습니다.
- 누구의 예매까지 보이는지는 토큰으로만 정합니다. 요청으로 회원·사업체 번호를 받지 않습니다. 세 API는 주소와 응답 모양만 다르고 찾는 코드(`BookingQueryService`)를 함께 씁니다.

- 로그인한 예매자 본인의 예매만 나옵니다. 회원 번호는 토큰에서 꺼냅니다. 판매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 시간 초과(`EXPIRED`)는 나오지 않고, `status`로 보내면 400입니다. 돈이 나가지 않은 무효 예매라 내역에서 뺍니다. 화면에서 거르면 한 페이지 개수와 전체 개수가 어긋나서 서버가 조회할 때 거릅니다.
- 결제 대기(`PENDING`)는 나옵니다. 화면이 목록에서 결제 화면으로 이어 가서, 7분 안에는 결제를 마저 할 수 있어야 하기 때문입니다. `status`를 비우면 `PENDING`, `PAID`, `PARTIAL_CANCELED`, `CANCELED`가 모두 나옵니다.
- 최신 예매순(`createdAt` 내림차순)이고, 같은 시각이면 번호가 큰 예매가 먼저라 페이지를 넘겨도 순서가 바뀌지 않습니다. 정렬은 고정이라 `sort`를 보내도 무시합니다.
- `size` 기본 20, 최대 20. 크기가 20을 넘으면 에러가 아니라 20으로 줄어듭니다.
- `seatCount`와 `totalAmount`는 **처음 예매 기준**입니다. 일부 취소해도 취소된 좌석까지 세고, 금액도 처음 결제 금액 그대로입니다. (둘의 기준을 맞추려는 것. 남은 좌석과 환불 금액은 상세 BKG-05에서 봅니다)
- `performanceTitle`·`posterUrl`·`scheduleStartAt`은 예매 때 복사한 값이 아니라 지금 값입니다. 회차가 연기되면 바뀐 일시가 보이고, 예매한 뒤 공연이 취소돼도 내역에 남습니다. 공연·회차는 예매의 읽기 전용 연관에서 꺼내므로 항상 있습니다. (행이 없는 깨진 데이터는 `null`이 아니라 500 입니다)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "bookingId": 1001,
        "bookingNo": "20261125KCZB03R",
        "status": "PAID",
        "performanceTitle": "가상 콘서트",
        "posterUrl": "https://cdn.example.com/posters/12.jpg",
        "scheduleStartAt": "2026-12-20T19:00:00",
        "seatCount": 2,
        "totalAmount": 312000,
        "createdAt": "2026-11-25T20:03:41"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 400 `COMMON000` 없는 `status` 값, `EXPIRED`, `from`이 `to`보다 늦음

### BKG-05 내 예매 상세 — `GET /api/v1/bookings/{bookingId}` (예매자, 구현됨)

- 로그인한 예매자 본인의 예매만 봅니다. 남의 예매·없는 예매는 404입니다. (번호가 있는지 알려주지 않으려고 같은 응답)
- 목록과 달리 **상태와 상관없이** 줍니다. 결제 화면이 결제 대기(`PENDING`) 예매를 이 API로 읽고, 7분이 지나면 `EXPIRED`로 받아 만료됐다고 보여 줍니다.
- `expiresAt`은 결제 대기(`PENDING`)일 때만 있고 나머지는 `null`입니다.
- `payment`는 승인된 결제(`DONE`, 환불되면 `PARTIAL_CANCELED`·`CANCELED`) 중 가장 최근 것입니다. 결제 대기·만료된 예매는 `null`입니다. 실패한 결제 시도는 보여 주지 않습니다.
- `seats`는 취소된 좌석까지 모두 나오고(`active=false`), 구역·열·번호·등급·가격은 예매할 때 복사한 값입니다.
- `cancelPolicy`는 예매할 때 복사한 취소 규정입니다. 판매자가 나중에 규정을 바꿔도 예매 당시 규정이 나옵니다. 공연에서 먼 구간부터(`daysFrom` 큰 순).
- `performance`·`schedule`은 지금 값이고 항상 있습니다. (예매의 읽기 전용 연관에서 꺼냅니다. 행이 없는 깨진 데이터는 `null`이 아니라 500 입니다)
- `delivery`는 배송(`DELIVERY`)이 아니면 `null`입니다.
- `refundedAmount`는 **실제로 돌려준 금액**입니다. 완료(`DONE`)된 환불만 더하고, 요청 중·실패한 환불은 빠집니다. 취소한 적이 없으면 0입니다.
- `cancelable`, `currentFeeRate`는 **예매 취소(BKG-06)와 같은 계산**으로 정합니다. 화면에 보인 수수료율과 실제 환불이 어긋나지 않게 한 클래스(`CancelPolicyCalculator`)에서만 계산합니다. 기준은 BKG-06의 "취소 가능 여부와 수수료율"을 봅니다.
  - `cancelable`: 지금 취소할 수 있으면 `true`. 결제 완료·일부 취소가 아니거나 취소 불가 기간이면 `false`입니다.
  - `currentFeeRate`: 지금 취소하면 적용되는 수수료율(%). 취소할 수 없으면 `null`입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "bookingId": 1001,
    "bookingNo": "20261125KCZB03R",
    "status": "PAID",
    "performance": {
      "performanceId": 12,
      "title": "가상 콘서트",
      "posterUrl": "https://cdn.example.com/posters/12.jpg"
    },
    "schedule": {
      "scheduleId": 31,
      "startAt": "2026-12-20T19:00:00",
      "status": "ON_SALE"
    },
    "receiveType": "DELIVERY",
    "orderer": {
      "name": "홍길동",
      "phone": "01012345678",
      "email": "user@example.com"
    },
    "delivery": {
      "recipientName": "홍길동",
      "recipientPhone": "01012345678",
      "recipientAddress": "서울시 마포구 월드컵북로 1길 1",
      "carrier": null,
      "trackingNo": null
    },
    "seats": [
      {
        "bookingSeatId": 5001,
        "zoneName": "F2구역",
        "rowLabel": "9열",
        "seatNumber": "6",
        "gradeName": "R",
        "price": 154000,
        "bookingFee": 2000,
        "active": true,
        "canceledAt": null,
        "cancelFee": null
      }
    ],
    "ticketAmount": 308000,
    "bookingFeeAmount": 4000,
    "totalAmount": 312000,
    "refundedAmount": 0,
    "payment": {
      "paymentId": 801,
      "method": "카드",
      "amount": 312000,
      "status": "DONE",
      "approvedAt": "2026-11-25T20:05:12"
    },
    "cancelPolicy": [
      {
        "daysFrom": 7,
        "daysTo": null,
        "feeRate": 0,
        "cancelable": true
      }
    ],
    "cancelable": true,
    "currentFeeRate": 0,
    "expiresAt": null,
    "createdAt": "2026-11-25T20:03:41",
    "canceledAt": null
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 404 `BOOKING006` 예매 없음(남의 예매 포함)

### BKG-06 예매 취소 — `POST /api/v1/bookings/{bookingId}/cancel` (예매자, 구현됨)

결제 완료된 예매의 좌석을 취소하고 토스로 환불합니다. `bookingSeatIds`를 비우면(생략, 빈 목록, 본문 없음) **남은 좌석 전부**를 취소합니다.

**요청** (부분 취소)

```json
{ "bookingSeatIds": [5002] }
```

**요청** (전체 취소)

```json
{}
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "bookingId": 1001,
    "bookingStatus": "PARTIAL_CANCELED",
    "canceledSeatIds": [5002],
    "cancelFeeTotal": 15400,
    "refund": {
      "refundId": 301,
      "amount": 140600,
      "status": "DONE"
    }
  }
}
```

**취소 가능 여부와 수수료율** (2026-11-24 확정, 금액·수수료 환불은 임시 정책)

아래 순서로 판정합니다. BKG-05의 `cancelable`, `currentFeeRate`도 같은 계산입니다.

1. 결제 완료(`PAID`)·일부 취소(`PARTIAL_CANCELED`)가 아니면 취소할 수 없습니다. (409 `BOOKING008`)
2. **연기 무료 취소** 대상이면 수수료 0%로 취소할 수 있습니다. 공연 당일이거나 새 일정이 미정이어도 됩니다. 대상은 가장 최근 연기 이력의 `free_cancel_until`이 지나지 않았고, 예매가 그 연기보다 먼저 생긴 경우입니다. (3부)
3. 회차가 이미 시작했으면 취소할 수 없습니다. 당일 취소를 허용한 규정이어도 시작한 뒤에는 막습니다. (400 `BOOKING009`)
4. 예매 때 복사한 취소 규정에서 **남은 일수**가 들어가는 구간을 찾습니다. `cancelable=false` 구간(기본 규정의 당일)이거나 맞는 구간이 없으면 400 `BOOKING009`입니다.

- **남은 일수는 달력 날짜 차이로 셉니다.** 기준은 한국 시간이고, 회차가 연기되면 바뀐 시작 일시로 셉니다.
  - 예: 공연 11/30 19:00, 지금 11/27 23:00 → 날짜로 3일 전 → 기본 규정 10%. (시간으로 세면 68시간이라 2일 전·20%가 되지만 쓰지 않습니다)
  - 이유: 규정 구간이 "공연 3·4·5·6일 전"처럼 날짜로 적혀 있어서 사용자가 읽는 대로 계산되게 했습니다.

**금액**

```
좌석별 취소 수수료 = 좌석 가격 × 수수료율(%)      (원 단위 절사)
환불 금액 = Σ(취소한 좌석 가격 − 취소 수수료) + Σ(취소한 좌석의 예매 수수료)
```

- 예매 수수료는 **취소한 좌석 몫만** 돌려줍니다. 예매 수수료가 좌석 1장당 붙기 때문입니다. (임시 정책)
- 예: 154,000원 좌석 1장을 10% 구간에 취소 → 수수료 15,400원, 환불 154,000 − 15,400 + 2,000 = 140,600원
- 환불은 취소 요청마다 `refund` 1행이 생깁니다. 부분 취소를 두 번 하면 2행입니다.

**토스 환불이 실패하면** (2026-11-24 변경: 처음 명세는 502)

- 좌석 취소는 끝나고 **200**으로 답하며 `refund.status`가 `FAILED`입니다. 실패 사유는 `refund.fail_reason`에 남습니다.
- 이유: 좌석 취소를 먼저 커밋하고 토스를 부르기 때문에, 실패로 답하면 실제로는 취소된 예매를 사용자가 다시 취소하려다 "이미 취소된 좌석"(`BOOKING010`)을 보게 됩니다.
- 실패한 환불은 같은 멱등키로 다시 요청하면 이중 환불 없이 마무리됩니다. 재시도 스케줄러가 마지막 시도 후 5분이 지난 실패 환불을 3회까지 다시 보냅니다. 관리자 재처리는 아직 없습니다. (4부 4-5, 5부)
- 결과 기록까지 실패하면 `PROCESSING`으로 답하고, 환불은 요청 중으로 남습니다.

**처리 순서**

1. 예매 단위 락(`booking:{id}`, 결제 승인과 같은 락) → 트랜잭션 시작
2. 위 판정과 금액 계산 → `refund` 1행(`PROCESSING`, 새 멱등키) → `payment` 일부·전액 환불 상태 → `booking_seat` 취소(`active=false`, `canceled_at`, `cancel_fee`, `refund_id`) → `schedule_seat` `SOLD` → `AVAILABLE` → `booking` 일부·전체 취소 → 커밋, 락 해제
3. 트랜잭션 밖에서 토스 결제 취소 API 호출 (`Idempotency-Key` = 환불 행의 멱등키)
4. `refund`를 `DONE`(완료 일시) 또는 `FAILED`(사유)로 기록

- 같은 좌석 취소가 동시에 와도 락이 하나씩 처리하고, 락이 뚫려도 좌석 취소 UPDATE의 `active` 조건이 한 번만 통과시켜 이중 환불이 생기지 않습니다.
- 돌려줄 금액이 0원이면(수수료 100% 규정) 토스를 부르지 않고 바로 `DONE`입니다.

**에러** (판정 순서)

| HTTP | 코드 | 언제 |
|---|---|---|
| 401 | `AUTH200` | 토큰 없음 |
| 403 | `AUTH100` | 예매자가 아님 |
| 400 | `COMMON000` | 좌석 번호 목록에 빈 값 |
| 404 | `BOOKING006` | 없는 예매, 남의 예매 |
| 409 | `BOOKING008` | 결제 완료·일부 취소가 아님 (결제 대기, 만료, 이미 전체 취소) |
| 400 | `BOOKING009` | 취소 불가 기간 (당일 등 `cancelable=false` 구간, 회차 시작 후) |
| 400 | `BOOKING010` | 이미 취소된 좌석이거나 이 예매의 좌석이 아님 (하나라도 있으면 전체 거절) |
| 409 | `COMMON004` | 같은 예매의 다른 요청(취소·결제)이 처리 중이라 락을 1초 안에 잡지 못함 |

---

## 4. 판매자 (`/api/v1/seller/**`, 자기 사업체 리소스만 — 남의 것은 404)

### 판매자 감사 로그 (공통)

SLR-03·04·09·10·11·12·13은 변경할 때 `audit_log`에 한 줄을 남깁니다. 행위자는 `SELLER`, `actor_id`는 계정 번호, `business_id`는 사업체 번호입니다. 변경과 **한 트랜잭션**이라 로그를 남기지 못하면 변경도 취소됩니다(500). 거절된 요청과 **값이 그대로인 요청은 남기지 않습니다.** 대상·행동·전후 값은 각 API에 적습니다. 판매 일시 중지·재개(SLR-26·27)와 취소 규정 생성·수정(SLR-21·22)도 남깁니다.

### SLR-01 공연장 목록 — `GET /api/v1/seller/venues` (구현됨)

Query: `keyword`, `region`, `page`, `size`

- 사용 중(`ACTIVE`)인 공연장만 줍니다. 사용 중지된 공연장은 빠집니다.
- `region`은 정확히 같은 지역만 찾고, 비우면 전체 지역입니다. `keyword`는 공연장 이름에서 부분 일치로 찾으며(영문 대소문자 무시) 주소는 찾지 않습니다.
- 정렬은 이름순이고 이름이 같으면 번호순입니다. 정렬은 고정이라 `sort`를 보내도 무시합니다.
- 기본 크기와 최대 크기는 20입니다. 크기가 20을 넘으면 에러가 아니라 20으로 줄어들고, 크기가 0이거나 음수면 기본값(20)으로 처리됩니다.
- 판매자(대표·담당자) 토큰만 쓸 수 있습니다. 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "venueId": 1,
        "name": "가상 아레나 (샘플)",
        "address": "서울 (가상)",
        "region": "서울",
        "totalSeats": 3022,
        "layoutKey": "sample-arena"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### SLR-02 공연 등록 — `POST /api/v1/seller/performances` (구현됨)

`DRAFT`로 생성.

DRAFT에서는 판매 필드(`saleOpenAt`, `saleCloseAt`, `maxPerPerson`, `receiveType`, `bookingFee`)를 비워 둘 수 있습니다. 공연 등록 시 취소 규정은 기본 규정 또는 로그인한 사업체 소유 규정 중 하나를 선택합니다. 공연장에 속한 모든 구역은 `UNSET` 상태로 초기화합니다. PUBLISHED 전환 전에 판매 필드가 모두 설정되어 있고 `UNSET` 구역이 없는지 검증합니다.

**요청**

```json
{
  "venueId": 1,
  "cancelPolicyId": 1,
  "title": "가상 콘서트",
  "genre": "CONCERT",
  "ageLimit": 12,
  "runningTime": 150,
  "castInfo": "가상 밴드",
  "posterUrl": "https://cdn.example.com/posters/12.jpg",
  "notice": "공연 시작 30분 전부터 입장 가능합니다.",
  "guide": "촬영·녹음은 금지됩니다.",
  "saleOpenAt": null,
  "saleCloseAt": null,
  "maxPerPerson": null,
  "receiveType": null,
  "bookingFee": null,
  "images": [
    {
      "url": "https://cdn.example.com/details/12-1.jpg",
      "sortOrder": 1
    }
  ]
}
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "status": "DRAFT",
    "venueId": 1,
    "createdAt": "2026-11-13T10:20:00"
  }
}
```

- 사업체 번호와 등록자는 본문으로 받지 않고 토큰에서 꺼냅니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 구역이 없는 공연장도 등록은 됩니다. (공개할 때 `ASSIGNED` 구역 1개 이상 조건에서 막힙니다)
- 문자열은 앞뒤 공백을 지우고 저장합니다. 선택 입력(`castInfo`, `notice`, `guide`)이 공백뿐이면 비운(`null`) 것으로 저장합니다.

**입력 규칙**

| 필드 | 규칙 |
| --- | --- |
| `venueId`, `cancelPolicyId` | 필수 |
| `title` | 필수, 공백만 불가, 200자 이하. 같은 이름의 공연을 여러 개 만들 수 있음 |
| `genre` | 필수, `CONCERT` / `MUSICAL` / `PLAY` / `CLASSIC` / `ETC` (대문자 그대로) |
| `ageLimit` | 필수, 0~100 |
| `runningTime` | 필수, 1~1440(분) |
| `castInfo`, `notice`, `guide` | 선택, 길이 제한 없음, 줄바꿈 허용 |
| `posterUrl` | 필수, 이미지 업로드(IMG-01)로 올린 **우리 저장소 주소(`IMAGE_BASE_URL` + `/`로 시작하는 https)만** 받고 공백 없음, 2048자 이하. 다른 사이트 주소·http는 400 |
| `saleOpenAt`, `saleCloseAt` | 선택. 둘 다 있으면 종료가 오픈보다 늦어야 함(같은 시각도 400). 과거 시각인지는 공개(SLR-04)에서 봄 |
| `maxPerPerson` | 선택, 1~5 (임시) |
| `receiveType` | 선택, `ONSITE` / `DELIVERY` / `BOTH` |
| `bookingFee` | 선택, 0~10,000원 (임시) |
| `images` | 선택(없거나 빈 배열이면 이미지 없이 저장), 장수 제한 없음(임시). `url`은 포스터와 같은 규칙, `sortOrder`는 필수·0 이상·같은 요청 안에서 겹치면 안 됨 |

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `COMMON000` | 입력 규칙 위반, 정해지지 않은 장르·수령 방법, 이미지 순서값 중복 |
| 400 | `PERFORMANCE001` | 판매 종료가 판매 오픈보다 늦지 않음 |
| 400 | `VENUE001` | 사용 중지된 공연장 |
| 404 | `VENUE000` | 공연장 없음 |
| 404 | `PERFORMANCE100` | 취소 규정 없음 또는 다른 사업체의 규정 (둘 다 같은 404) |

### SLR-03 공연 수정 — `PUT /api/v1/seller/performances/{performanceId}` (구현됨)

요청은 SLR-02와 같은 필드 전체이고 **전체 교체**입니다. 이미지 목록도 전체 교체합니다. 판매 필드는 공연의 일반 수정 필드이며 별도 판매 정책 API는 없습니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼내고, 다른 사업체의 공연은 없는 공연과 같은 404입니다.

- 취소된(`CANCELED`) 공연은 수정할 수 없습니다. (`PERFORMANCE002`)
- 판매 오픈·종료를 둘 다 보내면 종료가 오픈보다 늦어야 합니다. (`PERFORMANCE001`) 작성 중(`DRAFT`)에는 판매 필드를 비워 둘 수 있습니다.

**공개(`PUBLISHED`) 후 수정**

- **공연장·취소 규정**은 **값이 실제로 바뀔 때만** 막습니다. 전체 교체라 제목만 고쳐도 두 번호가 함께 가므로, 같은 값은 통과합니다. 다른 공연장이면 `PERFORMANCE003`, 다른 취소 규정이면 `PERFORMANCE004`(둘 다 409)입니다.
- **판매 필드**(판매 오픈·종료, 1인 최대 매수, 수령 방법, 예매 수수료)는 비울 수 없습니다. (400 `PERFORMANCE401`)
- **판매 오픈 일시**는 판매 오픈 **전**에만 바꿀 수 있고, 새 오픈이 지금부터 **3시간보다 더 남아** 있어야 합니다. (`PERFORMANCE406`, 공개 때와 같은 기준, 임시) 판매가 **열린 뒤**에는 바꿀 수 없습니다. (409 `PERFORMANCE005`) 오픈을 그대로 보내는 것은 언제나 통과합니다.
- **판매 종료 일시**는 늘리든 줄이든 **지금보다 늦으면** 됩니다. 기존 종료와 비교하지 않아서 판매자가 일찍 마감할 수 있습니다. 지난 시각이면 400 `PERFORMANCE405`입니다.
- 공개 때와 같이 취소되지 않은 모든 회차는 판매 오픈보다 늦게 시작해야 하고(`PERFORMANCE407`), 판매 종료는 마지막 회차 시작보다 늦을 수 없습니다. (`PERFORMANCE408`)
- **1인 최대 매수**는 늘릴 수 있고, **좌석을 차지한 예매**(결제 대기·결제 완료·일부 취소)가 하나라도 있으면 **줄일 수 없습니다**. (409 `PERFORMANCE006`) 전체 취소·만료된 예매만 있으면 줄일 수 있습니다. 확인한 직후 같은 순간에 점유가 옛 한도로 통과하는 빈틈은 알고 감수합니다. (결제 준비에서 한도를 다시 확인하는 것을 C님께 제안)
- **수령 방법**은 공개 후에도 언제든 바꿀 수 있습니다. 점유 중인 예매는 결제 준비 때 현재 값으로 다시 검사되고, 결제가 끝난 예매는 예매에 저장된 값이 그대로입니다.

**작성 중(`DRAFT`) 공연장 변경**

공연장은 `DRAFT`일 때만 다른 공연장으로 바꿀 수 있습니다. 바꾸면 같은 트랜잭션에서 다음을 합니다.

1. 새 공연장의 사용 여부를 확인합니다. (없는 공연장 404 `VENUE000`, 사용 중지 400 `VENUE001`)
2. 이 공연의 취소되지 않은 회차가 새 공연장의 다른 회차와 시간이 겹치지 않는지 확인합니다. (409 `PERFORMANCE007`)
3. 구역 설정을 새 공연장의 모든 구역 기준 `UNSET`으로 다시 만듭니다. 등급(`seat_grade`)은 유지합니다.
4. 판매 좌석이 있으면 **판매자 보류석(`RESERVED`)을 포함해 모두 지우고**, 취소되지 않은 회차의 `seatsGeneratedAt`을 비워 스케줄러가 새 공연장 기준으로 다시 만들게 합니다. 작성 중 공연은 예매·점유가 불가능하므로 임시 점유·판매 완료 좌석은 있을 수 없습니다.

**같은 공연장의 회차 시간 겹침**

공연장을 바꿀 때(새 공연장 기준)와 러닝타임을 **늘릴 때**(지금 공연장 기준) 이 공연의 취소되지 않은 모든 회차를 검사합니다. 러닝타임을 줄일 때는 겹침이 생기지 않아 검사하지 않습니다. 회차가 차지하는 시간은 `start_at` ~ `start_at + 러닝타임 + 1시간`(1시간은 임시)이고 끝나는 시각과 다음 시작이 같으면 겹치지 않습니다. 다른 공연의 회차는 작성 중이어도 세고, 취소된 회차·취소된 공연의 회차·새 일정이 정해지지 않은 연기 회차는 세지 않습니다. 겹치는 **이 공연의 회차** 일시가 메시지에 나옵니다. (다른 공연의 회차 일시는 다른 판매자의 정보라 알려 주지 않습니다) 회차 추가(SLR-12)는 이와 달리 겹친 회차가 내 사업체 것이면 다른 공연이어도 공연명과 일시를 알려 줍니다.

**검사 순서** (에러는 한 번에 하나씩, 먼저 걸린 것이 나옵니다)

요청 형식 400 `COMMON000` → 판매 종료가 오픈보다 늦지 않음 `PERFORMANCE001` → 공연 없음·남의 공연 404 → (락) → 취소 `002` → 공개 후 공연장 `003` → 규정 `004` → 판매 필드 `401` → 오픈 변경 `005` → 판매 종료 `405` → 오픈 3시간 `406` → 취소 규정 `PERFORMANCE100` → 공연장 `VENUE000`·`VENUE001` → 회차 `407`·`408` → 1인 매수 `006` → 회차 겹침 `007` → 저장

- 락은 공연 단위(`performance:{공연 번호}`)입니다. 같은 공연의 등급·구역 수정(SLR-10·11), 공개(SLR-04), 판매 좌석 생성과 겹치면 하나씩 처리되고, 락을 1초 안에 잡지 못하면 409 `COMMON004`(잠시 후 다시 시도)입니다. 작성 중 취소 규정 변경은 규정 락을 잡지 않습니다. 같은 공연장의 시간 겹침은 락 없이 위 검사(`PERFORMANCE007`)만 합니다. 서로 다른 공연이 같은 순간에 같은 공연장의 같은 시간을 잡으면 둘 다 통과할 수 있지만, 공연장은 판매자가 미리 정해 오는 것이라 매우 드물다고 보고 감수합니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "status": "DRAFT",
    "updatedAt": "2026-11-13T11:05:00"
  }
}
```

`updatedAt`은 공연 행이 바뀐 시각입니다. 값이 그대로여서 저장하지 않았으면 이전 값입니다.

**감사 로그**: `PERFORMANCE` / `UPDATE`. 전후 값에는 바뀐 항목만 요청 필드 이름으로 담습니다(이미지는 `images`). 작성 중 공연의 공연장을 바꾸면 구역 설정과 판매 좌석이 지워지므로 `zonesAndSeatsReset`(전 `false`, 후 `true`)을 더합니다.

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `COMMON000` | 요청 형식 오류 (이미지 순서값 중복 포함) |
| 400 | `PERFORMANCE001` | 판매 종료가 오픈보다 늦지 않음 |
| 400 | `PERFORMANCE401` | 공개된 공연의 판매 필드를 비움 |
| 400 | `PERFORMANCE405` | 공개된 공연의 판매 종료가 지금보다 늦지 않음 |
| 400 | `PERFORMANCE406` | 공개된 공연의 새 오픈이 3시간 넘게 남지 않음 (임시) |
| 400 | `PERFORMANCE407` | 판매 오픈보다 먼저(같은 시각 포함) 시작하는 회차 (메시지에 회차 일시) |
| 400 | `PERFORMANCE408` | 판매 종료가 마지막 회차 시작보다 늦음 |
| 400 | `VENUE001` | 사용이 중지된 공연장으로 변경 |
| 404 | `PERFORMANCE000` | 공연 없음 또는 다른 사업체의 공연 (둘 다 같은 404) |
| 404 | `PERFORMANCE100` | 없는 취소 규정이거나 다른 사업체의 규정 |
| 404 | `VENUE000` | 없는 공연장으로 변경 |
| 409 | `PERFORMANCE002` | 취소된 공연 |
| 409 | `PERFORMANCE003` | 공개된 공연의 공연장 변경 |
| 409 | `PERFORMANCE004` | 공개된 공연의 취소 규정 변경 |
| 409 | `PERFORMANCE005` | 판매가 열린 뒤 판매 오픈 일시 변경 |
| 409 | `PERFORMANCE006` | 좌석을 차지한 예매가 있는데 1인 최대 매수 줄이기 |
| 409 | `PERFORMANCE007` | 같은 공연장의 다른 회차와 시간 겹침 (메시지에 이 공연의 회차 일시) |
| 409 | `COMMON004` | 락을 1초 안에 잡지 못함 (잠시 후 다시 시도) |

### SLR-04 공연 공개 — `POST /api/v1/seller/performances/{performanceId}/publish` (구현됨)

Body 없음. `DRAFT` → `PUBLISHED`. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼내고, 다른 사업체의 공연은 없는 공연과 같은 404입니다.

공개하면 구역·등급·취소 규정을 되돌릴 수 없어서 아래 조건을 **모두** 만족해야 합니다. 에러는 한 번에 하나씩, 아래 순서로 먼저 걸린 것이 나옵니다.

1. 작성 중(`DRAFT`)이어야 합니다. (`PERFORMANCE400`)
2. 판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료가 모두 설정되어 있어야 합니다. (`PERFORMANCE401`)
3. 판매 종료 일시가 지금보다 늦어야 합니다. (`PERFORMANCE405`)
4. 판매 오픈까지 **3시간보다 더 남아** 있어야 합니다. 이미 오픈이 지났어도 같은 에러입니다. (`PERFORMANCE406`, 임시) 판매 좌석은 오픈 24시간 전부터 스케줄러가 주기적으로 만드는데, 오픈 직전에 공개하면 판매가 열렸는데 좌석이 없는 틈이 생기기 때문입니다. 스케줄러 주기가 바뀌면 이 값도 다시 정합니다.
5. 취소되지 않은 회차가 1개 이상이어야 합니다. (`PERFORMANCE402`)
6. 취소되지 않은 모든 회차가 판매 오픈보다 늦게 시작해야 합니다. 같은 시각도 안 됩니다. 메시지에 해당 회차 일시가 나옵니다. (`PERFORMANCE407`)
7. 판매 종료가 마지막 회차 시작보다 늦으면 안 됩니다. 판매 종료 뒤에 공연하는 회차는 괜찮습니다. (`PERFORMANCE408`)
8. 설정하지 않은(`UNSET`) 구역이 없어야 합니다. 메시지에 구역 이름이 나옵니다. (`PERFORMANCE403`)
9. 판매할(`ASSIGNED`) 구역이 1개 이상이어야 합니다. (`PERFORMANCE404`)
10. 전석(`ALL`)과 다른 등급이 함께 구역에 지정되어 있으면 안 됩니다. (`PERFORMANCE303`)

- 공연 수정, 같은 공연의 구역·등급 수정(SLR-10·11), 그 공연이 쓰는 취소 규정 수정(SLR-22)과 겹치면 하나씩 처리됩니다. 락을 1초 안에 잡지 못하면 409 `COMMON004`(잠시 후 다시 시도)입니다. 서비스 기본 규정을 쓰는 공연은 규정 락을 잡지 않습니다. (기본 규정은 항상 수정할 수 없어서 부딪히지 않습니다)
- 공개 두 번이나 공연 취소와 겹쳐도 상태 조건부 UPDATE(`DRAFT`일 때만 바뀜)가 마지막으로 막아 409 `PERFORMANCE400`입니다.
- 문제 구역·회차는 에러 응답의 `data`가 아니라 **메시지**에 나옵니다. (에러 응답에 `data`를 담는 방식은 아직 정하지 않았습니다)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "status": "PUBLISHED",
    "intentionallyUnsoldZones": [
      {
        "zoneId": 22,
        "name": "H구역"
      }
    ]
  }
}
```

**감사 로그**: `PERFORMANCE` / `PUBLISH`. 변경 전 `{"status":"DRAFT"}`, 변경 후 `{"status":"PUBLISHED"}`입니다.

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `PERFORMANCE401` | 판매 필드 미설정 |
| 400 | `PERFORMANCE402` | 취소되지 않은 회차 없음 |
| 400 | `PERFORMANCE403` | 설정하지 않은(`UNSET`) 구역 있음 (메시지에 구역 이름) |
| 400 | `PERFORMANCE404` | 판매할(`ASSIGNED`) 구역 없음 (모든 구역이 의도적 미판매) |
| 400 | `PERFORMANCE405` | 판매 종료 일시가 지남 |
| 400 | `PERFORMANCE406` | 판매 오픈까지 3시간 이하 (이미 지난 경우 포함, 임시) |
| 400 | `PERFORMANCE407` | 판매 오픈보다 먼저(같은 시각 포함) 시작하는 회차 (메시지에 회차 일시) |
| 400 | `PERFORMANCE408` | 판매 종료가 마지막 회차 시작보다 늦음 |
| 404 | `PERFORMANCE000` | 공연 없음 또는 다른 사업체의 공연 (둘 다 같은 404) |
| 409 | `PERFORMANCE400` | 작성 중이 아님 (이미 공개·취소, 동시에 공개·취소된 경우 포함) |
| 409 | `PERFORMANCE303` | 전석(`ALL`)과 다른 등급이 함께 구역에 지정됨 |
| 409 | `COMMON004` | 락을 1초 안에 잡지 못함 (잠시 후 다시 시도) |

### SLR-05 내 공연 목록 — `GET /api/v1/seller/performances` (구현됨)

Query: `status`(`DRAFT/PUBLISHED/CANCELED`), `page`, `size`

- 로그인한 판매자 사업체의 공연만 나옵니다. 사업체 번호는 토큰에서 꺼냅니다.
- `status`를 주지 않으면 취소된 공연까지 모든 상태가 나옵니다.
- 최신 등록이 먼저 나오고 등록 시각이 같으면 번호가 큰 공연이 먼저입니다. 정렬은 고정이라 `sort`를 보내도 무시합니다.
- 기본·최대 크기는 20입니다. 크기가 20을 넘으면 에러가 아니라 20으로 줄어들고, 크기 0은 기본값으로 처리됩니다.
- `scheduleCount`는 취소·연기된 회차를 포함한 모든 회차 수이고, 회차가 없으면 0입니다. `venueName`은 사용 중지된 공연장도 보입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "performanceId": 12,
        "title": "가상 콘서트",
        "status": "PUBLISHED",
        "posterUrl": "https://cdn.example.com/posters/12.jpg",
        "venueName": "가상 아레나 (샘플)",
        "scheduleCount": 2,
        "createdAt": "2026-11-13T10:20:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### SLR-06 내 공연 상세 — `GET /api/v1/seller/performances/{performanceId}` (구현됨)

- 로그인한 판매자 사업체의 공연만 볼 수 있습니다. 다른 사업체의 공연이거나 없는 공연은 똑같이 404 `PERFORMANCE000`입니다.
- 작성 중(`DRAFT`)인 공연은 판매 필드(`saleOpenAt`, `saleCloseAt`, `maxPerPerson`, `receiveType`, `bookingFee`)가 `null`일 수 있습니다.
- `images`는 `sortOrder` 순, `schedules`는 공연 일시가 빠른 순이며 취소·연기된 회차도 포함합니다. 공연장은 사용 중지된 곳도 이름이 보입니다.
- `schedules` 항목의 `pauseReason`은 일시 중지 회차의 중지 사유이고 그 밖은 `null`입니다. 재개 확인 화면에서 씁니다. 예매자 공연 상세(PFM-02)에는 판매 상태가 `PAUSED`인 회차에만 같은 값을 줍니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "status": "PUBLISHED",
    "venue": {
      "venueId": 1,
      "name": "가상 아레나 (샘플)"
    },
    "title": "가상 콘서트",
    "genre": "CONCERT",
    "ageLimit": 12,
    "runningTime": 150,
    "castInfo": "가상 밴드",
    "posterUrl": "https://cdn.example.com/posters/12.jpg",
    "notice": "...",
    "guide": "...",
    "images": [
      {
        "url": "https://cdn.example.com/details/12-1.jpg",
        "sortOrder": 1
      }
    ],
    "cancelPolicyId": 1,
    "saleOpenAt": "2026-11-25T20:00:00",
    "saleCloseAt": "2026-12-20T18:00:00",
    "maxPerPerson": 4,
    "receiveType": "BOTH",
    "bookingFee": 2000,
    "schedules": [
      {
        "scheduleId": 31,
        "startAt": "2026-12-20T19:00:00",
        "status": "ON_SALE",
        "seatsGeneratedAt": "2026-11-24T20:00:12"
      }
    ],
    "createdAt": "2026-11-13T10:20:00",
    "updatedAt": "2026-11-13T11:05:00"
  }
}
```

### SLR-08 등급·구역 조회 — `GET /api/v1/seller/performances/{performanceId}/grades` (구현됨)

대표·담당자 모두 조회할 수 있고, 취소(`CANCELED`)된 공연도 지난 판매 설정을 확인할 수 있게 조회됩니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "locked": false,
    "grades": [
      {
        "gradeId": 20,
        "name": "VIP",
        "price": 165000
      }
    ],
    "zones": [
      {
        "zoneId": 12,
        "name": "F2구역",
        "floor": "1층",
        "sortOrder": 3,
        "seatCount": 220,
        "assignmentStatus": "ASSIGNED",
        "gradeId": 20
      },
      {
        "zoneId": 22,
        "name": "H구역",
        "floor": "2층",
        "sortOrder": 22,
        "seatCount": 42,
        "assignmentStatus": "INTENTIONALLY_UNSOLD",
        "gradeId": null
      },
      {
        "zoneId": 23,
        "name": "J구역",
        "floor": "2층",
        "sortOrder": 23,
        "seatCount": 40,
        "assignmentStatus": "UNSET",
        "gradeId": null
      }
    ]
  }
}
```

- `locked`: 판매 좌석이 생성되어 등급·가격·구역 설정을 수정할 수 없는 상태입니다. 취소(`CANCELED`)되지 않은 회차 중 하나라도 판매 좌석을 만들었으면(`seats_generated_at`이 있음) `true`입니다. 가격은 좌석을 만들 때 복사되므로 일부 회차만 만든 상태에서 고치면 회차끼리 가격이 어긋나기 때문입니다. 취소된 회차는 더 팔리지 않고 환불은 예매 때 저장한 가격으로 해서 세지 않습니다. SLR-09·10·11의 "좌석 생성 후라 수정 불가"(409)도 같은 기준입니다.
- `grades`: 가격 높은 순, 가격이 같으면 번호순입니다. 등급이 없으면 빈 배열입니다.
- `name`: 등급 코드입니다(아래 표). 서버는 코드만 내리고, 화면에 보이는 이름("VIP석")과 색은 프론트가 코드마다 정합니다. 코드는 추가만 하고 이름을 바꾸거나 지우지 않습니다(이미 저장된 코드를 프론트가 계속 보여 줄 수 있게). 프론트는 모르는 코드가 오면 코드를 그대로 보여 줍니다.

| 코드 | 표시 예 | 코드 | 표시 예 |
| --- | --- | --- | --- |
| `VIP` | VIP석 | `OP` | OP석 |
| `R` | R석 | `VIEW_LIMITED` | 시야제한석 |
| `S` | S석 | `ALL` | 전석 |
| `A` | A석 | `STANDING` | 스탠딩석 |
| `B` | B석 | `VIP_STANDING` | VIP 스탠딩석 |
| `C` | C석 | `RESERVED` | 지정석 |
- `zones`: 공연의 구역 설정(`zone_grade`) 행마다 하나씩, 공연장 구역 순서(`sortOrder`)순(같으면 구역 번호순)입니다. `gradeId`는 `ASSIGNED`일 때만 있고 나머지는 `null`입니다.

**에러**: 404 `PERFORMANCE000` 다른 사업체의 공연이거나 없는 공연 (번호가 있는지 알려주지 않으려고 같은 404)

### SLR-09 등급 추가 — `POST /api/v1/seller/performances/{performanceId}/grades` (구현됨)

- 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 공연당 등급 개수의 상한은 없습니다.
- `name`: 필수, SLR-08의 등급 코드 중 하나. 목록에 없는 값은 400입니다. 같은 공연에는 같은 등급을 하나만 둘 수 있어서, 이미 있는 등급이면 409입니다.
- `price`: 필수, 0원 이상 10,000,000원 이하(임시). 0원은 무료 좌석입니다.
- 전석(`ALL`)은 다른 등급과 함께 만들어 둘 수 있습니다. 새 등급은 아직 어느 구역에도 지정되지 않아 예매자 화면에 보이지 않기 때문입니다. 전석과 다른 등급이 **함께 구역에 지정되는 것**은 SLR-10·11·04에서 막습니다(409 `PERFORMANCE303`).
- 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면(SLR-08의 `locked`) 추가할 수 없습니다.
- 판정 순서: 입력 형식 400 → 공연 404 → 취소된 공연 409 → 같은 등급 409 → `locked` 409. 다른 사업체의 공연은 취소·`locked` 상태와 상관없이 404입니다.

**요청**

```json
{ "name": "R", "price": 154000 }
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "gradeId": 21,
    "name": "R",
    "price": 154000
  }
}
```

**감사 로그**: `SEAT_GRADE` / `CREATE`, `target_id`는 등급 번호. 변경 전 값은 없고 변경 후 `performanceId`·`name`·`price`입니다.

**에러**: 400 `COMMON000` 입력 형식 오류 · 404 `PERFORMANCE000` 없는 공연·다른 사업체의 공연 · 409 `PERFORMANCE002` 취소된 공연 · 409 `PERFORMANCE302` 같은 등급이 이미 있음 · 409 `PERFORMANCE301` 좌석 생성 후라 수정 불가

### SLR-10 등급 수정 — `PUT /api/v1/seller/performances/{performanceId}/grades/{gradeId}` (구현됨)

요청은 SLR-09와 같은 필드이고 입력 규칙도 같습니다.

- 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 등급과 가격을 **전체 교체**합니다.
- 자기 등급 그대로(가격만 바꾸는 경우 포함)는 200입니다. 같은 공연의 **다른 등급**과 같은 등급으로 바꾸면 409입니다.
- 구역에 지정된(`ASSIGNED`) 등급을 전석(`ALL`)으로 바꾸거나 전석을 다른 등급으로 바꿔서, 전석과 다른 등급이 함께 구역에 지정되면 409 `PERFORMANCE303`입니다. 구역에 지정되지 않은 등급은 예매자 화면에 보이지 않아 검사하지 않습니다.
- 경로의 공연에 속한 등급만 고칠 수 있습니다. 같은 사업체의 다른 공연 등급도 404입니다.
- 판정 순서: 입력 형식 400 → 공연 404 → 취소된 공연 409 → 등급 404 → 같은 등급 409 → 전석 섞임 409 → `locked` 409. 다른 사업체의 공연은 취소·`locked` 상태와 상관없이 404입니다.
- 가격을 고치는 순간 판매 좌석 생성이 고치기 전 가격을 복사하지 않도록 공연 단위 락(`performance:{공연 번호}`)을 잡습니다. 1초 안에 못 잡으면 409 `COMMON004`(잠시 후 다시 시도)입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "gradeId": 21,
    "name": "R",
    "price": 150000
  }
}
```

**감사 로그**: `SEAT_GRADE` / `UPDATE`, `target_id`는 등급 번호. 전후 모두 `performanceId`·`name`·`price`입니다.

**에러**: 400 `COMMON000` 입력 형식 오류 · 404 `PERFORMANCE000` 없는 공연·다른 사업체의 공연 · 404 `PERFORMANCE300` 없는 등급·이 공연의 등급이 아님 · 409 `PERFORMANCE002` 취소된 공연 · 409 `PERFORMANCE302` 같은 등급이 이미 있음 · 409 `PERFORMANCE303` 전석과 다른 등급이 함께 지정됨 · 409 `PERFORMANCE301` 좌석 생성 후라 수정 불가 · 409 `COMMON004` 락 대기 초과

### SLR-11 구역별 상태·등급 설정 — `PATCH /api/v1/seller/performances/{performanceId}/zone-grades` (구현됨)

요청에 포함된 구역만 변경합니다. 빠진 구역은 기존 상태를 유지합니다. `ASSIGNED`는 `gradeId`가 필요하고, `INTENTIONALLY_UNSOLD`와 `UNSET`은 `gradeId: null`로 보냅니다. 의도적 미판매와 아직 설정하지 않은 상태를 구분합니다.

전석(`ALL`)과 다른 등급이 함께 `ASSIGNED`로 지정되면 409 `PERFORMANCE303`입니다. 구역 하나씩이 아니라 **요청을 적용한 뒤의 최종 상태**(요청에 없는 구역 포함)로 판단합니다. 그래서 R석·S석 구역을 한 요청에 모두 전석으로 바꾸는 것은 됩니다.

**요청**

```json
{
  "assignments": [
    { "zoneId": 12, "assignmentStatus": "ASSIGNED", "gradeId": 21 },
    { "zoneId": 22, "assignmentStatus": "INTENTIONALLY_UNSOLD", "gradeId": null },
    { "zoneId": 23, "assignmentStatus": "UNSET", "gradeId": null }
  ]
}
```

**응답 200** (SLR-08과 같은 형태)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "locked": false,
    "grades": [
      {
        "gradeId": 21,
        "name": "R",
        "price": 154000
      }
    ],
    "zones": [
      {
        "zoneId": 12,
        "name": "F2구역",
        "floor": "1층",
        "sortOrder": 3,
        "seatCount": 220,
        "assignmentStatus": "ASSIGNED",
        "gradeId": 21
      },
      {
        "zoneId": 22,
        "name": "H구역",
        "floor": "2층",
        "sortOrder": 22,
        "seatCount": 42,
        "assignmentStatus": "INTENTIONALLY_UNSOLD",
        "gradeId": null
      }
    ]
  }
}
```

**규칙** (구현됨)

- 요청 전체를 한 번에 적용합니다. 하나라도 틀리면 **아무것도 바꾸지 않습니다.**
- `assignments`가 비었거나 없거나, 같은 `zoneId`가 두 번 들어오면 400 `COMMON000`입니다.
- `ASSIGNED`를 `UNSET`·`INTENTIONALLY_UNSOLD`로 되돌릴 수 있습니다. 값이 그대로인 항목·요청도 200입니다.
- 공연이 `PUBLISHED`여도 판매 좌석 생성 전이면 설정할 수 있습니다. 대표·담당자 모두 가능합니다.
- 판매 좌석 생성과 겹치면 잠시 기다리고, 1초 안에 끝나지 않으면 409 `COMMON004`(잠시 후 다시 시도)입니다.
- 검사 순서: 요청 형식 400 → 상태·`gradeId` 조합 400 → 공연 404 → 취소된 공연 409 → 공연장에 없는 구역 400 → 공연에 없는 등급 400 → 전석 섞임 409 → 좌석 생성 후 409. 좌석이 생성된 공연에 없는 구역을 보내면 400이 먼저입니다.

**감사 로그**: `PERFORMANCE` / `ZONE_ASSIGN`. 요청 한 번에 한 줄이고, 전후 `zones`에 바뀐 구역만 `zoneId`·`zoneName`·`assignmentStatus`·`gradeName`으로 담습니다.

**에러**

| 상태 | 코드 | 언제 |
|---|---|---|
| 400 | `COMMON000` | 빈 목록, 항목 값 누락, 모르는 상태 값, 같은 구역 중복 |
| 400 | `PERFORMANCE306` | `ASSIGNED`인데 `gradeId`가 없음, `UNSET`·`INTENTIONALLY_UNSOLD`인데 `gradeId`가 있음 |
| 400 | `PERFORMANCE304` | 이 공연의 공연장에 없는 구역(다른 공연장의 구역, 없는 번호) |
| 400 | `PERFORMANCE305` | 이 공연에 없는 등급(없는 번호, 다른 공연의 등급) |
| 404 | `PERFORMANCE000` | 없는 공연, 다른 사업체의 공연 |
| 409 | `PERFORMANCE002` | 취소된 공연 |
| 409 | `PERFORMANCE303` | 전석과 다른 등급이 함께 지정됨 |
| 409 | `PERFORMANCE301` | 판매 좌석 생성 후라 수정 불가(`locked`) |
| 409 | `COMMON004` | 판매 좌석 생성과 겹쳐 1초 안에 처리하지 못함 |

### SLR-12 회차 추가 — `POST /api/v1/seller/performances/{performanceId}/schedules` (구현됨)

공연에 회차를 하나 추가합니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼내고, 다른 사업체의 공연은 상태와 상관없이 없는 공연과 같은 404 `PERFORMANCE000`입니다. 회차 개수 제한은 없습니다.

**요청**

```json
{ "startAt": "2026-12-20T19:00:00" }
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "scheduleId": 31,
    "performanceId": 12,
    "startAt": "2026-12-20T19:00:00",
    "status": "ON_SALE",
    "seatsGeneratedAt": null
  }
}
```

**시각 조건**

| 조건 | 작성 중(`DRAFT`) | 공개(`PUBLISHED`) |
|---|---|---|
| `startAt`은 지금보다 늦어야 함 | 적용 | 적용 |
| 판매 오픈이 설정되어 있으면 오픈보다 늦어야 함(같으면 거절) | 적용 | 적용 |
| 지금부터 3시간(임시)보다 뒤여야 함. 더 이른 회차가 있으면 그 공연을 공개할 수 없음 | 적용 | - |
| 취소되지 않은 회차가 모두 시작했으면 거절 | - | 적용 |
| 판매가 끝났으면(`saleCloseAt <= 지금`) 거절 | - | 적용 |

판매 종료가 마지막 회차 시작보다 늦은지(`PERFORMANCE408`)는 보지 않습니다. 공개와 공연 수정에서 확인합니다.

**같은 공연장의 시간 겹침**: 회차가 차지하는 시간은 `startAt` ~ `startAt + 러닝타임 + 1시간`(임시)이고 끝나는 시각과 다음 시작이 같으면 겹치지 않습니다. 같은 공연의 회차와 다른 공연(작성 중 포함)의 회차를 모두 보며, 취소된 회차·취소된 공연의 회차·새 일정이 정해지지 않은 연기 회차는 세지 않습니다. 겹친 회차가 **내 사업체** 것이면 메시지에 공연명·일시·사용 종료 시각을 넣고(끝나는 날이 다르면 날짜까지), 다른 판매자의 회차는 알려 주지 않습니다.

```
같은 공연장의 다른 회차와 시간이 겹칩니다: 가상 콘서트 2026-12-20 18:00 (21:30까지 사용)
```

**판매 좌석 즉시 생성**: 공개 공연이고 판매 오픈 24시간(임시) 전부터는 회차를 추가하면서 판매 좌석을 바로 만듭니다(판매 좌석 생성 SLR-14와 같은 방식). 이때 `seatsGeneratedAt`에 시각이 들어오고, 만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없습니다. 좌석을 만들 수 없으면 **회차도 추가되지 않습니다.** 작성 중 공연은 판매 오픈과 상관없이 만들지 않습니다.

에러는 한 번에 하나씩, 아래 순서로 먼저 걸린 것이 나옵니다.

| 순서 | 조건 | 코드 |
|---|---|---|
| 1 | `startAt`이 없거나 지금 이전·같음 | 400 `COMMON000` |
| 2 | 없는 공연이거나 다른 사업체의 공연 | 404 `PERFORMANCE000` |
| 3 | 락을 1초 안에 잡지 못함 | 409 `COMMON004`(잠시 후 다시 시도) |
| 4 | 취소된 공연 | 409 `PERFORMANCE002` |
| 5 | 사용이 중지된 공연장 | 400 `VENUE001` |
| 6 | 판매 오픈보다 늦지 않음 | 400 `PERFORMANCE407` |
| 7 | (작성 중) 3시간 이내 | 400 `PERFORMANCE009` |
| 8 | (공개) 회차가 모두 시작함 | 409 `PERFORMANCE010` |
| 9 | (공개) 판매가 끝남 | 400 `PERFORMANCE405` |
| 10 | (공개) 아직 시작하지 않은 판매 중·일시 중지 회차가 하나 이상 있고 모두 일시 중지 | 409 `PERFORMANCE018` |
| 11 | (즉시 생성) 설정하지 않은(`UNSET`) 구역이 있음. 메시지에 구역 이름이 나옴 | 400 `PERFORMANCE403` |
| 12 | (즉시 생성) 판매할(`ASSIGNED`) 구역이 없음 | 400 `PERFORMANCE404` |
| 13 | 같은 공연장의 다른 회차와 시간 겹침 | 409 `PERFORMANCE007` |
| 14 | (즉시 생성) 복사할 공연장 좌석이 없음(깨진 데이터) | 409 `PERFORMANCE503` |

- 락은 공연 단위(`performance:{공연 번호}`)입니다. 같은 공연의 회차 수정(SLR-13)·공개(SLR-04)·공연 수정(SLR-03)·등급과 구역 수정(SLR-10·11)·좌석 생성(SLR-14)과 겹치면 하나씩 처리됩니다.
- 감사 로그: `SCHEDULE` / `CREATE`, `target_id`는 회차 번호. 변경 전 값은 없고 변경 후 `performanceId`·`startAt`·`seatsGenerated`(판매 좌석을 바로 만들었는지)입니다.

**일시 중지 중의 회차 추가 (2026-11-27)**: 공개 공연의 아직 시작하지 않은 회차(판매 중·일시 중지만 봄)가 모두 일시 중지면 회차를 추가할 수 없습니다(409 `PERFORMANCE018` "판매를 일시 중지한 공연에는 회차를 추가할 수 없습니다. 먼저 판매를 재개해 주세요."). 화면은 이때 회차 추가 버튼을 끄고 이유를 보여 줍니다. 하나라도 판매 중이거나, 그런 회차가 없거나(시작·취소·연기 회차만 남음), 작성 중 공연이면 `ON_SALE`로 추가합니다.

### SLR-13 회차 수정 — `PUT /api/v1/seller/schedules/{scheduleId}` (구현됨)

회차의 공연 일시를 바꿉니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼내고, 다른 사업체의 회차는 상태와 상관없이 없는 회차와 같은 404 `PERFORMANCE008`입니다.

**요청**

```json
{ "startAt": "2026-12-20T18:00:00" }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "scheduleId": 31,
    "startAt": "2026-12-20T18:00:00",
    "updatedAt": "2026-11-14T09:30:00"
  }
}
```

`updatedAt`은 바뀐 시각입니다. 같은 일시를 보내 아무것도 바꾸지 않았으면 이전 값입니다.

**바꿀 수 있는 회차**

| 회차 | 수정 |
|---|---|
| 판매 중(`ON_SALE`)·일시 중지(`PAUSED`)이고 아직 시작하지 않음 | 가능 |
| 이미 시작함(시작 시각이 지금 이전이거나 같음) | 불가 |
| 연기(`POSTPONED`) | 불가 (새 일정은 연기 이력·상태와 함께 바뀌어야 함) |
| 취소(`CANCELED`) 회차, 취소된 공연의 회차 | 불가 |

- **예매**: 그 회차에 결제 대기·결제 완료·일부 취소·전체 취소 예매가 하나라도 있으면 바꿀 수 없습니다. 만료(`EXPIRED`)만 있으면 바꿀 수 있습니다. 결제 제한 시각이 지났어도 만료 처리 전인 결제 대기는 막습니다(만료 처리 주기 약 1분). 판매 중이든 판매 종료 뒤든 같습니다.
- **사용이 중지된 공연장**, **판매가 끝난 공연**의 회차도 바꿀 수 있습니다. (회차 추가는 둘 다 막습니다)
- 요청한 일시가 지금 일시와 같으면 예매가 있어도 200이고 아무것도 바꾸지 않습니다. (취소·연기·시작한 회차는 그 에러가 먼저입니다)

**새 일시 조건**

| 조건 | 작성 중(`DRAFT`) | 공개(`PUBLISHED`) |
|---|---|---|
| `startAt`은 지금보다 늦어야 함 | 적용 | 적용 |
| 판매 오픈이 설정되어 있으면 오픈보다 늦어야 함(같으면 거절) | 적용 | 적용 |
| 지금부터 3시간(임시)보다 뒤여야 함 | 적용 | - |
| 바꾼 뒤에도 판매 종료가 마지막 회차 시작보다 늦지 않아야 함 | - | 적용 |

마지막 회차는 취소되지 않은 회차 중 시작이 가장 늦은 것이고, 바꾸는 회차는 새 일시로 계산합니다. 걸리면 판매 종료를 먼저 공연 수정(SLR-03)에서 앞당긴 뒤 다시 요청합니다. 메시지에 기준이 되는 일시가 나옵니다.

**같은 공연장의 시간 겹침**: 회차 추가(SLR-12)와 같은 기준·메시지이고, 바꾸는 회차 자신은 빼고 봅니다.

**바뀌지 않는 것**: 회차 상태, 판매 좌석과 `seatsGeneratedAt`, 등급·가격·구역 잠금. 좌석을 새로 만들지 않습니다. 연기 이력은 남기지 않습니다.

**감사 로그**: `SCHEDULE` / `UPDATE`, `target_id`는 회차 번호. 전후 모두 `performanceId`·`startAt`이라 바꾸기 전 일시가 남습니다.

에러는 한 번에 하나씩, 아래 순서로 먼저 걸린 것이 나옵니다. 예매가 있는 회차에 판매 오픈보다 이른 일시를 보내면 `PERFORMANCE407`이 먼저입니다. (DB 조회가 필요 없는 검사를 앞에 둠)

| 순서 | 조건 | 코드 | 메시지 |
|---|---|---|---|
| 1 | `startAt`이 없거나 지금 이전·같음 | 400 `COMMON000` | 요청 검증 메시지(SLR-12와 같음) |
| 2 | 없는 회차이거나 다른 사업체의 회차 | 404 `PERFORMANCE008` | 기본 |
| 3 | 락을 1초 안에 잡지 못함 | 409 `COMMON004` | 기본(잠시 후 다시 시도) |
| 4 | 취소된 공연 | 409 `PERFORMANCE002` | 기본 |
| 5 | 취소된 회차 | 409 `PERFORMANCE501` | 취소된 회차는 일시를 바꿀 수 없습니다. |
| 6 | 연기된 회차 | 409 `PERFORMANCE012` | 연기된 회차는 일시를 바꿀 수 없습니다. |
| 7 | 이미 시작한 회차 | 400 `PERFORMANCE502` | 이미 시작한 회차는 일시를 바꿀 수 없습니다. |
| - | 지금과 같은 일시면 여기서 200 | | |
| 8 | 판매 오픈보다 늦지 않음 | 400 `PERFORMANCE407` | SLR-12와 같음(오픈 일시 포함) |
| 9 | (작성 중) 3시간 이내 | 400 `PERFORMANCE009` | 작성 중인 공연의 회차는 지금부터 3시간 뒤의 일시로만 바꿀 수 있습니다. |
| 10 | (공개) 만료가 아닌 예매가 있음 | 409 `PERFORMANCE011` | 결제 대기 중이거나 결제된 적 있는(취소 포함) 예매가 있어 일시를 바꿀 수 없습니다. |
| 11 | (공개) 판매 종료가 마지막 회차 시작보다 늦어짐 | 400 `PERFORMANCE408` | 판매 종료 일시는 마지막 회차 시작보다 늦을 수 없습니다. 판매 종료 일시를 `yyyy-MM-dd HH:mm` 이전으로 먼저 앞당겨 주세요. |
| 12 | 같은 공연장의 다른 회차와 시간 겹침 | 409 `PERFORMANCE007` | SLR-12와 같음 |
| - | 저장하는 순간 회차 상태·일시가 다른 작업으로 바뀜(락이 듣지 않은 경우) | 409 `COMMON004` | 기본. 다시 요청하면 위의 정확한 에러가 나옵니다 |

- 락은 공연 단위(`performance:{공연 번호}`)입니다. 같은 공연의 회차 추가(SLR-12)·공연 수정(SLR-03)·공개(SLR-04)·등급과 구역 수정(SLR-10·11)·좌석 생성(SLR-14)과 겹치면 하나씩 처리됩니다.
- 좌석 점유(BKG-01)는 이 락을 잡지 않아, 예매를 확인한 직후 저장하기 전에 들어온 점유는 막지 못합니다. (알고 감수, `seller-progress.md` TODO)

### SLR-14 판매 좌석 생성 — `POST /api/v1/seller/schedules/{scheduleId}/seats/generate` (구현됨)

Body 없음. 공연장 좌석을 복사합니다(회차 1개 = 트랜잭션 1개). `ASSIGNED` 구역은 `AVAILABLE`(등급·가격 복사), `INTENTIONALLY_UNSOLD` 구역은 `BLOCKED`(등급·가격 없음)로 생성하고 `RESERVED`는 만들지 않습니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼내고, 다른 사업체의 회차는 상태와 상관없이 없는 회차와 같은 404 `PERFORMANCE008`입니다.

**허용 상태**: 작성 중(`DRAFT`)과 공개(`PUBLISHED`) 공연. 작성 중 공연은 판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료가 모두 설정되어 있어야 합니다. 판매 중·일시 중지·연기 회차도 만들 수 있습니다.

**만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없고 되돌릴 수 없습니다.** (SLR-08 `locked`가 `true`)

**자동 생성**: 판매자 버튼과 별도로 서버가 같은 생성 코드로 판매 좌석을 만듭니다. (API는 없습니다)

- 주기는 1시간(임시, 설정 `app.performance.seat-generation.interval`)이고 서버가 켜지면 바로 한 번 실행합니다. `enabled`를 `false`로 두고 재시작하면 꺼지며, 꺼도 이 API는 동작합니다.
- 대상: **공개(`PUBLISHED`) 공연**의 취소되지 않고 아직 시작하지 않은 회차 중 판매 좌석이 없고 **판매 오픈 24시간(임시) 전**이 지난 것입니다. 일시 중지·연기 회차도 만들고, 판매 종료는 보지 않습니다. 작성 중 공연은 만들지 않습니다.
- 판매 오픈이 빠른 회차부터 만들고 한 번에 만드는 개수 제한은 없습니다. 회차 1개 = 트랜잭션 1개라, 한 회차가 실패해도 나머지는 계속 만듭니다.
- 만들기 직전에 공연 락 안에서 "공개 상태이고 판매 오픈 24시간 전이 지남"을 다시 확인합니다. 대상을 읽은 뒤 판매 오픈이 뒤로 바뀌었으면 만들지 않습니다.
- 만들지 못하면(`UNSET` 구역 등) 서버 로그만 남기고 다음 실행에서 다시 시도합니다. 판매자에게 알리지 않으며, 설정 문제는 이 API("좌석 지금 생성")를 부르면 아래 에러로 이유를 알 수 있습니다.

아래는 이 API의 에러이며, 한 번에 하나씩 아래 순서로 먼저 걸린 것이 나옵니다.

| 순서 | 조건 | 코드 |
|---|---|---|
| 1 | 없는 회차이거나 다른 사업체의 회차 | 404 `PERFORMANCE008` |
| 2 | 취소된 공연 | 409 `PERFORMANCE002` |
| 3 | 취소된 회차 | 409 `PERFORMANCE501` |
| 4 | 이미 판매 좌석이 생성된 회차 | 409 `PERFORMANCE500` |
| 5 | 이미 시작한 회차(시작 시각이 지금 이전이거나 같음) | 400 `PERFORMANCE502` |
| 6 | (작성 중 공연) 판매 정보가 비어 있음 | 400 `PERFORMANCE401` |
| 7 | 설정하지 않은(`UNSET`) 구역이 있음. 메시지에 구역 이름이 나옴 | 400 `PERFORMANCE403` |
| 8 | 판매할(`ASSIGNED`) 구역이 하나도 없음 | 400 `PERFORMANCE404` |
| 9 | 판매 구역은 있는데 복사할 공연장 좌석이 하나도 없음(깨진 데이터). 롤백되어 생성 시각도 비어 있음 | 409 `PERFORMANCE503` |
| - | 락을 1초 안에 잡지 못함 | 409 `COMMON004`(잠시 후 다시 시도) |

- 락은 공연 단위(`performance:{공연 번호}`)입니다. 같은 공연의 등급·구역 수정(SLR-10·11), 공개(SLR-04), 공연 수정과 겹치면 하나씩 처리됩니다. 생성이 먼저 끝나면 수정은 `locked` 409(`PERFORMANCE301`)로 거절되고, 수정이 먼저면 좌석이 수정된 값을 복사합니다.
- 락이 듣지 않아도(Redis 장애·락 만료) 상태 조건부 UPDATE(`seats_generated_at IS NULL`일 때만 채움)와 UNIQUE `(schedule_id, venue_seat_id)`가 한 회차가 두 번 만들어지는 것을 막아 409 `PERFORMANCE500`입니다.
- 문제 구역은 에러 응답의 `data`가 아니라 **메시지**에 나옵니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "scheduleId": 31,
    "seatsGeneratedAt": "2026-11-14T10:00:05",
    "totalSeats": 3022,
    "availableSeats": 2980,
    "blockedSeats": 42
  }
}
```

### SLR-26 회차 판매 일시 중지 — `POST /api/v1/seller/performances/{performanceId}/schedules/pause` (구현됨)

공개(`PUBLISHED`) 공연의 회차 판매를 모두 또는 골라서 일시 중지합니다. 재개(SLR-27)로 다시 열 수 있습니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다. 사업체 번호는 토큰에서 꺼냅니다. 공연에는 "중지" 상태가 없고 회차의 상태만 바꿉니다.

**요청** (모두, 또는 골라서)

```json
{ "all": true, "reason": "좌석 점검" }
```

```json
{ "scheduleIds": [31, 33], "reason": "좌석 점검" }
```

- `all: true`와 `scheduleIds` 중 **정확히 하나**를 보냅니다. `all: false`는 보내지 않은 것과 같습니다.
- `scheduleIds`: 빈 목록과 빈 값(`null`)은 400, 같은 번호는 한 번으로 봅니다.
- `reason`: 필수, 앞뒤 공백을 뺀 255자 이하. 예매자 공연 상세(PFM-02)에 보이므로 화면의 입력창에서 알립니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "changedScheduleIds": [31, 33],
    "skipped": [
      { "scheduleId": 32, "reason": "ALREADY_PAUSED" }
    ]
  }
}
```

- 두 목록은 공연 일시가 빠른 순(같으면 회차 번호순)입니다.
- **바꿀 수 있는 회차만 바꾸고** 나머지는 `skipped`에 이유와 함께 줍니다. `all: true`면 대상이 아닌 회차도 `skipped`에 나옵니다.

| 회차 | 중지(SLR-26) | 재개(SLR-27) |
|---|---|---|
| 판매 중(`ON_SALE`), 시작 전 | 바꿈 | 건너뜀 `ALREADY_ON_SALE` |
| 일시 중지(`PAUSED`), 시작 전 | 건너뜀 `ALREADY_PAUSED` (중지 사유도 그대로) | 바꿈 |
| 취소(`CANCELED`) | 건너뜀 `CANCELED` | 건너뜀 `CANCELED` |
| 연기(`POSTPONED`) | 건너뜀 `POSTPONED` | 건너뜀 `POSTPONED` |
| 시작함(시작 시각이 지금 이전이거나 같음) | 건너뜀 `STARTED` | 건너뜀 `STARTED` |

이유가 겹치면 `CANCELED` → `POSTPONED` → `STARTED` → `ALREADY_*` 순으로 하나만 줍니다.

- 판매 오픈 전과 판매 종료 뒤에도 중지·재개할 수 있습니다.
- 판매 좌석, `seatsGeneratedAt`, 점유, 예매, 공연 상태는 바꾸지 않습니다. 판매 좌석 자동 생성은 지금처럼 중지 회차도 만듭니다.

**중지하면** (재개하면 모두 풀립니다)

| 예매자 | 동작 |
|---|---|
| 새 대기열 입장, 좌석 점유 | 409 `BOOKING012` (대기열 확인은 최대 10초 늦게 반영) |
| 대기 중 | 순번 유지, 입장만 멈춤 (`doc/queue-design.md`) |
| 결제 준비·승인 | 409 `BOOKING012` (BKG-02·03) |
| 중지 전에 승인을 시작한 결제 | 완료 |
| 좌석 조회, 결제한 예매의 직접 취소 | 그대로 (취소 수수료는 평소 규정) |
| 공연 목록·상세 | `PAUSED` (PFM-01·02). 상세의 저장분은 중지·재개 때 지워 바로 반영 |
| 예매자 화면 | 좌석·결제 화면에 들어올 때, 예매·결제 버튼이 거절될 때, 새로 대기열에 들어갈 때 중지를 알면 알림 창을 띄우고, 확인(또는 닫기)하면 공연 상세로 보냄. 열어 둔 화면이 주기적으로 묻지는 않음. 이미 대기 중인 사람은 대기 화면에 남음 |

**에러** (겹치면 위가 먼저. 취소된 공연에 다른 공연의 회차 번호를 보내면 `PERFORMANCE002`)

| 순서 | 조건 | 코드 | 메시지 |
|---|---|---|---|
| 1 | 요청 형식 위반 | 400 `COMMON000` | 요청 검증 메시지 |
| 2 | 없는 공연, 다른 사업체의 공연 | 404 `PERFORMANCE000` | 기본 |
| 3 | 락을 1초 안에 잡지 못함 | 409 `COMMON004` | 기본(잠시 후 다시 시도) |
| 4 | 취소된 공연 | 409 `PERFORMANCE002` | 취소된 공연의 회차는 중지하거나 재개할 수 없습니다. |
| 5 | 작성 중 공연 | 409 `PERFORMANCE015` | 작성 중인 공연의 회차는 중지하거나 재개할 수 없습니다. |
| 6 | 이 공연의 회차가 아닌 번호가 섞임(없는 번호 포함). 아무것도 바꾸지 않음 | 404 `PERFORMANCE008` | 기본 |
| 7 | 바꿀 수 있는 회차가 하나도 없음 | 409 `PERFORMANCE016`(중지) / `PERFORMANCE017`(재개) | 중지할 수 있는 회차가 없습니다. / 재개할 수 있는 회차가 없습니다. |
| 8 | 저장하는 순간 회차가 바뀜(락이 듣지 않은 경우). 전체를 되돌림 | 409 `COMMON004` | 기본 |

- 락은 공연 단위(`performance:{공연 번호}`)라 회차 추가·수정, 공연 수정, 좌석 생성과 하나씩 처리됩니다. 바꿀 상태이고 아직 시작하지 않았을 때만 바꾸는 조건부 UPDATE를 함께 둡니다.
- 좌석 점유(BKG-01)는 이 락을 잡지 않습니다. 중지 순간에 통과한 점유는 결제에서 막힙니다.
- 감사 로그: **바뀐 회차마다 한 줄**, `SCHEDULE` / `PAUSE`, `target_id`는 회차 번호. 변경 전 `performanceId`·`status`, 변경 후 `performanceId`·`status`·`reason`. 변경과 한 트랜잭션이고 건너뛴 회차는 남기지 않습니다.

### SLR-27 회차 판매 재개 — `POST /api/v1/seller/performances/{performanceId}/schedules/resume` (구현됨)

일시 중지한 회차를 다시 판매 중으로 바꿉니다. 권한, 대상, 응답 모양, 검사 순서, 락은 SLR-26과 같습니다.

**요청** (`reason` 없음)

```json
{ "all": true }
```

```json
{ "scheduleIds": [31, 33] }
```

- **`all: true`는 말 그대로 모두입니다.** 따로 중지한 회차까지 재개합니다. 일부만 열려면 `scheduleIds`로 고릅니다.
- 재개한 회차의 중지 사유는 비웁니다. 지난 사유는 감사 로그에 남습니다.
- 사용이 중지된 공연장의 회차도 재개할 수 있습니다.
- 감사 로그: `SCHEDULE` / `RESUME`, 변경 전 `performanceId`·`status`·`reason`(중지 사유), 변경 후 `performanceId`·`status`.

### SLR-15 회차 취소 — `POST /api/v1/seller/schedules/{scheduleId}/cancel`

**요청**

```json
{ "reason": "출연자 사정으로 회차 취소" }
```

환불은 수수료 0%로 `refund`를 만들어 스케줄러가 한 건씩 처리.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "scheduleId": 31,
    "status": "CANCELED",
    "refundRequestedCount": 120
  }
}
```

**에러**(코드 미정): 409 이미 취소된 회차

### SLR-16 공연 취소 — `POST /api/v1/seller/performances/{performanceId}/cancel`

**요청**

```json
{ "reason": "공연 전체 취소" }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "status": "CANCELED",
    "canceledScheduleCount": 2,
    "refundRequestedCount": 245
  }
}
```

**에러**(코드 미정): 409 이미 취소된 공연

### SLR-17 판매 현황 — `GET /api/v1/seller/performances/{performanceId}/sales` (구현됨)

대표·담당자 모두 조회할 수 있고, 취소·작성 중 공연도 조회됩니다(취소 뒤 환불이 끝났는지 볼 수 있게). 다른 사업체의 공연과 없는 공연은 같은 404 `PERFORMANCE000`이고 새 에러 코드는 없습니다. 읽기만 하며 락을 잡지 않습니다.

- `schedules`: 취소·연기·중지 회차를 포함한 **모든 회차**이고 공연 일시 빠른 순입니다. 내 공연 상세(SLR-06)의 회차 목록과 개수가 같습니다. 회차가 없으면 빈 배열입니다.
- `seatsGenerated`: 판매 좌석을 만들었는지(`seats_generated_at` 유무)입니다. `false`이면 `soldSeats`~`blockedSeats` 5개는 모두 `null`입니다. 만든 회차는 없는 상태의 개수를 0으로 줍니다.
- `totalSeats`: 판매 좌석 생성 여부와 상관없이 모든 회차에 공연장의 좌석 수(`venue.total_seats`)입니다.
- 개수는 지금 좌석 상태 그대로입니다. 만료됐지만 아직 정리되지 않은 임시 점유(`HELD`)도 `heldSeats`로 셉니다(정리 주기 1분, 그 좌석은 예매자도 고를 수 없음). 취소된 회차도 지금 상태라, 예매 취소·환불 뒤에는 `soldSeats`가 줄어 이력처럼 보이지 않을 수 있습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "schedules": [
      {
        "scheduleId": 31,
        "startAt": "2026-12-20T19:00:00",
        "status": "ON_SALE",
        "seatsGenerated": true,
        "totalSeats": 3022,
        "soldSeats": 1204,
        "heldSeats": 18,
        "availableSeats": 1750,
        "reservedSeats": 8,
        "blockedSeats": 42
      },
      {
        "scheduleId": 32,
        "startAt": "2026-12-21T19:00:00",
        "status": "ON_SALE",
        "seatsGenerated": false,
        "totalSeats": 3022,
        "soldSeats": null,
        "heldSeats": null,
        "availableSeats": null,
        "reservedSeats": null,
        "blockedSeats": null
      }
    ]
  }
}
```

### SLR-18 회차별 예매 목록 — `GET /api/v1/seller/schedules/{scheduleId}/bookings` (구현됨, 사용 중단 예정)

> 2026-11-25 역할별 예매 조회를 한곳(`BookingQueryService`)으로 모으면서 사용 중단 예정(`@Deprecated`)으로 표시했습니다. 쓰는 화면이 있어서 동작은 그대로입니다. 새 화면은 SLR-23을 씁니다.

Query: `status`, `receiveType`, `bookingNo`, `page`, `size`

내 사업체 공연의 회차에 들어온 예매를 최신 예매순으로 줍니다. 현장 확인·배송·문의 응대용 목록입니다.

- 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자 토큰은 403, 토큰이 없으면 401입니다. 회차가 내 사업체 것이 아니면 없는 회차와 같은 404 `PERFORMANCE008`입니다. 취소된 회차·공연, 작성 중 공연의 회차도 조회할 수 있습니다.
- `status`를 주지 않으면 남은 표가 있는 예매(`PAID`, `PARTIAL_CANCELED`)만 나옵니다. 전체 취소(`CANCELED`)·결제 대기(`PENDING`)·만료(`EXPIRED`)는 `status`로 따로 봅니다. `status`는 한 가지만 받고 모르는 값은 400 `COMMON000`입니다.
- `receiveType`(`ONSITE`·`DELIVERY`)으로 수령 방법을 고를 수 있습니다. `bookingNo`는 예매 번호가 **정확히 일치**하는 예매만 찾고(일부만 넣으면 빈 목록), 다른 회차의 번호도 빈 목록입니다. 조건을 함께 주면 모두 맞아야 합니다.
- 정렬은 예매 시각 최신순, 같으면 번호가 큰 순으로 고정이라 `sort`는 무시합니다. 기본·최대 크기는 20이고, 범위 밖 페이지는 빈 `content`로 200입니다.
- 금액은 수수료 없는 표값입니다. `tickets`는 남은 표, `canceledTickets`는 취소한 표를 등급·가격별로 묶은 것이고(가격 높은 순) 예매 때 저장한 값이라 나중에 등급 가격이 바뀌어도 그대로입니다. `seatCount`는 남은 장수, `ticketAmount`는 남은 표값의 합입니다. 처음 결제 총액·수수료·환불액은 주지 않습니다.
- 주문자 이름·전화번호와 배송 정보(`delivery`, 배송 예매만)는 대표·담당자 모두에게 그대로 줍니다. 주문자 이메일은 주지 않습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "bookingId": 1001,
        "bookingNo": "20261125KCZB03R",
        "status": "PARTIAL_CANCELED",
        "ordererName": "홍길동",
        "ordererPhone": "01012345678",
        "receiveType": "DELIVERY",
        "delivery": { "recipientName": "김받는", "recipientPhone": "01099990000", "recipientAddress": "서울 가상구 1" },
        "tickets": [{ "gradeName": "VIP", "price": 150000, "count": 2, "amount": 300000 }],
        "canceledTickets": [{ "gradeName": "S", "price": 100000, "count": 1, "amount": 100000 }],
        "seatCount": 2,
        "canceledSeatCount": 1,
        "ticketAmount": 300000,
        "createdAt": "2026-11-25T20:03:41"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

---

### SLR-23 내 사업체 예매 목록 — `GET /api/v1/seller/bookings` (구현됨)

Query: `title`, `status`(여러 개), `from`, `to`, `page`, `size` — BKG-04의 "예매 목록 검색 조건"과 같습니다.

- 내 사업체의 **모든 공연**에 들어온 예매를 최신 예매순으로 줍니다. 사업체 번호는 토큰에서 꺼냅니다. 대표·담당자 모두 씁니다.
- `status`를 비우면 남은 표가 있는 예매(`PAID`, `PARTIAL_CANCELED`)만 나옵니다. (SLR-18과 같은 기준)
- 항목은 SLR-18 항목에 공연(`performanceId`, `performanceTitle`)과 회차(`scheduleId`, `scheduleStartAt`)를 더한 모양입니다. 금액은 수수료 없는 표값입니다.
- 예매에 복사해 둔 사업체 번호(`booking.business_id`)와 인덱스 `(business_id, created_at DESC, id DESC)`로 읽어서, 예매가 많은 판매자도 앞 페이지만 읽고 끝납니다.
- `size` 기본 20, 최대 20.

**에러**: 403 `AUTH100` 판매자가 아님 · 400 `COMMON000` 없는 `status` 값, `from`이 `to`보다 늦음

### SLR-24 내 사업체 예매 상세 — `GET /api/v1/seller/bookings/{bookingId}` (구현됨)

- 내 사업체 공연의 예매 하나를 상태와 상관없이 줍니다. 응답은 SLR-23 항목과 같은 모양입니다.
- 다른 사업체 공연의 예매·없는 예매는 404 `BOOKING006`입니다. (번호가 있는지 알려주지 않으려고 같은 응답)

### SLR-19 취소 규정 목록 — `GET /api/v1/seller/cancel-policies` (구현됨)

Query: `page`, `size`

서비스 기본 규정과 로그인한 사업체가 만든 규정을 조회합니다. 기본 규정은 예시로 보여 주며 수정할 수 없습니다.

- 사업체 번호는 토큰에서 꺼내며, 다른 사업체의 규정은 나오지 않습니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 기본 규정이 첫 페이지 맨 앞에 나오고, 내 규정은 번호가 큰(최신) 순입니다. 정렬은 고정이라 `sort`를 보내도 무시합니다. 기본 규정도 목록의 한 항목이라 `totalElements`에 포함됩니다.
- 기본·최대 크기는 20입니다. 크기가 20을 넘으면 에러가 아니라 20으로 줄어들고, 크기 0은 기본값으로 처리됩니다.
- 구간(`rules`)은 목록에 넣지 않고 상세(SLR-20)에서 줍니다.
- `editable`은 지금 이 규정을 수정(SLR-22)할 수 있는지입니다. 기본 규정과, 공개(`PUBLISHED`)·취소(`CANCELED`)된 공연이 쓰는 규정은 `false`입니다. 조회한 시점의 안내라서, 그사이 공연이 공개되면 수정할 때 409가 날 수 있습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      { "cancelPolicyId": 1, "name": "기본 규정", "isDefault": true, "editable": false },
      { "cancelPolicyId": 8, "name": "공연장 자체 규정", "isDefault": false, "editable": true }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

### SLR-20 취소 규정 상세 — `GET /api/v1/seller/cancel-policies/{cancelPolicyId}` (구현됨)

기본 규정 또는 로그인한 사업체의 규정만 조회합니다.

- 사업체 번호는 토큰에서 꺼냅니다. 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 다른 사업체의 규정은 없는 규정과 같은 404입니다. (번호가 있는지 알려주지 않으려고)
- `rules`는 저장 순서와 상관없이 `daysFrom`이 큰 순(공연에서 먼 구간부터)입니다. `daysTo`가 `null`이면 제한 없음, `cancelable`이 `false`면 그 구간에는 취소할 수 없습니다.
- `editable`은 목록(SLR-19)과 같은 기준으로 지금 수정할 수 있는지를 알려 줍니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "cancelPolicyId": 8,
    "name": "공연장 자체 규정",
    "isDefault": false,
    "editable": true,
    "rules": [
      { "daysFrom": 7, "daysTo": null, "feeRate": 0, "cancelable": true },
      { "daysFrom": 3, "daysTo": 6, "feeRate": 10, "cancelable": true },
      { "daysFrom": 1, "daysTo": 2, "feeRate": 20, "cancelable": true },
      { "daysFrom": 0, "daysTo": 0, "feeRate": 0, "cancelable": false }
    ]
  }
}
```

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `COMMON000` | 규정 번호가 숫자가 아님 |
| 404 | `PERFORMANCE100` | 규정 없음 또는 다른 사업체의 규정 (둘 다 같은 404) |

### SLR-21 취소 규정 생성 — `POST /api/v1/seller/cancel-policies` (구현됨)

요청의 `businessId`는 받지 않고 로그인 토큰의 사업체 번호를 사용합니다. 기본 규정을 참고해 프론트에서 구간을 채울 수 있지만, 새 규정은 별도 사업체 소유 데이터로 저장합니다.

- 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 구간은 **양끝 날짜를 포함**합니다. `3~6`은 공연 3·4·5·6일 전이고, `1~1`처럼 하루짜리 구간도 됩니다.
- 구간은 순서와 상관없이 보내도 됩니다. 응답은 SLR-20처럼 `daysFrom`이 큰 순입니다.
- 모든 구간을 취소 불가(`cancelable: false`)로 둔 규정도 저장됩니다. 수수료율끼리의 크기도 서버가 검사하지 않습니다. (판매자 판단이며, 필요하면 프론트가 경고합니다)
- 취소 불가 구간의 `feeRate`는 그대로 저장하고 수수료 계산에서는 쓰지 않습니다.

**요청**

```json
{
  "name": "공연장 자체 규정",
  "rules": [
    { "daysFrom": 7, "daysTo": null, "feeRate": 0, "cancelable": true },
    { "daysFrom": 3, "daysTo": 6, "feeRate": 10, "cancelable": true },
    { "daysFrom": 1, "daysTo": 2, "feeRate": 20, "cancelable": true },
    { "daysFrom": 0, "daysTo": 0, "feeRate": 0, "cancelable": false }
  ]
}
```

| 필드 | 규칙 |
| --- | --- |
| `name` | 필수, 공백만 불가, 앞뒤 공백을 지우고 저장, 50자 이하. 같은 사업체 안에서 대소문자를 무시하고 겹치면 안 되고, 기본 규정 이름(`기본 규정`)도 쓸 수 없음. 다른 사업체와는 겹쳐도 됨 |
| `rules` | 필수, 1~10개 |
| `rules[].daysFrom` | 필수, 0~90 |
| `rules[].daysTo` | 선택(`null`이면 제한 없음), 0~90 |
| `rules[].feeRate` | 필수, 0~100(%) |
| `rules[].cancelable` | 필수 |

**구간끼리의 규칙** (`daysFrom` 순으로 정렬해서 봄. 어기면 400 `PERFORMANCE101`)

1. 구간마다 `daysTo`는 `daysFrom`보다 작을 수 없음
2. 가장 가까운 구간은 0일 전(공연 당일)부터 시작
3. 제한 없음(`daysTo: null`) 구간은 하나이고 가장 먼 구간
4. 빈 날짜나 겹치는 날짜가 없음 (`다음 daysFrom = 앞 daysTo + 1`)

**응답 201**: SLR-20 상세 응답과 같은 형태

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `COMMON000` | 입력 규칙 위반(이름 누락·길이, 구간 개수, 일수·수수료율 범위, 빠진 값) |
| 400 | `PERFORMANCE101` | 구간끼리의 규칙 위반 (이유는 메시지로 구분) |
| 409 | `PERFORMANCE104` | 같은 사업체에 같은 이름의 규정이 있음, 또는 기본 규정과 같은 이름 |

**감사 로그**: `CANCEL_POLICY` / `CREATE`, `target_id`는 규정 번호. 변경 전 값은 없고 변경 후 `name`·`rules`입니다. 거절된 요청은 남기지 않습니다.

### SLR-22 취소 규정 수정 — `PUT /api/v1/seller/cancel-policies/{cancelPolicyId}` (구현됨)

요청은 SLR-21과 같은 필드와 규칙이며, 이름과 규정 구간을 전체 교체합니다. 로그인한 사업체 소유 규정만 수정할 수 있습니다. 기본 규정과, 공개(`PUBLISHED`)되었거나 취소(`CANCELED`)된 공연에 적용 중인 규정은 수정할 수 없습니다. 작성 중(`DRAFT`) 공연만 쓰는 규정은 수정할 수 있고, 그 공연들에는 바뀐 내용이 적용됩니다. 삭제 API는 이번 범위에서 제공하지 않습니다.

- 판매자(대표·담당자) 토큰만 쓸 수 있고 예매자·관리자 토큰은 403, 토큰이 없으면 401입니다.
- 취소된 공연이 쓰는 규정도 잠그는 이유: 판매자가 "그 공연에서 어떤 기준으로 수수료를 뗐는지" 볼 수 있는 곳은 공연의 규정뿐이라, 취소 뒤에 규정이 바뀌면 화면이 실제 기준과 달라지기 때문입니다. 잠긴 규정도 공연 등록(SLR-02)에서 계속 고를 수 있고, 내용을 바꾸려면 새 규정을 만듭니다.
- 이름은 자기 자신과는 겹쳐도 됩니다. (같은 이름으로 다시 보내거나 대소문자만 바꿔도 200)
- 같은 값으로 보내도 200입니다.

**응답 200**: SLR-20 상세 응답과 같은 형태 (`editable`은 `true`)

**에러**

| 상태 | 코드 | 경우 |
| --- | --- | --- |
| 400 | `COMMON000` | 입력 규칙 위반(SLR-21과 같음), 규정 번호가 숫자가 아님 |
| 400 | `PERFORMANCE101` | 구간끼리의 규칙 위반 (SLR-21과 같음) |
| 404 | `PERFORMANCE100` | 규정 없음 또는 다른 사업체의 규정 (둘 다 같은 404) |
| 409 | `PERFORMANCE102` | 서비스 기본 규정 |
| 409 | `PERFORMANCE103` | 공개되었거나 취소된 공연이 쓰는 규정 |
| 409 | `PERFORMANCE104` | 같은 사업체의 다른 규정이나 기본 규정과 같은 이름 |
| 409 | `COMMON004` | 같은 규정을 동시에 수정하거나 그 규정을 쓰는 공연을 공개하는 중이라 락(`cancel-policy:{규정 번호}`)을 1초 안에 잡지 못함 (잠시 후 다시 시도) |

**감사 로그**: `CANCEL_POLICY` / `UPDATE`, `target_id`는 규정 번호. 변경 전·후에 **바뀐 항목만**(`name`, `rules`) 담고, 구간이 하나라도 바뀌면 `rules`는 목록 전체입니다. 값이 그대로면(구간을 보낸 순서만 다른 경우 포함) 200이고 남기지 않습니다. 거절된 요청(기본 규정, 남의 규정, 잠긴 규정 등)도 남기지 않습니다. 에러 순서, 락, 응답은 그대로입니다.

## 5. 관리자 (`/api/v1/admin/**`)

### ADM-01 사업체 목록·검색 — `GET /api/v1/admin/seller-businesses` (구현됨)

Query

| 이름 | 설명 |
| --- | --- |
| `keyword` | 검색어. **상호·대표자명·사업자번호**에서 부분 일치로 찾고 영문은 대소문자를 가립니다. 비우면 전체. `%`, `_`는 글자 그대로 찾습니다 |
| `status` | `PENDING`(기본) / `APPROVED` / `REJECTED` / `SUSPENDED` / `ALL`(전체). 알 수 없는 값은 400 `COMMON000` |
| `page` | 0부터. 기본 0 |
| `size` | 기본 **10**, 최대 **20** (넘으면 20으로 줄어듦) |

정렬은 **최신 신청이 먼저**로 고정이고, 클라이언트가 보낸 `sort`는 무시합니다. 목록에는 고르는 데 필요한 값만 있고, 연락처·반려 사유·처리한 관리자 같은 값은 상세 조회(ADM-07)에서 줍니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "businessId": 3,
        "companyName": "(주)가상기획",
        "representativeName": "김대표",
        "businessNumber": "1234567890",
        "status": "PENDING",
        "createdAt": "2026-11-12T15:30:00",
        "reviewedAt": null
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 관리자가 아님 · 400 `COMMON000` 알 수 없는 상태 값

### ADM-07 사업체 상세 — `GET /api/v1/admin/seller-businesses/{businessId}` (구현됨)

승인·반려를 판단할 때 보는 화면입니다. 판매자 가입은 **사업체 신청과 대표 계정 가입이 한 번의 요청**이라서, `owner`는 신청과 함께 가입한 본인입니다. (승인되기 전에는 이 계정으로 로그인할 수 없습니다)

- `owner`에는 계정 번호·로그인 이메일·휴대폰·상태만 줍니다. 비밀번호는 주지 않습니다.
- 계정 이름은 `owner`에 두지 않습니다. 신청자는 `representativeName`으로 판단합니다.
- `contactPhone`(업무 연락처)과 `owner.phone`(대표 계정 휴대폰)은 다를 수 있어서 둘 다 줍니다.
- `reviewedBy`는 처리한 관리자 **번호**만 줍니다. 담당자(STAFF) 목록은 주지 않습니다.
- 탈퇴 처리된 대표 계정이면 `owner`가 `null`입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "businessId": 3,
    "companyName": "(주)가상기획",
    "representativeName": "김대표",
    "businessNumber": "1234567890",
    "contactPhone": "0212345678",
    "contactEmail": "contact@company.com",
    "status": "REJECTED",
    "rejectReason": "사업자등록번호 확인이 필요합니다.",
    "reviewedBy": 1,
    "reviewedAt": "2026-11-12T16:10:00",
    "termsAgreedAt": "2026-11-12T15:30:00",
    "createdAt": "2026-11-12T15:30:00",
    "owner": {
      "memberId": 5,
      "email": "owner@company.com",
      "phone": "01011112222",
      "status": "ACTIVE"
    }
  }
}
```

처리 전에는 `rejectReason`, `reviewedBy`, `reviewedAt`이 `null`입니다.

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 관리자가 아님 · 404 `SELLER006` 없는 사업체 · 400 `COMMON000` 번호가 숫자가 아님

### ADM-02 사업체 승인 — `POST /api/v1/admin/seller-businesses/{businessId}/approve` (구현됨)

Body 없음. 처리 관리자·시각을 기록하고 감사 로그를 남깁니다. 승인하면 대표 계정이 로그인할 수 있습니다.

- **승인 대기(`PENDING`)만 승인**할 수 있습니다. 이미 승인·반려·정지된 사업체는 409입니다.
- 상태 변경과 감사 로그는 **한 트랜잭션**입니다. 로그 기록이 실패하면 승인도 취소됩니다.
- 사업체 행을 잠그지 않습니다. 관리자가 몇 명 안 되고 같은 사업체를 같은 순간에 처리할 일이 드물기 때문입니다. (아래 "동시 처리" 참고)
- 감사 로그는 행위자 `ADMIN`, 대상 `SELLER_BUSINESS`, 행동 `APPROVE`, 변경 전 `{"status":"PENDING"}`, 변경 후 `{"status":"APPROVED"}`로 남깁니다. `business_id`는 비웁니다. 판매자 이력 화면에 관리자의 처리 로그가 섞이지 않게 하려는 것입니다.

**동시 처리**: 순서대로 들어온 요청은 상태 확인이 막습니다. 두 관리자가 **같은 순간에** 승인과 반려를 누르면 나중에 저장한 쪽의 상태가 남고 감사 로그가 둘 다 남을 수 있습니다. 관리자 수와 빈도를 보고 지금은 허용하며, 문제가 되면 `UPDATE ... WHERE status = 'PENDING'` 한 문장으로 바꿉니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "businessId": 3,
    "status": "APPROVED",
    "reviewedAt": "2026-11-12T16:10:00"
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 관리자가 아님 · 404 `SELLER006` 사업체 없음 · 409 `SELLER007` 이미 처리된 사업체 · 400 `COMMON000` 번호가 숫자가 아님

### ADM-03 사업체 반려 — `POST /api/v1/admin/seller-businesses/{businessId}/reject` (구현됨)

승인(ADM-02)과 같은 구조입니다. 처리 관리자·시각을 기록하고 감사 로그를 남기며, 상태 변경과 로그는 한 트랜잭션입니다.

- **승인 대기(`PENDING`)만 반려**할 수 있습니다. 이미 승인·반려·정지된 사업체는 409입니다. **이미 승인한 사업체를 반려로 되돌릴 수는 없습니다.** (운영 중 문제는 정지로 다룹니다)
- `reason`은 필수이고 공백뿐이면 안 되며 **최대 500자**입니다. 앞뒤 공백은 잘라서 저장하고, 응답의 `rejectReason`도 저장된 값입니다.
- **반려된 사업체는 같은 사업자번호로 다시 신청할 수 없습니다.** (재신청을 지원하지 않기로 했습니다. 가입의 사업자번호 중복 검사가 상태와 상관없이 막아서 `SELLER001`이 납니다) 반려된 사업체의 대표는 로그인할 때 `SELLER004`를 받고, 이의가 있으면 문의합니다. 문의 방법은 나중에 정합니다.
- 반려 사유는 관리자만 상세 조회(ADM-07)에서 볼 수 있습니다. 로그인이 막혀 있어서 판매자에게는 보이지 않습니다.
- 감사 로그는 행동 `REJECT`, 변경 전 `{"status":"PENDING"}`, 변경 후 `{"status":"REJECTED","rejectReason":"..."}`로 남깁니다. `business_id`는 비웁니다.
- 승인과 같이 행을 잠그지 않습니다. (ADM-02의 "동시 처리" 참고)

**요청**

```json
{ "reason": "사업자등록번호 확인이 필요합니다." }
```

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "businessId": 3,
    "status": "REJECTED",
    "rejectReason": "사업자등록번호 확인이 필요합니다.",
    "reviewedAt": "2026-11-12T16:10:00"
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 관리자가 아님 · 404 `SELLER006` 사업체 없음 · 409 `SELLER007` 이미 처리된 사업체 · 400 `COMMON000` 사유가 없거나 공백뿐이거나 500자를 넘음, 번호가 숫자가 아님

### ADM-04 공연장 생성 — `POST /api/v1/admin/venues`

공연장·구역·좌석을 한 트랜잭션으로 생성(`VenueGenerator`, 시드와 같은 JSON). `rows`는 세 형태: 한 줄(`label,from,to`) / 묶음(`rowRange,suffix,from,to`) / 문자 번호(`label,numbers`).

**요청**

```json
{
  "name": "가상 아레나 (샘플)",
  "address": "서울 (가상)",
  "region": "서울",
  "layoutKey": "sample-arena",
  "zones": [
    {
      "name": "F2구역",
      "floor": "1층",
      "rows": [
        {
          "rowRange": [1, 11],
          "suffix": "열",
          "from": 1,
          "to": 20
        }
      ]
    },
    {
      "name": "G구역",
      "floor": "2층",
      "rows": [
        {
          "label": "1열",
          "from": 1,
          "to": 2
        },
        {
          "label": "2열",
          "from": 1,
          "to": 4
        }
      ]
    },
    {
      "name": "Floor석",
      "floor": "1층",
      "rows": [
        {
          "label": "스탠딩",
          "from": 1,
          "to": 500
        }
      ]
    },
    {
      "name": "발코니석",
      "floor": "2층",
      "rows": [
        {
          "label": "발코니",
          "numbers": ["BALCONY-1", "BALCONY-2"]
        }
      ]
    }
  ]
}
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "venueId": 1,
    "name": "가상 아레나 (샘플)",
    "status": "ACTIVE",
    "totalSeats": 728,
    "zoneCount": 4
  }
}
```

**에러**(코드 미정): 400 구조 오류(`from > to`, 중복 등) · 409 같은 이름의 공연장

### ADM-05 환불 실패 목록 — `GET /api/v1/admin/refunds` (관리자, 구현됨)

Query: `status`(기본 `FAILED`), `exhaustedOnly`(기본 `true`, 재시도 3회 이상만), `page`, `size`(기본·최대 20)

- 아무 값도 주지 않으면 **자동 재시도를 다 쓰고도 실패한 환불**, 즉 사람이 처리해야 하는 것만 나옵니다. 스케줄러가 아직 다시 보내는 중인 건(1~2회)은 `exhaustedOnly=false`일 때만 보입니다.
- 기준 횟수(3회)는 재시도 스케줄러 설정(`app.payment.refund-retry.max-count`)을 그대로 씁니다. 설정을 바꾸면 목록 기준도 함께 바뀝니다.
- **오래 기다린 요청부터**(`requestedAt` 오름차순) 나옵니다. 정렬은 고정이고 `sort`는 무시합니다.
- `lastAttemptedAt`은 마지막으로 토스에 보낸 시각입니다. 토스 장애 시간과 맞춰 볼 때 씁니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "refundId": 288,
        "bookingId": 874,
        "bookingNo": "20261125QWRT91K",
        "paymentId": 702,
        "amount": 156000,
        "reasonType": "PERFORMANCE_CANCEL",
        "reasonDetail": "출연자 사정으로 회차 취소",
        "status": "FAILED",
        "retryCount": 3,
        "failReason": "PG 응답 시간 초과",
        "requestedAt": "2026-12-01T10:00:00",
        "lastAttemptedAt": "2026-12-01T10:15:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`reasonType`: `USER_CANCEL` / `PERFORMANCE_CANCEL` / `POSTPONE_CANCEL` / `SYSTEM` / `ADMIN`

**에러**: 400 `COMMON000` 모르는 `status` 값 · 401 `AUTH200` · 403 `AUTH100` 관리자가 아님

### ADM-06 환불 수동 재처리 — `POST /api/v1/admin/refunds/{refundId}/retry` (관리자, 구현됨)

Body 없음. 같은 `idempotency_key`로 토스 재요청(이중 환불 방지), 감사 로그.

- **실패(`FAILED`)한 환불만** 다시 보냅니다. 자동 재시도가 남은(3회 미만) 환불도 바로 보낼 수 있고, 스케줄러와 겹치면 한쪽만 보내고 나머지는 409 `PAYMENT009`입니다.
- 보낼 때마다 `retryCount`가 1 늘어납니다.
- **토스가 또 실패해도 200**이고 `status`가 `FAILED`, `failReason`에 토스의 사유가 담깁니다. 환불은 실패 목록(ADM-05)에 그대로 남습니다.
- **처음 요청한 지 15일이 지난 환불은 409 `PAYMENT010`**입니다. 토스 멱등키는 처음 요청한 날부터 15일만 유효해서, 그 뒤에는 같은 키도 새 환불로 처리될 수 있습니다(이중 환불 위험). 이런 환불은 토스에서 실제 환불 여부를 확인하고 토스 쪽에서 처리합니다.
- 감사 로그: `target_type=REFUND`, `action=RETRY`, 전후 값은 `status`·`retryCount`·`failReason`입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "refundId": 288,
    "status": "DONE",
    "retryCount": 4,
    "failReason": null,
    "completedAt": "2026-12-01T14:22:10"
  }
}
```

**에러**: 401 `AUTH200` · 403 `AUTH100` 관리자가 아님 · 404 `PAYMENT007` 없는 환불 · 409 `PAYMENT008` 실패가 아님(완료 등) · 409 `PAYMENT009` 처리 중(스케줄러나 다른 요청이 보내는 중) · 409 `PAYMENT010` 요청한 지 15일 지남

판정 순서는 없음(404) → 처리 중 → 실패가 아님 → 15일 지남입니다.

---

### ADM-08 전체 예매 목록 — `GET /api/v1/admin/bookings` (관리자, 구현됨)

Query: `title`, `status`(여러 개), `from`, `to`, `page`, `size` — BKG-04의 "예매 목록 검색 조건"과 같습니다.

- 모든 예매를 최신 예매순으로 줍니다. `status`를 비우면 만료(`EXPIRED`)를 포함한 모든 상태입니다.
- 항목: `bookingId`, `bookingNo`, `status`, `userId`, `ordererName`, `businessId`, `performanceId`, `performanceTitle`, `scheduleStartAt`, `seatCount`(처음 좌석 수), `totalAmount`(처음 결제 금액), `createdAt`
- 인덱스 `(created_at DESC, id DESC)`로 읽습니다. `size` 기본 20, 최대 20.

**에러**: 403 `AUTH100` 관리자가 아님 · 400 `COMMON000` 없는 `status` 값, `from`이 `to`보다 늦음

### ADM-09 예매 상세 — `GET /api/v1/admin/bookings/{bookingId}` (관리자, 구현됨)

- 예매 하나를 상태와 상관없이 줍니다. 응답은 `{ userId, businessId, booking }`이고 `booking`은 BKG-05 응답과 같은 모양(결제·환불·취소 규정 포함)입니다. 같은 내용을 두 벌로 만들면 한쪽만 고쳐질 수 있어서 그대로 씁니다.
- 없는 예매는 404 `BOOKING006`입니다.

### ADM-14 문의 목록 — `GET /api/v1/admin/inquiries` (관리자, 구현됨)

Query: `authorType`(`USER`/`SELLER`), `status`(`WAITING`/`ANSWERED`), `deleted`(기본 `false`), `page`, `size`

- 예매자·판매자 문의를 한 목록에서 최신 작성순으로 줍니다. `authorType`·`status`를 비우면 거르지 않습니다.
- **삭제된 문의는 기본으로 빠집니다.** `deleted=true`면 작성자가 삭제한 문의**만** 줍니다.
- 항목: `inquiryId`, `authorType`, `authorName`, `authorEmail`(작성 때 복사한 값), `category`, `categoryName`, `title`, `status`, `createdAt`, `answeredAt`, `deletedAt`
- 항목은 문의 행의 값만 써서 목록·개수 쿼리 두 번으로 끝납니다. `size` 기본 20, 최대 20.

**에러**: 403 `AUTH100` 관리자가 아님 · 400 `COMMON000` 없는 `authorType`·`status` 값

### ADM-15 문의 상세 — `GET /api/v1/admin/inquiries/{inquiryId}` (관리자, 구현됨)

- 문의 하나와 이력(`histories`, 남긴 순서대로)을 줍니다. **삭제된 문의도 볼 수 있습니다.** (분쟁 확인용)
- INQ-04 응답에 `authorType`, `authorId`, `businessId`, `authorName`, `authorEmail`, `answeredBy`, `deletedAt`, `histories`를 더했고, `category`는 `category`(코드)·`categoryName`으로 나눠 줍니다.
- 이력 한 줄: `action`(`CREATED`/`UPDATED`/`DELETED`/`ANSWERED`), `actorType`, `actorId`, `title`, `content`, `imageUrls`, `answer`, `createdAt` — 그 순간의 내용 그대로입니다.

```json
"histories": [
  { "action": "CREATED", "actorType": "USER", "actorId": 5, "title": "환불이 아직 들어오지 않았어요", "content": "…", "imageUrls": [], "answer": null, "createdAt": "2026-11-24T15:10:00" },
  { "action": "ANSWERED", "actorType": "ADMIN", "actorId": 1, "title": "환불이 아직 들어오지 않았어요", "content": "…", "imageUrls": [], "answer": "3~5영업일 안에 환불됩니다.", "createdAt": "2026-11-25T10:00:00" }
]
```

**에러**: 403 `AUTH100` · 404 `INQUIRY001` 없는 문의

### ADM-16 문의 답변 — `POST /api/v1/admin/inquiries/{inquiryId}/answer` (관리자, 구현됨)

```json
{ "answer": "3~5영업일 안에 환불됩니다." }
```

- `answer`: 필수, 최대 2,000자. 앞뒤 공백은 잘라 저장합니다.
- **답변 대기이고 삭제되지 않은 문의에만** 됩니다. 답변은 문의 하나에 하나이고 **고치거나 지울 수 없습니다.**
- 답변하면 `ANSWERED`가 되고 답변한 관리자·일시와 답변 이력(`ANSWERED`, 그때 문의 내용 + 답변)이 남습니다.
- 작성자의 수정·삭제(INQ-05·06)와 같은 상태 조건부 UPDATE라, 동시에 들어오면 **먼저 끝난 쪽만** 성공합니다. 관리자 여러 명이 동시에 답변해도 하나만 성공합니다.

**응답 200**: ADM-15와 같은 문의 상세(답변 반영)

**에러**: 400 `COMMON000` 답변 누락·2,000자 초과 · 403 `AUTH100` · 404 `INQUIRY001` 없는 문의, 삭제된 문의 · 409 `INQUIRY003` 이미 답변한 문의(동시에 다른 답변이 먼저 끝났거나 작성자가 먼저 지운 경우도)

## 6. 이미지 (`/api/v1/images/**`)

### IMG-01 이미지 업로드 — `POST /api/v1/images` (로그인, 구현됨)

사진 파일을 서버로 보내면 **서버가 S3에 올리고 공개 주소를 돌려줍니다.** 응답의 `imageUrl`을 공연 등록(SLR-02)·수정(SLR-03)·내 정보 수정(AUTH-07)의 이미지 주소로 보냅니다. (**올리기만 하고 주소를 등록하지 않으면 사진은 어디에도 연결되지 않습니다**)

**권한**: 경로가 `/api/v1/images`라 시큐리티는 "로그인한 누구나"로 통과시키고, **용도에 맞는 계정인지는 서비스가 확인합니다.** 관리자는 모든 용도에서 403입니다. 가입 화면은 로그인 전이라 이 API를 쓸 수 없습니다. 프로필 사진은 가입 뒤 내 정보 수정(AUTH-07)에서만 바꿉니다.

| `purpose` | 쓰는 곳 | 요청할 수 있는 계정 |
| --- | --- | --- |
| `POSTER` | 공연 포스터 | 판매자 (대표·STAFF) |
| `DETAIL` | 공연 상세 이미지 | 판매자 (대표·STAFF) |
| `PROFILE` | 예매자 프로필 사진 | 예매자 |
| `USER_INQUIRY` | 예매자 문의 첨부 | 예매자 |
| `SELLER_INQUIRY` | 판매자 문의 첨부 | 판매자 (대표·STAFF) |

- 문의 첨부는 예매자·판매자가 함께 쓰는 문의 기능의 사진입니다. 용도 하나에 계정 종류 하나라서 둘로 나눴습니다. (2026-11-24) 다른 용도처럼 공개 주소라 **주소를 아는 사람은 누구나 볼 수 있어서**, 화면에서 영수증·연락처 같은 개인정보가 담긴 사진을 올리지 않도록 안내합니다.

**요청** (`multipart/form-data`)

| 이름 | 종류 | 설명 |
| --- | --- | --- |
| `purpose` | 텍스트 | **필수**. `POSTER` / `DETAIL` / `PROFILE` / `USER_INQUIRY` / `SELLER_INQUIRY` |
| `file` | 파일 | **필수**. `image/jpeg` / `image/png` / `image/webp` 중 하나, 1바이트 이상 10MB 이하. (gif 등은 안 받음) |

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "imageUrl": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/public/poster/seller-7/3f2a9c1e-5b7d-4e0a-9d44-1c8f6a2b7e90.jpg"
  }
}
```

**저장 경로**: `public/{용도}/{계정}/{UUID}.{확장자}` (예: `public/poster/seller-7/3f2a….jpg`)

- 용도 폴더는 `poster` / `detail` / `profile` / `user_inquiry` / `seller_inquiry`, 계정 폴더는 `seller-{계정 번호}` / `user-{회원 번호}`입니다. 번호는 토큰에서 읽고 요청으로 받지 않습니다.
- 이름은 서버가 UUID로 만듭니다. 원본 파일명을 쓰지 않아서 한글·공백·특수문자 문제와 같은 이름 덮어쓰기가 없습니다. 확장자는 파일 형식에서 정합니다. (jpeg → `jpg`)
- 같은 파일을 다시 올리면 **매번 다른 주소**가 나옵니다. 주소의 내용은 바뀌지 않아서 브라우저가 1년 동안 캐시하도록 `Cache-Control`을 붙여 올립니다.

**등록 API에 걸리는 이미지 주소 규칙 (바뀜)**: AUTH-07 `profileImg`, SLR-02·SLR-03 `posterUrl`·`images[].url`의 주소 규칙이 "`http://` 또는 `https://`로 시작"에서 **"`IMAGE_BASE_URL` + `/`로 시작하는 https 주소"**로 좁아졌습니다. 외부 사이트 주소·http·공백이 든 주소는 `400 COMMON000`입니다. (`@ImageUrl` 검증)

**에러**
- 400 `COMMON000`: `purpose`가 없거나 모르는 값 · 파일이 없거나 비어 있음 · 허용하지 않는 형식(대소문자도 구분) · 파일이 10MB 초과. `message`로 사유를 알려 줍니다.
- 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 용도에 맞지 않는 계정(예: 예매자가 `POSTER`·`SELLER_INQUIRY`, 판매자가 `USER_INQUIRY`, 관리자)
- 500 `COMMON999`: S3에 올리지 못함. 원인은 서버 로그에만 남습니다.
- 판정 순서는 `purpose` 값(400) → 용도에 맞는 계정(403) → 파일(400)입니다.

**파일 크기**: 10MB를 넘으면 400입니다. 요청 전체가 11MB를 넘으면 서버가 JSON을 돌려주지 않고 **연결을 끊으므로**, 화면에서 올리기 전에 10MB 이하인지 확인해 주세요. (2026-11-18 실제 서버로 확인: 10MB는 통과, 10MB+1바이트는 400, 12MB는 연결 끊김)

**운영 정보**
- 서버가 쓰는 `.env` 키는 모두 필수입니다: `S3_BUCKET`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `IMAGE_BASE_URL`(**버킷 루트 주소** `https://{버킷}.s3.{리전}.amazonaws.com`. 저장 경로가 `public/`으로 시작하므로 `/public`을 붙이면 `imageUrl`이 `public/public/…`이 되어 열리지 않음). 앱을 시작할 때 AWS를 부르지 않아서, 사진을 실제로 올려 보지 않는 사람은 앱이 뜨도록 값만 채워 두면 됩니다. 올려 보려면 진짜 키가 필요합니다. (Mock 구현은 없음)
- 올리기는 서버를 거치므로 요청 한 번이 파일 크기만큼 서버 메모리를 씁니다. (최대 10MB)
- 올리고 등록하지 않은 파일은 버킷에 남습니다. (정리 방법은 아래 "확정이 필요한 것")

---

## 7. 문의 (`/api/v1/inquiries/**`, 예매자)

설계(테이블, 유형, 이력, PR 순서)는 `doc/inquiry-design.md`에 있습니다. 예매자만 쓸 수 있고(판매자·관리자 토큰은 403 `AUTH100`), 남의 문의는 없는 문의와 같은 404입니다. 관리자 답변은 5장(ADM-14~16), 판매자 문의는 7-1장(SLR-34~37)입니다.

### INQ-01 문의 유형 목록 — `GET /api/v1/inquiries/categories` (예매자, 구현됨)

예매자가 고를 수 있는 유형만 줍니다. (판매자 유형은 나오지 않음) 화면은 이 목록으로 유형 드롭다운을 그립니다. **값은 임시**입니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": [
    { "code": "BOOKING", "name": "예매" },
    { "code": "CANCEL_REFUND", "name": "취소·환불" },
    { "code": "PAYMENT", "name": "결제" },
    { "code": "DELIVERY", "name": "티켓 배송·수령" },
    { "code": "ACCOUNT", "name": "회원·계정" },
    { "code": "ETC", "name": "기타" }
  ]
}
```

### INQ-02 문의 작성 — `POST /api/v1/inquiries` (예매자, 구현됨)

**요청**

```json
{
  "category": "CANCEL_REFUND",
  "bookingId": 1001,
  "title": "환불이 아직 들어오지 않았어요",
  "content": "11/27 에 취소했는데 아직 환불되지 않았습니다.",
  "images": [
    { "url": "https://{버킷 주소}/public/user_inquiry/user-5/3f2a9c1e.jpg" }
  ]
}
```

| 필드 | 규칙 |
| --- | --- |
| `category` | 필수. INQ-01의 값만 (판매자 유형이면 400 `INQUIRY000`) |
| `bookingId` | 선택. 고르면 **내 예매**여야 함 (남의·없는 예매는 404 `BOOKING006`). 예매 상태는 따지지 않음 |
| `title` | 필수, 100자 이하, 앞뒤 공백 제거 (임시) |
| `content` | 필수, 2,000자 이하, 앞뒤 공백 제거 (임시) |
| `images` | 선택, 최대 3장(임시). `url`은 이미지 업로드(IMG-01, `purpose=USER_INQUIRY`)의 `imageUrl` 값만 (우리 저장소 주소가 아니면 400 `COMMON000`). 보낸 순서대로 저장 |

- 답변 대기(`WAITING`)로 만들고, 작성 이력(`CREATED`, 그때 내용 그대로)을 함께 남깁니다.
- 작성자 이름·이메일은 회원 정보에서 복사해 둡니다. 탈퇴한 회원은 404 `USER002`입니다.
- 첨부 사진은 공개 주소라 화면에서 개인정보가 담긴 사진을 올리지 않도록 안내해 주세요.

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": { "inquiryId": 41, "status": "WAITING", "createdAt": "2026-11-24T15:10:00" }
}
```

**에러**: 400 `COMMON000` 형식 오류(필수 값 누락, 길이 초과, 사진 4장 이상, 외부 사진 주소) · 400 `INQUIRY000` 고를 수 없는 유형 · 401 `AUTH200` · 403 `AUTH100` 예매자가 아님 · 404 `BOOKING006` 남의·없는 예매 · 404 `USER002` 탈퇴한 회원

### INQ-03 내 문의 목록 — `GET /api/v1/inquiries` (예매자, 구현됨)

Query: `page`, `size` (기본·최대 20). 최신 작성순 고정이고 삭제한 문의는 나오지 않습니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "content": [
      {
        "inquiryId": 41,
        "category": { "code": "CANCEL_REFUND", "name": "취소·환불" },
        "title": "환불이 아직 들어오지 않았어요",
        "status": "WAITING",
        "bookingNo": "20261125KCZB03R",
        "createdAt": "2026-11-24T15:10:00",
        "answeredAt": null
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

- `bookingNo`는 예매를 고르지 않았으면 `null`, `answeredAt`은 답변 전이면 `null`입니다.

### INQ-04 내 문의 상세 — `GET /api/v1/inquiries/{inquiryId}` (예매자, 구현됨)

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "inquiryId": 41,
    "category": { "code": "CANCEL_REFUND", "name": "취소·환불" },
    "title": "환불이 아직 들어오지 않았어요",
    "content": "11/27 에 취소했는데 아직 환불되지 않았습니다.",
    "imageUrls": ["https://{버킷 주소}/public/user_inquiry/user-5/3f2a9c1e.jpg"],
    "status": "WAITING",
    "booking": { "bookingId": 1001, "bookingNo": "20261125KCZB03R", "performanceTitle": "가상 콘서트" },
    "answer": null,
    "answeredAt": null,
    "createdAt": "2026-11-24T15:10:00",
    "updatedAt": "2026-11-24T15:10:00"
  }
}
```

- `booking`은 예매를 고르지 않았으면 `null`이고, 공연 제목은 지금 값입니다. `imageUrls`는 사진이 없으면 빈 배열입니다.

**에러**: 401 `AUTH200` · 403 `AUTH100` · 404 `INQUIRY001` 없는 문의, 남의 문의, 삭제한 문의

### INQ-05 문의 수정 — `PUT /api/v1/inquiries/{inquiryId}` (예매자, 구현됨)

**답변 대기(`WAITING`)일 때만** 됩니다. 작성(INQ-02)과 같은 본문을 **통째로** 다시 보냅니다. 보내지 않은 사진은 지워지고, `bookingId`를 비우면 예매 연결이 풀립니다. 필드 규칙은 INQ-02와 같습니다.

**요청**

```json
{
  "category": "CANCEL_REFUND",
  "bookingId": 1001,
  "title": "환불이 아직 들어오지 않았어요",
  "content": "11/27 에 취소했는데 아직 환불되지 않았습니다. 카드사에도 확인했어요.",
  "images": [
    { "url": "https://{버킷 주소}/public/user_inquiry/user-5/3f2a9c1e.jpg" }
  ]
}
```

- 수정 이력(`UPDATED`, 바뀐 내용 그대로)을 남깁니다. 작성 이력은 처음 내용 그대로 남습니다.
- 수정과 관리자 답변이 동시에 들어오면 **먼저 끝난 쪽만** 성공하고, 수정이 늦으면 409 `INQUIRY002`입니다. (상태 조건부 UPDATE)

**응답 200**: INQ-04와 같은 문의 상세(바뀐 내용)

**에러**: 400 `COMMON000` 형식 오류 · 400 `INQUIRY000` 고를 수 없는 유형 · 401 `AUTH200` · 403 `AUTH100` · 404 `INQUIRY001` 없는·남의·삭제한 문의 · 404 `BOOKING006` 남의·없는 예매 · 409 `INQUIRY002` 답변이 끝난 문의

### INQ-06 문의 삭제 — `DELETE /api/v1/inquiries/{inquiryId}` (예매자, 구현됨)

**답변 대기일 때만** 됩니다. 행은 지우지 않고 `deleted_at`만 남겨서(소프트 삭제) 목록·상세에서 사라지고, 지우기 직전 내용을 삭제 이력(`DELETED`)으로 남깁니다. 같은 문의를 동시에 여러 번 지워도 한 번만 성공합니다.

**응답 200**

```json
{ "success": true, "code": "0000", "message": "", "data": null }
```

**에러**: 401 `AUTH200` · 403 `AUTH100` · 404 `INQUIRY001` 없는·남의·이미 삭제한 문의 · 409 `INQUIRY002` 답변이 끝난 문의(동시에 지운 요청이 먼저 끝난 경우도)

## 7-1. 판매자 문의 (`/api/v1/seller/inquiries/**`, 판매자)

판매자(대표·담당자)만 쓸 수 있습니다. (예매자·관리자 토큰은 403 `AUTH100`) 사업체 번호와 작성자는 세션에서 꺼내고 요청으로 받지 않습니다. **같은 사업체의 문의는 대표·담당자 누가 썼든 함께 봅니다.** 다른 사업체의 문의는 없는 문의와 같은 404입니다. 수정·삭제(작성자 본인만)는 다음 PR입니다.

### SLR-34 문의 유형 목록 — `GET /api/v1/seller/inquiries/categories` (판매자, 구현됨)

판매자가 고를 수 있는 유형만 줍니다. 응답 모양은 INQ-01과 같습니다. **값은 임시**입니다.

```json
"data": [
  { "code": "PERFORMANCE", "name": "공연 등록·수정" },
  { "code": "SETTLEMENT", "name": "정산" },
  { "code": "BUSINESS", "name": "사업체·계정" },
  { "code": "ETC", "name": "기타" }
]
```

### SLR-35 문의 작성 — `POST /api/v1/seller/inquiries` (판매자, 구현됨)

**요청**

```json
{
  "category": "SETTLEMENT",
  "title": "정산 금액이 예상과 달라요",
  "content": "11월 공연 정산 금액이 판매 현황과 다릅니다.",
  "images": [
    { "url": "https://{버킷 주소}/public/seller_inquiry/seller-3/3f2a9c1e.jpg" }
  ]
}
```

| 필드 | 규칙 |
| --- | --- |
| `category` | 필수. SLR-34의 값만 (예매자 유형이면 400 `INQUIRY000`) |
| `title` | 필수, 100자 이하, 앞뒤 공백 제거 (임시) |
| `content` | 필수, 2,000자 이하, 앞뒤 공백 제거 (임시) |
| `images` | 선택, 최대 3장(임시). `url`은 이미지 업로드(IMG-01, `purpose=SELLER_INQUIRY`)의 `imageUrl` 값만 (우리 저장소 주소가 아니면 400 `COMMON000`). 보낸 순서대로 저장 |

- 예매는 고르지 않습니다. 답변 대기(`WAITING`)로 만들고 작성 이력(`CREATED`)을 함께 남깁니다.
- 작성자 이름·이메일은 판매자 계정에서 복사해 두고, 사업체 번호를 함께 저장해 같은 사업체가 봅니다.

**응답 201**: INQ-02와 같음 (`inquiryId`, `status`, `createdAt`)

**에러**: 400 `COMMON000` 형식 오류 · 400 `INQUIRY000` 고를 수 없는 유형 · 401 `AUTH200` · 403 `AUTH100` 판매자가 아님 · 404 `SELLER009` 삭제된 계정

### SLR-36 사업체 문의 목록 — `GET /api/v1/seller/inquiries` (판매자, 구현됨)

Query: `page`, `size` (기본·최대 20). 우리 사업체의 문의를 최신 작성순으로 줍니다. 삭제한 문의는 나오지 않습니다.

```json
"content": [
  {
    "inquiryId": 52,
    "category": { "code": "SETTLEMENT", "name": "정산" },
    "title": "정산 금액이 예상과 달라요",
    "status": "WAITING",
    "authorId": 3,
    "authorName": "김대표",
    "createdAt": "2026-11-28T10:00:00",
    "answeredAt": null
  }
]
```

- `authorName`은 작성 때 복사한 이름입니다. 항목은 문의 행의 값만 써서 목록·개수 쿼리 두 번으로 끝납니다.

### SLR-37 사업체 문의 상세 — `GET /api/v1/seller/inquiries/{inquiryId}` (판매자, 구현됨)

```json
"data": {
  "inquiryId": 52,
  "category": { "code": "SETTLEMENT", "name": "정산" },
  "title": "정산 금액이 예상과 달라요",
  "content": "11월 공연 정산 금액이 판매 현황과 다릅니다.",
  "imageUrls": ["https://{버킷 주소}/public/seller_inquiry/seller-3/3f2a9c1e.jpg"],
  "status": "ANSWERED",
  "authorId": 3,
  "authorName": "김대표",
  "answer": "정산 내역을 확인해 다시 안내드리겠습니다.",
  "answeredAt": "2026-11-28T14:00:00",
  "createdAt": "2026-11-28T10:00:00",
  "updatedAt": "2026-11-28T14:00:00"
}
```

**에러**: 401 `AUTH200` · 403 `AUTH100` · 404 `INQUIRY001` 없는·다른 사업체의·삭제한 문의

---

## 확정이 필요한 것

1. 미구현 API의 에러 코드(접두사·번호)
2. ~~페이징 응답 구조~~ → `content/page/size/totalElements/totalPages`로 확정 (`PageResponse`)
3. ~~공개 조회 API의 `permitAll` 경로~~ → `SecurityConfig`에 반영됨 (위 권한 표 참고)
4. 로그아웃 경로 (`/api/v1/auth/**`가 permitAll)
5. ~~관리자 로그인 경로~~ → `POST /api/v1/auth/admins/login` 구현됨. 판매자·관리자 로그아웃은 미정
6. ~~공연 등록(DRAFT) 때 판매 정책이 비어도 되는지~~ → 확정: 판매 정책 5개 컬럼은 DRAFT에서 NULL 허용, PUBLISHED 전환 시 서비스에서 필수값 검증
7. ~~이미지는 주소 문자열만 받음 — 업로드 방식 미정~~ → 서버가 파일을 받아 S3에 올리는 방식으로 확정 (IMG-01). 등록 API는 여전히 주소 문자열만 받되 우리 버킷 주소만 허용. (처음에는 브라우저가 S3로 바로 올리는 임시 주소(presigned URL) 방식으로 만들었다가, 업로드가 드물어 단순한 서버 경유로 바꿈)
8. 환불 `reasonType`에 `SYSTEM` 포함 여부 (4부 문서는 `SYSTEM` 포함, 기능 정의서에는 없음) → `project-log.md`에서 `SYSTEM` 포함으로 결정되어 있음
9. ~~올린 파일의 실제 크기 확인~~ → 서버가 파일을 직접 받으므로 서버가 크기(10MB)·형식을 확인함. 큰 파일이 서버 메모리를 쓰는 부담은 업로드가 드물어서 받아들임
10. 올리고 등록하지 않은 파일 정리 (버킷 수명 주기 규칙 등) — 지금은 정리하지 않음
11. 이미 저장된 데이터의 옛 이미지 주소(시드의 `public/파일명`처럼 경로 규칙이 없는 것)는 새 경로 규칙을 따르지 않지만 같은 버킷이라 주소 규칙 검증은 통과함
12. 업로드 남용 방지(요청 횟수 제한)는 매크로 방어 작업에서 함께 정함. 지금은 로그인한 누구나 올릴 수 있고 횟수 제한이 없음
