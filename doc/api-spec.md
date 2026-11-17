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
| 예매자 | `/api/v1/users/**`, `/api/v1/bookings/**`, `/api/v1/payments/**`, `/api/v1/schedules/*/bookings` | 예매자만 (판매자·관리자 토큰은 403) |
| 판매자 | `/api/v1/seller/**` | 판매자 (대표·STAFF). 대표만 가능한 기능은 메서드에 `@PreAuthorize("hasRole('SELLER_OWNER')")` |
| 관리자 | `/api/v1/admin/**` | 관리자 |
| 그 밖 | 위에 없는 경로 | 로그인 (계정 종류는 가리지 않음) |

- **권한은 두 단계**입니다. ① 계정 종류·역할은 시큐리티가 토큰으로 검사하고(위 표), ② "이 데이터가 내 것인가"는 서비스가 토큰의 번호(`account.getId()`, `account.getBusinessId()`)로 조회해서 확인합니다. 남의 것은 403이 아니라 **404**로 답합니다.
- 토큰의 역할은 발급 시점 값이라, 계정이 비활성화돼도 액세스 토큰이 만료될 때(15분)까지는 통합니다. (재발급은 DB 상태를 다시 읽습니다)
- 새 API를 만들면 위 표의 어느 줄에 들어가는지 확인하고, 공개 조회나 예매자 전용이면 `SecurityConfig`의 `PUBLIC_READ_PATHS`, `USER_ONLY_PATHS`에 경로를 추가합니다.

- 인증 헤더: `Authorization: Bearer {accessToken}`
- 액세스 토큰 900초, 리프레시 토큰 14일(HttpOnly 쿠키 `refreshToken`, `Path=/api/v1/auth`, `SameSite=Lax`)
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
| 500 | `COMMON999` | 서버 오류 |
| 401 | `AUTH000` / `AUTH200` / `AUTH201` / `AUTH202` | 인증 실패 / 토큰 없음 / 토큰 무효 / 토큰 만료 |
| 403 | `AUTH100` | 권한 없음 |

미구현 API의 에러는 **코드 미정**으로 HTTP 상태와 상황만 적었습니다.

## 목록

| ID | 이름 | 메서드 · URL | 상태 |
| --- | --- | --- | --- |
| AUTH-01 | 약관 조회 | GET `/api/v1/terms` | 구현됨 |
| AUTH-02 | 예매자 가입 | POST `/api/v1/auth/users/signup` | 구현됨 |
| AUTH-03 | 예매자 로그인 | POST `/api/v1/auth/users/login` | 구현됨 |
| AUTH-04 | 로그아웃 | POST `/api/v1/auth/logout` | 미구현 |
| AUTH-05 | 토큰 재발급 | POST `/api/v1/auth/refresh` | 구현됨 |
| AUTH-06 | 내 정보 조회 | GET `/api/v1/users/me` | 구현됨 |
| AUTH-07 | 내 정보 수정 | PATCH `/api/v1/users/me` | 구현됨 |
| AUTH-08 | 회원 탈퇴 | DELETE `/api/v1/users/me` | 미구현 |
| AUTH-09 | 판매자 가입 신청 | POST `/api/v1/auth/sellers/signup` | 구현됨 |
| AUTH-10 | 판매자 로그인 | POST `/api/v1/auth/sellers/login` | 구현됨 |
| AUTH-11 | 관리자 로그인 | POST `/api/v1/auth/admins/login` | 구현됨 |
| AUTH-12 | 예매자 로그인 (v2 세션) | POST `/api/v2/auth/users/login` | 구현됨 |
| AUTH-13 | 판매자 로그인 (v2 세션) | POST `/api/v2/auth/sellers/login` | 구현됨 |
| AUTH-14 | 관리자 로그인 (v2 세션) | POST `/api/v2/auth/admins/login` | 구현됨 |
| AUTH-15 | 로그아웃 (v2 세션) | POST `/api/v2/auth/logout` | 구현됨 |
| PFM-01 | 공연 목록·검색 | GET `/api/v1/performances` | 구현됨 |
| AUTH-16 | 예매자 가입 (v2 세션) | POST `/api/v2/auth/users/signup` | 구현됨 |
| PFM-02 | 공연 상세 | GET `/api/v1/performances/{performanceId}` | 구현됨 |
| PFM-03 | 구역 요약 | GET `/api/v1/schedules/{scheduleId}/zones` | 구현됨 |
| PFM-04 | 구역 좌석 상세 | GET `/api/v1/schedules/{scheduleId}/zones/{zoneId}/seats` | 미구현 |
| BKG-01 | 좌석 점유·예매 생성 | POST `/api/v1/schedules/{scheduleId}/bookings` | 구현됨 |
| BKG-02 | 결제 준비 | POST `/api/v1/bookings/{bookingId}/payments` | 미구현 |
| BKG-03 | 결제 승인 | POST `/api/v1/payments/confirm` | 미구현 |
| BKG-04 | 내 예매 목록 | GET `/api/v1/bookings` | 미구현 |
| BKG-05 | 내 예매 상세 | GET `/api/v1/bookings/{bookingId}` | 미구현 |
| BKG-06 | 예매 취소 | POST `/api/v1/bookings/{bookingId}/cancel` | 미구현 |
| SLR-01 | 공연장 목록 | GET `/api/v1/seller/venues` | 구현됨 |
| SLR-02 | 공연 등록 | POST `/api/v1/seller/performances` | 구현됨 |
| SLR-03 | 공연 수정 | PUT `/api/v1/seller/performances/{performanceId}` | 미구현 |
| SLR-04 | 공연 공개 | POST `/api/v1/seller/performances/{performanceId}/publish` | 미구현 |
| SLR-05 | 내 공연 목록 | GET `/api/v1/seller/performances` | 구현됨 |
| SLR-06 | 내 공연 상세 | GET `/api/v1/seller/performances/{performanceId}` | 구현됨 |
| SLR-08 | 등급·구역 조회 | GET `/api/v1/seller/performances/{performanceId}/grades` | 구현됨 |
| SLR-09 | 등급 추가 | POST `/api/v1/seller/performances/{performanceId}/grades` | 미구현 |
| SLR-10 | 등급 수정 | PUT `/api/v1/seller/performances/{performanceId}/grades/{gradeId}` | 미구현 |
| SLR-11 | 구역별 상태·등급 설정 | PATCH `/api/v1/seller/performances/{performanceId}/zone-grades` | 미구현 |
| SLR-12 | 회차 추가 | POST `/api/v1/seller/performances/{performanceId}/schedules` | 미구현 |
| SLR-13 | 회차 수정 | PUT `/api/v1/seller/schedules/{scheduleId}` | 미구현 |
| SLR-14 | 판매 좌석 생성 | POST `/api/v1/seller/schedules/{scheduleId}/seats/generate` | 미구현 |
| SLR-15 | 회차 취소 | POST `/api/v1/seller/schedules/{scheduleId}/cancel` | 미구현 |
| SLR-16 | 공연 취소 | POST `/api/v1/seller/performances/{performanceId}/cancel` | 미구현 |
| SLR-17 | 판매 현황 | GET `/api/v1/seller/performances/{performanceId}/sales` | 미구현 |
| SLR-18 | 회차별 예매 목록 | GET `/api/v1/seller/schedules/{scheduleId}/bookings` | 미구현 |
| SLR-19 | 취소 규정 목록 | GET `/api/v1/seller/cancel-policies` | 구현됨 |
| SLR-20 | 취소 규정 상세 | GET `/api/v1/seller/cancel-policies/{cancelPolicyId}` | 구현됨 |
| SLR-21 | 취소 규정 생성 | POST `/api/v1/seller/cancel-policies` | 구현됨 |
| SLR-22 | 취소 규정 수정 | PUT `/api/v1/seller/cancel-policies/{cancelPolicyId}` | 구현됨 |
| ADM-01 | 사업체 목록·검색 | GET `/api/v1/admin/seller-businesses` | 구현됨 |
| ADM-02 | 사업체 승인 | POST `/api/v1/admin/seller-businesses/{businessId}/approve` | 구현됨 |
| ADM-03 | 사업체 반려 | POST `/api/v1/admin/seller-businesses/{businessId}/reject` | 구현됨 |
| ADM-04 | 공연장 생성 | POST `/api/v1/admin/venues` | 미구현 |
| ADM-05 | 환불 실패 목록 | GET `/api/v1/admin/refunds` | 미구현 |
| ADM-06 | 환불 수동 재처리 | POST `/api/v1/admin/refunds/{refundId}/retry` | 미구현 |
| ADM-07 | 사업체 상세 | GET `/api/v1/admin/seller-businesses/{businessId}` | 구현됨 |

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

### AUTH-02 예매자 가입 — `POST /api/v1/auth/users/signup` (공개, 구현됨)

가입하면 바로 로그인됩니다. 액세스 토큰은 본문, 리프레시 토큰은 `Set-Cookie`.

**요청**

```json
{
  "email": "user@example.com",
  "password": "password1!",
  "nickname": "홍길동",
  "identityToken": "mock|홍길동|ci-hong-001|01012345678|19900101",
  "agreedTermIds": [1, 2]
}
```

| 필드 | 규칙 |
| --- | --- |
| `email` | 이메일 형식, 최대 255자, 앞뒤 공백 제거, 대소문자 구분 없음 |
| `password` | 8~25자, 영문·숫자·특수문자 포함 |
| `nickname` | 2~20자 |
| `identityToken` | 본인인증 토큰(Mock: `mock|이름|CI|휴대폰|생년월일yyyyMMdd`) |
| `agreedTermIds` | 최신 필수 약관을 모두 포함 |

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access.sample",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "accountType": "USER",
    "role": null
  }
}
```

**에러**: 409 `USER000` 이메일 중복 · 409 `USER001` 이미 가입된 본인(CI) · 400 `TERMS000` 필수 약관 미동의 · 400 `TERMS001` 없는 약관 · 400 `TERMS002` 개정 전 약관 · 400 `AUTH300` 본인인증 실패 · 400 `COMMON000`

### AUTH-03 예매자 로그인 — `POST /api/v1/auth/users/login` (공개, 구현됨)

**요청**

```json
{ "email": "user@example.com", "password": "password1!" }
```

**응답 200** (`Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=1209600`)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access.sample",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "accountType": "USER",
    "role": null
  }
}
```

**에러**: 401 `AUTH001` 이메일·비밀번호 불일치 · 401 `AUTH003` 비활성 계정 · 400 `COMMON000`

### AUTH-04 로그아웃 — `POST /api/v1/auth/logout` (로그인, 미구현)

Body 없음. 액세스 토큰 `jti`를 Redis 블랙리스트에 올리고 리프레시 토큰·쿠키를 지움.
※ 현재 `/api/v1/auth/**`가 permitAll이라 로그아웃 경로는 `SecurityConfig` 조정이 필요합니다.

**응답 200**

```json
{  "success": true,  "code": "0000",  "message": "",  "data": null}
```

### AUTH-05 토큰 재발급 — `POST /api/v1/auth/refresh` (공개, 구현됨)

예매자·판매자·관리자 공통. Body 없음, 쿠키의 `refreshToken` 사용. **리프레시 토큰도 새로 발급**되어 `Set-Cookie`로 내려갑니다.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.new-access.sample",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "accountType": "USER",
    "role": null
  }
}
```

**에러**: 401 `AUTH200` 쿠키 없음 · 401 `AUTH201` 토큰 무효 · 401 `AUTH202` 토큰 만료 (그 외 계정 상태 에러는 코드 미정)

### AUTH-06 내 정보 조회 — `GET /api/v1/users/me` (예매자, 구현됨)

회원 번호는 경로로 받지 않고 토큰에서 꺼냅니다. 비밀번호와 본인인증 값(`ci_hash`)은 내려가지 않습니다. 정지된 회원도 토큰이 살아 있는 동안은 조회할 수 있습니다. (로그인·재발급에서 이미 막힘)

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
    "birthDate": "1990-01-01",
    "createdAt": "2026-11-20T14:03:21"
  }
}
```

**에러**: 401 `AUTH200` 토큰 없음 · 403 `AUTH100` 예매자가 아님 · 404 `USER002` 회원 없음(탈퇴 후 토큰이 만료되기 전)

### AUTH-07 내 정보 수정 — `PATCH /api/v1/users/me` (예매자, 구현됨)

닉네임·프로필 이미지를 **보낸 값으로 통째로 바꿉니다.** 바꿀 수 있는 값은 이 둘뿐입니다. (이름·휴대폰·생년월일은 본인인증 값이라 수정 대상이 아님) **화면은 현재 값을 채운 수정 폼을 그대로 보냅니다.** 닉네임만 바꿀 때도 기존 `profileImg`를 함께 보내야 사진이 유지됩니다.

- `nickname`: **필수**. 2~20자, 공백만으로는 불가, 앞뒤 공백은 지우고 저장 (가입과 같은 규칙)
- `profileImg`: 선택. `http://` 또는 `https://`로 시작하는 주소, 2048자 이하. **생략하거나 `null`이면 사진이 지워집니다.** (빈 문자열 `""`은 400)
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

### AUTH-08 회원 탈퇴 — `DELETE /api/v1/users/me` (예매자, 미구현)

`deleted_at` 기록, 개인정보 비식별 처리, `ci_hash` 비움, 토큰 폐기.

**응답 200**

```json
{  "success": true,  "code": "0000",  "message": "",  "data": null}
```

### AUTH-09 판매자 가입 신청 — `POST /api/v1/auth/sellers/signup` (공개, 구현됨)

사업체(승인 대기) + 대표자 계정 생성. 승인 전에는 로그인 불가.

**요청**

```json
{
  "email": "owner@company.com",
  "password": "password1!",
  "identityToken": "mock|김대표|ci-owner-001|01099998888|19800101",
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
| `representativeName` | 최대 50자, 본인인증 이름과 같아야 함 |
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

**에러**: 409 `SELLER000` 이메일 중복 · 409 `SELLER001` 사업자번호 중복(반려된 사업체의 번호도 포함, 재신청 불가) · 400 `SELLER002` 대표자명 불일치 · 400 `AUTH300` 본인인증 실패 · 400 `COMMON000`

### AUTH-10 판매자 로그인 — `POST /api/v1/auth/sellers/login` (공개, 구현됨)

**요청**

```json
{ "email": "owner@company.com", "password": "password1!" }
```

**응답 200** (`Set-Cookie: refreshToken=...`)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access.sample",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "accountType": "SELLER",
    "role": "OWNER"
  }
}
```

**에러**: 401 `AUTH001` 불일치 · 401 `AUTH003` 비활성 계정 · 403 `SELLER003` 승인 대기 · 403 `SELLER004` 반려 · 403 `SELLER005` 정지

### AUTH-11 관리자 로그인 — `POST /api/v1/auth/admins/login` (공개, 구현됨)

관리자는 가입과 비밀번호 변경이 없습니다. 로컬에서는 `db/seed/V103`의 시드 계정(`admin@tickting.local`)으로 로그인하고, **비밀번호는 저장소에 없어서 팀에서 따로 전달**합니다. 운영 관리자 계정은 배포 때 따로 만듭니다.

**요청**

```json
{ "email": "admin@tickting.local", "password": "시드 계정의 비밀번호" }
```

**응답 200** (`Set-Cookie: refreshToken=...`)

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.access.sample",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "accountType": "ADMIN",
    "role": null
  }
}
```

**에러**: 401 `AUTH001` 불일치 · 401 `AUTH003` 비활성 계정

> 로그인 계정 정보 조회(`GET /api/v1/me`)는 삭제했습니다. 계정 종류·역할은 로그인·재발급 응답에 이미 들어 있고, 사업체 번호는 프론트가 액세스 토큰(JWT payload)에서 읽습니다. 권한 판단은 항상 서버가 합니다.

### AUTH-12~15 세션 로그인·로그아웃 (v2, 공개, 구현됨)

JWT(v1) 대신 **Redis 세션**으로 로그인하는 방식입니다. 로그인하면 `SESSION` 쿠키가 내려가고, 그 뒤에는 **경로를 바꾸지 않은 업무 API**(`/api/v1/users/me`, `/api/v1/admin/**` 등)를 쿠키만 보내서 호출합니다. 업무 API는 JWT와 세션을 모두 인정하며, 요청에 `Authorization: Bearer` 헤더가 있으면 JWT가 먼저 적용됩니다.

| 항목 | v1 (JWT) | v2 (세션) |
| --- | --- | --- |
| 로그인 결과 | 액세스 토큰(본문) + 리프레시 쿠키 | `SESSION` 쿠키 (토큰 없음) |
| 이후 요청 | `Authorization: Bearer {토큰}` 헤더 | 쿠키만 보냄 (브라우저가 자동으로) |
| 로그아웃 | 없음 (토큰이 만료될 때까지 유효) | 서버가 세션을 지워서 **바로** 끊김 |
| 만료 | 액세스 15분, 리프레시 14일 | 마지막 요청 후 **30분** (요청이 오면 연장) |

- `SESSION` 쿠키는 `HttpOnly`, `SameSite=Lax`, `Path=/`이고 운영(https)에서는 `Secure`입니다. 스크립트로 읽을 수 없습니다.
- 요청은 v1 로그인과 같고(`{ "email", "password" }`), 에러도 같습니다. 판매자는 사업체가 승인된 뒤에만 로그인되고(`SELLER003`~`SELLER005`), 로그인할 때 이미 세션이 있으면 세션 번호를 새로 바꿉니다.
- 판매자 가입 신청(AUTH-09)은 인증이 없어서 v1의 API를 그대로 씁니다.
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

**에러**: 401 `AUTH001` 불일치 · 401 `AUTH003` 비활성 계정 · 403 `SELLER003` 승인 대기 · 403 `SELLER004` 반려 · 403 `SELLER005` 정지 · 400 `COMMON000` 요청 값 오류

**로그아웃 응답 200**

```json
{ "success": true, "code": "0000", "message": "", "data": null }
```

### AUTH-16 예매자 가입 (v2 세션) — `POST /api/v2/auth/users/signup` (공개, 구현됨)

요청과 검증, 에러는 AUTH-02와 같습니다. 다른 점은 **응답이 토큰 대신 `SESSION` 쿠키**라는 것입니다. 가입하면 바로 로그인되어, 받은 쿠키로 `GET /api/v1/users/me` 같은 업무 API를 호출할 수 있습니다.

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

가입이 실패하면(이메일 중복 `USER000`, 같은 본인인증 `USER001`, 필수 약관 미동의 등) 세션이 만들어지지 않고 회원도 저장되지 않습니다. 약관 확인, 회원 저장, 약관 동의 기록은 한 트랜잭션이고, 세션은 그 뒤에 만듭니다.

---

## 2. 공연·좌석 조회 (예매자)

### PFM-01 공연 목록·검색 — `GET /api/v1/performances` (공개, 구현됨)

Query: `genre`(`CONCERT/MUSICAL/PLAY/CLASSIC/ETC`), `sort`(`LATEST`), `region`, `keyword`, `page`, `size`

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

`saleStatus`: `UPCOMING` / `ON_SALE` / `SOLD_OUT` / `ENDED` (계산값). `PUBLISHED` 공연만 노출.

| `saleStatus` | 조건 (위에서부터 먼저 맞는 것) |
|---|---|
| `ENDED` | 판매 종료 시각이 지남, 또는 마지막 회차가 시작됨, 또는 취소되지 않은 회차가 없음 |
| `UPCOMING` | 판매 오픈 시각 전 |
| `SOLD_OUT` | 아직 시작하지 않은 회차에 판매 가능(`AVAILABLE`) 좌석이 없음. 임시 점유(`HELD`)만 남아도 매진이고, 점유가 풀리면 다시 `ON_SALE` |
| `ON_SALE` | 그 외 |

종료된 공연도 목록에 남고 `ENDED`로 표시됩니다.

**에러**: 400 `COMMON000` 없는 `genre`·`sort` 값

### PFM-02 공연 상세 — `GET /api/v1/performances/{performanceId}` (공개, 구현됨)

- 공개(`PUBLISHED`)된 공연만 볼 수 있습니다. 작성 중·취소된 공연은 없는 공연과 같은 404입니다.
- `schedules`: 취소된 회차는 빠지고 공연 일시가 빠른 순입니다. (공연 목록의 첫·마지막 회차 일시와 같은 기준)
  - `saleStatus`는 회차마다 PFM-01과 같은 규칙으로 계산합니다. (마지막 회차 자리에 그 회차의 시작 일시, 남은 좌석은 그 회차의 `AVAILABLE`)
  - 일시 중지(`PAUSED`)·연기(`POSTPONED`)는 `saleStatus`에 섞지 않고 `status`로 내려갑니다. 화면은 `status`가 `ON_SALE`이 아니면 그 상태를 먼저 보여 줍니다.
- `grades`: 구역에 지정(`ASSIGNED`)된 등급만, 비싼 순(가격이 같으면 번호순)입니다. 어느 구역에도 붙지 않은 등급은 나오지 않습니다.
- `cancelPolicy`: 공연이 고른 취소 규정의 구간입니다. 공연에서 먼 구간부터(`daysFrom` 큰 순).
- `venue`·`seller`는 정보가 없으면 `null`, `commonGuide`의 각 안내는 관리자가 아직 넣지 않았으면 `null`입니다.
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
        "saleStatus": "UPCOMING"
      }
    ],
    "grades": [
      {
        "gradeId": 20,
        "name": "VIP석",
        "price": 165000,
        "color": "#E8503A"
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
        "grade": "R석",
        "color": "#F2994A",
        "price": 154000,
        "remaining": 38,
        "total": 220
      }
    ]
  }
}
```

`INTENTIONALLY_UNSOLD` 구역은 `grade`·`color`·`price`가 `null`이고 판매 제외입니다. 공개 공연에는 `UNSET` 구역이 없습니다. (혹시 있으면 판매하지 않는 구역과 같이 `null`)

**에러**: 404 `PERFORMANCE200` 볼 수 없는 회차(없음, 공개되지 않은 공연, 취소된 회차) · 400 `COMMON000` 회차 번호가 숫자가 아님

### PFM-04 구역 좌석 상세 — `GET /api/v1/schedules/{scheduleId}/zones/{zoneId}/seats` (공개)

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

`status`: `AVAILABLE` / `HELD` / `SOLD` / `BLOCKED` / `RESERVED`. **에러**: 404 회차·구역 없음, 좌석 미생성 회차

---

## 3. 예매·결제·취소 (예매자)

### BKG-01 좌석 점유·예매 생성 — `POST /api/v1/schedules/{scheduleId}/bookings` (예매자)

좌석 점유(7분)와 `PENDING` 예매 생성은 한 트랜잭션.

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
        "gradeName": "R석",
        "price": 154000,
        "bookingFee": 2000
      },
      {
        "bookingSeatId": 5002,
        "zoneName": "F2구역",
        "rowLabel": "9열",
        "seatNumber": "7",
        "gradeName": "R석",
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
| 400 | `BOOKING002` | 판매 중이 아닌 회차 (판매 기간 밖·회차 중지·연기·취소·이미 시작. 공연이 공개 상태가 아니면 `BOOKING004`) |
| 400 | `BOOKING003` | 판매 제외 좌석 (`BLOCKED`, `RESERVED`) |
| 404 | `BOOKING004` | 없는 회차 (공연이 공개 상태가 아닌 것도 같은 404) |
| 404 | `BOOKING005` | 없는 좌석 (다른 회차의 좌석도 같은 404) |
| 400 | `COMMON000` | 좌석 번호가 비었거나 중복 |
| 409 | `COMMON004` | 락을 기다리는 시간(1초) 안에 못 잡음 |

락을 기다리는 시간과 만료 처리 규칙은 `part4` 4-7을 따른다. 만료된 좌석은 스케줄러(1분)가 풀어 줄 때까지 `BOOKING000`이다.

`seats`는 요청한 `seatIds` 순서대로 내려간다. 락 대기 시간(`app.booking.lock-wait`, 1초)과 결제 제한 시간(`app.booking.hold-duration`, 7분)은 `application.yaml`의 설정값이다. 예매자 토큰만 호출할 수 있고(판매자·관리자 403), 예매자 번호는 토큰에서 꺼낸다.

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

**에러**(코드 미정): 404 예매 없음(남의 예매 포함) · 409 `PENDING`이 아니거나 만료됨 · 400 허용되지 않는 수령 방법 · 400 배송 정보 누락

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

**에러**(코드 미정): 404 결제 없음 · 400 금액 불일치 · 409 점유 시간 만료 · 409 이미 처리된 결제 · 400 토스 승인 거절 · 502 토스 통신 오류(5분 스케줄러가 확정)

### BKG-04 내 예매 목록 — `GET /api/v1/bookings` (예매자)

Query: `status`(`PENDING/PAID/PARTIAL_CANCELED/CANCELED/EXPIRED`), `page`, `size`

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

### BKG-05 내 예매 상세 — `GET /api/v1/bookings/{bookingId}` (예매자)

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
        "gradeName": "R석",
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

**에러**: 404 예매 없음(남의 예매 포함)

### BKG-06 예매 취소 — `POST /api/v1/bookings/{bookingId}/cancel` (예매자)

`bookingSeatIds`를 생략하면 전체 취소. 환불 = Σ(좌석가 − 취소 수수료) + Σ(예매 수수료).

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

**에러**(코드 미정): 404 예매 없음 · 409 취소할 수 없는 상태 · 400 당일 취소 불가 · 400 이미 취소된 좌석 · 502 토스 환불 실패(환불은 `FAILED`로 남아 재시도)

---

## 4. 판매자 (`/api/v1/seller/**`, 자기 사업체 리소스만 — 남의 것은 404)

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
| `posterUrl` | 필수, `http://` 또는 `https://`로 시작하고 공백 없음, 2048자 이하 |
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

### SLR-03 공연 수정 — `PUT /api/v1/seller/performances/{performanceId}`

요청은 SLR-02와 같은 필드 전체. 이미지 목록도 전체 교체합니다. 판매 필드는 공연의 일반 수정 필드이며 별도 판매 정책 API는 없습니다. `PUBLISHED` 이후에는 `cancelPolicyId`를 바꿀 수 없습니다.

공연장 변경은 공연이 `DRAFT`일 때만 허용합니다. 새 공연장의 모든 회차 시간 충돌을 확인하고, 기존 구역 설정을 새 공연장 구역의 `UNSET` 상태로 초기화합니다. 판매 좌석이 이미 생성됐으면 예매·점유·판매자 보류가 없는 경우 기존 좌석을 삭제하고 `seatsGeneratedAt`을 비워 재생성할 수 있게 합니다. 예매나 사용 중인 좌석이 있으면 변경을 거부합니다.

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

**에러**(코드 미정): 409 취소된 공연 · 400 판매 종료가 오픈보다 빠름 · 409 예매가 생긴 뒤 1인 최대 매수 줄이기 · 409 공개 후 공연장/취소 규정 변경 · 409 새 공연장의 회차 시간 충돌 · 409 예매/사용 중 좌석이 있는 공연장 변경

### SLR-04 공연 공개 — `POST /api/v1/seller/performances/{performanceId}/publish`

Body 없음. `DRAFT` → `PUBLISHED`.

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

**에러**(코드 미정): 409 이미 공개·취소된 공연 · 400 판매 필드 미설정 · 400 회차 없음 · 400 `UNSET` 구역 있음(구역 ID·이름 반환) · 400 `ASSIGNED` 구역 없음(모든 구역이 의도적 미판매)

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
        "name": "VIP석",
        "price": 165000,
        "color": "#E8503A"
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
- `zones`: 공연의 구역 설정(`zone_grade`) 행마다 하나씩, 공연장 구역 순서(`sortOrder`)순(같으면 구역 번호순)입니다. `gradeId`는 `ASSIGNED`일 때만 있고 나머지는 `null`입니다.

**에러**: 404 `PERFORMANCE000` 다른 사업체의 공연이거나 없는 공연 (번호가 있는지 알려주지 않으려고 같은 404)

### SLR-09 등급 추가 — `POST /api/v1/seller/performances/{performanceId}/grades`

**요청**

```json
{ "name": "R석", "price": 154000, "color": "#F2994A" }
```

**응답 201**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "gradeId": 21,
    "name": "R석",
    "price": 154000,
    "color": "#F2994A"
  }
}
```

**에러**(코드 미정): 409 좌석 생성 후라 수정 불가 · 409 등급명 중복

### SLR-10 등급 수정 — `PUT /api/v1/seller/performances/{performanceId}/grades/{gradeId}`

요청은 SLR-09와 같은 필드.

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "gradeId": 21,
    "name": "R석",
    "price": 150000,
    "color": "#F2994A"
  }
}
```

**에러**(코드 미정): 404 등급 없음 · 409 좌석 생성 후라 수정 불가

### SLR-11 구역별 상태·등급 설정 — `PATCH /api/v1/seller/performances/{performanceId}/zone-grades`

요청에 포함된 구역만 변경합니다. 빠진 구역은 기존 상태를 유지합니다. `ASSIGNED`는 `gradeId`가 필요하고, `INTENTIONALLY_UNSOLD`와 `UNSET`은 `gradeId: null`로 보냅니다. 의도적 미판매와 아직 설정하지 않은 상태를 구분합니다.

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
        "name": "R석",
        "price": 154000,
        "color": "#F2994A"
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

**에러**(코드 미정): 400 다른 공연장의 구역 · 400 다른 공연의 등급 · 400 상태와 `gradeId` 조합 오류 · 409 좌석 생성 후라 수정 불가

### SLR-12 회차 추가 — `POST /api/v1/seller/performances/{performanceId}/schedules`

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

판매 오픈 24시간 안이면 좌석이 즉시 생성됩니다.

**에러**(코드 미정): 409 같은 공연장의 다른 회차와 시간 겹침 · 409 취소된 공연

### SLR-13 회차 수정 — `PUT /api/v1/seller/schedules/{scheduleId}`

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

**에러**(코드 미정): 409 예매가 생긴 회차(연기로 처리) · 409 시간 겹침

### SLR-14 판매 좌석 생성 — `POST /api/v1/seller/schedules/{scheduleId}/seats/generate`

Body 없음. 공연장 좌석을 복사(회차 1개 = 트랜잭션 1개). 생성 전에 `UNSET` 구역이 없는지 확인합니다. `ASSIGNED` 구역은 `AVAILABLE`, `INTENTIONALLY_UNSOLD` 구역은 `BLOCKED`로 생성합니다.

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

**에러**(코드 미정): 409 이미 생성됨 · 400 판매 필드 미설정 · 400 `UNSET` 구역 있음

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

### SLR-17 판매 현황 — `GET /api/v1/seller/performances/{performanceId}/sales`

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

### SLR-18 회차별 예매 목록 — `GET /api/v1/seller/schedules/{scheduleId}/bookings`

Query: `status`, `page`, `size`

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
        "ordererName": "홍길동",
        "ordererPhone": "01012345678",
        "seatCount": 2,
        "totalAmount": 312000,
        "receiveType": "DELIVERY",
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

- `owner`에는 계정 번호·로그인 이메일·본인인증 휴대폰·상태만 줍니다. 비밀번호·CI 해시·생년월일은 주지 않습니다.
- 이름은 본인인증 이름과 `representativeName`이 같아야 가입되므로 `owner`에 따로 두지 않습니다.
- `contactPhone`(업무 연락처)과 `owner.phone`(본인인증 휴대폰)은 다를 수 있어서 둘 다 줍니다.
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

### ADM-05 환불 실패 목록 — `GET /api/v1/admin/refunds`

Query: `status`(기본 `FAILED`), `exhaustedOnly`(기본 `true`, 재시도 3회 이상만), `page`, `size`

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
        "requestedAt": "2026-12-01T10:00:00"
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

### ADM-06 환불 수동 재처리 — `POST /api/v1/admin/refunds/{refundId}/retry`

Body 없음. 같은 `idempotency_key`로 토스 재요청(이중 환불 방지), 감사 로그.

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

**에러**(코드 미정): 404 환불 없음 · 409 `FAILED`가 아님 · 409 처리 중(`PROCESSING`)

---

## 확정이 필요한 것

1. 미구현 API의 에러 코드(접두사·번호)
2. ~~페이징 응답 구조~~ → `content/page/size/totalElements/totalPages`로 확정 (`PageResponse`)
3. ~~공개 조회 API의 `permitAll` 경로~~ → `SecurityConfig`에 반영됨 (위 권한 표 참고)
4. 로그아웃 경로 (`/api/v1/auth/**`가 permitAll)
5. ~~관리자 로그인 경로~~ → `POST /api/v1/auth/admins/login` 구현됨. 판매자·관리자 로그아웃은 미정
6. ~~공연 등록(DRAFT) 때 판매 정책이 비어도 되는지~~ → 확정: 판매 정책 5개 컬럼은 DRAFT에서 NULL 허용, PUBLISHED 전환 시 서비스에서 필수값 검증
7. 이미지는 주소 문자열만 받음 — 업로드 방식 미정
8. 환불 `reasonType`에 `SYSTEM` 포함 여부 (4부 문서는 `SYSTEM` 포함, 기능 정의서에는 없음) → `project-log.md`에서 `SYSTEM` 포함으로 결정되어 있음
