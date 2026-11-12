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
| AUTH-06 | 내 정보 조회 | GET `/api/v1/users/me` | 미구현 |
| AUTH-07 | 내 정보 수정 | PATCH `/api/v1/users/me` | 미구현 |
| AUTH-08 | 회원 탈퇴 | DELETE `/api/v1/users/me` | 미구현 |
| AUTH-09 | 판매자 가입 신청 | POST `/api/v1/auth/sellers/signup` | 구현됨 |
| AUTH-10 | 판매자 로그인 | POST `/api/v1/auth/sellers/login` | 구현됨 |
| AUTH-11 | 관리자 로그인 | POST `/api/v1/auth/admins/login` | 구현됨 |
| PFM-01 | 공연 목록·검색 | GET `/api/v1/performances` | 미구현 |
| PFM-02 | 공연 상세 | GET `/api/v1/performances/{performanceId}` | 미구현 |
| PFM-03 | 구역 요약 | GET `/api/v1/schedules/{scheduleId}/zones` | 미구현 |
| PFM-04 | 구역 좌석 상세 | GET `/api/v1/schedules/{scheduleId}/zones/{zoneId}/seats` | 미구현 |
| BKG-01 | 좌석 점유·예매 생성 | POST `/api/v1/schedules/{scheduleId}/bookings` | 미구현 |
| BKG-02 | 결제 준비 | POST `/api/v1/bookings/{bookingId}/payments` | 미구현 |
| BKG-03 | 결제 승인 | POST `/api/v1/payments/confirm` | 미구현 |
| BKG-04 | 내 예매 목록 | GET `/api/v1/bookings` | 미구현 |
| BKG-05 | 내 예매 상세 | GET `/api/v1/bookings/{bookingId}` | 미구현 |
| BKG-06 | 예매 취소 | POST `/api/v1/bookings/{bookingId}/cancel` | 미구현 |
| SLR-01 | 공연장 목록 | GET `/api/v1/seller/venues` | 미구현 |
| SLR-02 | 공연 등록 | POST `/api/v1/seller/performances` | 미구현 |
| SLR-03 | 공연 수정 | PUT `/api/v1/seller/performances/{performanceId}` | 미구현 |
| SLR-04 | 공연 공개 | POST `/api/v1/seller/performances/{performanceId}/publish` | 미구현 |
| SLR-05 | 내 공연 목록 | GET `/api/v1/seller/performances` | 미구현 |
| SLR-06 | 내 공연 상세 | GET `/api/v1/seller/performances/{performanceId}` | 미구현 |
| SLR-07 | 판매 정책 설정 | PUT `/api/v1/seller/performances/{performanceId}/sale-policy` | 미구현 |
| SLR-08 | 등급·구역 조회 | GET `/api/v1/seller/performances/{performanceId}/grades` | 미구현 |
| SLR-09 | 등급 추가 | POST `/api/v1/seller/performances/{performanceId}/grades` | 미구현 |
| SLR-10 | 등급 수정 | PUT `/api/v1/seller/performances/{performanceId}/grades/{gradeId}` | 미구현 |
| SLR-11 | 구역별 등급 지정 | PUT `/api/v1/seller/performances/{performanceId}/zone-grades` | 미구현 |
| SLR-12 | 회차 추가 | POST `/api/v1/seller/performances/{performanceId}/schedules` | 미구현 |
| SLR-13 | 회차 수정 | PUT `/api/v1/seller/schedules/{scheduleId}` | 미구현 |
| SLR-14 | 판매 좌석 생성 | POST `/api/v1/seller/schedules/{scheduleId}/seats/generate` | 미구현 |
| SLR-15 | 회차 취소 | POST `/api/v1/seller/schedules/{scheduleId}/cancel` | 미구현 |
| SLR-16 | 공연 취소 | POST `/api/v1/seller/performances/{performanceId}/cancel` | 미구현 |
| SLR-17 | 판매 현황 | GET `/api/v1/seller/performances/{performanceId}/sales` | 미구현 |
| SLR-18 | 회차별 예매 목록 | GET `/api/v1/seller/schedules/{scheduleId}/bookings` | 미구현 |
| ADM-01 | 사업체 목록·검색 | GET `/api/v1/admin/seller-businesses` | 구현됨 |
| ADM-02 | 사업체 승인 | POST `/api/v1/admin/seller-businesses/{businessId}/approve` | 미구현 |
| ADM-03 | 사업체 반려 | POST `/api/v1/admin/seller-businesses/{businessId}/reject` | 미구현 |
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

### AUTH-06 내 정보 조회 — `GET /api/v1/users/me` (예매자, 미구현)

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

### AUTH-07 내 정보 수정 — `PATCH /api/v1/users/me` (예매자, 미구현)

보낸 필드만 수정.

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

**에러**: 409 `SELLER000` 이메일 중복 · 409 `SELLER001` 사업자번호 중복 · 400 `SELLER002` 대표자명 불일치 · 400 `AUTH300` 본인인증 실패 · 400 `COMMON000`

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

---

## 2. 공연·좌석 조회 (예매자)

### PFM-01 공연 목록·검색 — `GET /api/v1/performances` (공개)

Query: `genre`(`CONCERT/MUSICAL/PLAY/CLASSIC/ETC`), `region`, `keyword`, `page`, `size`

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

### PFM-02 공연 상세 — `GET /api/v1/performances/{performanceId}` (공개)

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

**에러**: 404 공연 없음 또는 `PUBLISHED`가 아님

### PFM-03 구역 요약 — `GET /api/v1/schedules/{scheduleId}/zones` (공개)

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

등급 미지정 구역은 `grade`·`color`·`price`가 `null`, 판매 제외. **에러**: 404 회차 없음

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

**에러**(코드 미정): 409 이미 점유·판매된 좌석 · 409 1인 최대 매수 초과 · 400 판매 중이 아닌 회차 · 400 판매 제외 좌석 · 404 회차·좌석 없음

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

### SLR-01 공연장 목록 — `GET /api/v1/seller/venues`

Query: `keyword`, `region`, `page`, `size`

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

### SLR-02 공연 등록 — `POST /api/v1/seller/performances`

`DRAFT`로 생성.

**요청**

```json
{
  "venueId": 1,
  "title": "가상 콘서트",
  "genre": "CONCERT",
  "ageLimit": 12,
  "runningTime": 150,
  "castInfo": "가상 밴드",
  "posterUrl": "https://cdn.example.com/posters/12.jpg",
  "notice": "공연 시작 30분 전부터 입장 가능합니다.",
  "guide": "촬영·녹음은 금지됩니다.",
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

**에러**(코드 미정): 404 공연장 없음 · 400 사용 중지된 공연장

### SLR-03 공연 수정 — `PUT /api/v1/seller/performances/{performanceId}`

요청은 SLR-02와 같은 필드 전체.

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

**에러**(코드 미정): 409 취소된 공연

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
    "unassignedZones": [
      {
        "zoneId": 22,
        "name": "H구역"
      }
    ]
  }
}
```

**에러**(코드 미정): 409 이미 공개·취소된 공연 · 400 판매 정책 미설정 · 400 회차 없음

### SLR-05 내 공연 목록 — `GET /api/v1/seller/performances`

Query: `status`(`DRAFT/PUBLISHED/CANCELED`), `page`, `size`

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

### SLR-06 내 공연 상세 — `GET /api/v1/seller/performances/{performanceId}`

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
    "salePolicy": {
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
        "seatsGeneratedAt": "2026-11-24T20:00:12"
      }
    ],
    "createdAt": "2026-11-13T10:20:00",
    "updatedAt": "2026-11-13T11:05:00"
  }
}
```

### SLR-07 판매 정책 설정 — `PUT /api/v1/seller/performances/{performanceId}/sale-policy`

**요청**

```json
{
  "saleOpenAt": "2026-11-25T20:00:00",
  "saleCloseAt": "2026-12-20T18:00:00",
  "maxPerPerson": 4,
  "receiveType": "BOTH",
  "bookingFee": 2000
}
```

`receiveType`: `ONSITE` / `DELIVERY` / `BOTH`. 한도는 예매가 생긴 뒤 늘리기만 가능(임시).

**응답 200**

```json
{
  "success": true,
  "code": "0000",
  "message": "",
  "data": {
    "performanceId": 12,
    "saleOpenAt": "2026-11-25T20:00:00",
    "saleCloseAt": "2026-12-20T18:00:00",
    "maxPerPerson": 4,
    "receiveType": "BOTH",
    "bookingFee": 2000,
    "unassignedZones": []
  }
}
```

**에러**(코드 미정): 400 종료가 오픈보다 빠름 · 409 예매가 생긴 뒤 한도 줄이기

### SLR-08 등급·구역 조회 — `GET /api/v1/seller/performances/{performanceId}/grades`

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
        "gradeId": 20
      },
      {
        "zoneId": 22,
        "name": "H구역",
        "floor": "2층",
        "sortOrder": 22,
        "seatCount": 42,
        "gradeId": null
      }
    ]
  }
}
```

`locked`: 판매 좌석이 생성되어 등급·가격 수정 불가한 상태.

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

### SLR-11 구역별 등급 지정 — `PUT /api/v1/seller/performances/{performanceId}/zone-grades`

`gradeId: null`이면 지정 해제(판매 제외).

**요청**

```json
{ "assignments": [ { "zoneId": 12, "gradeId": 21 }, { "zoneId": 22, "gradeId": null } ] }
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
        "gradeId": 21
      },
      {
        "zoneId": 22,
        "name": "H구역",
        "floor": "2층",
        "sortOrder": 22,
        "seatCount": 42,
        "gradeId": null
      }
    ]
  }
}
```

**에러**(코드 미정): 400 다른 공연장의 구역 · 400 다른 공연의 등급 · 409 좌석 생성 후라 수정 불가

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

Body 없음. 공연장 좌석을 복사(회차 1개 = 트랜잭션 1개). 등급 미지정 구역은 `BLOCKED`.

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

**에러**(코드 미정): 409 이미 생성됨 · 400 판매 정책·등급 미설정

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

### ADM-02 사업체 승인 — `POST /api/v1/admin/seller-businesses/{businessId}/approve`

Body 없음. 처리 관리자·시각 기록, 감사 로그.

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

**에러**: 404 `SELLER006` 사업체 없음 · 409 이미 처리된 건(코드 미정)

### ADM-03 사업체 반려 — `POST /api/v1/admin/seller-businesses/{businessId}/reject`

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

**에러**: ADM-02와 같음

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
6. 공연 등록(DRAFT) 때 판매 정책이 비어도 되는지 (`performance` 컬럼이 NOT NULL)
7. 이미지는 주소 문자열만 받음 — 업로드 방식 미정
8. 환불 `reasonType`에 `SYSTEM` 포함 여부 (4부 문서는 `SYSTEM` 포함, 기능 정의서에는 없음) → `project-log.md`에서 `SYSTEM` 포함으로 결정되어 있음
