# Redis 작업할 때 해야 할 것

> Redis 작업을 시작할 때 이 문서를 먼저 읽는다. 지금까지 "Redis 때 하기로 한 것"을 한곳에 모았다.
> 가입·로그인 API(`feat/account-auth`)를 만들면서 **일부러 미뤄 둔 것**이 대부분이다.

## 1. 인증 (가장 먼저)

현재 리프레시 토큰은 **서버에 저장하지 않는다.** 그래서 아래가 안 된다.

| # | 할 일 | 지금 상태 | 방법 |
|---|---|---|---|
| 1 | **로그아웃 API** | 없음. 쿠키를 지워도 토큰 자체는 14일간 유효 | `POST /api/v1/auth/logout`: 쿠키 삭제 + Redis에서 리프레시 토큰 삭제 |
| 2 | **리프레시 토큰 서버 저장** | 저장 안 함 (JWT 서명만 검증) | 발급할 때 Redis에 저장하고, 재발급 때 저장된 값과 같은지 확인. 만료(TTL)는 `jwt.refresh-token-validity-seconds`와 같게 |
| 3 | **재발급 시 옛 토큰 폐기 (rotation)** | 새 토큰을 주지만 옛 토큰도 계속 유효 | 재발급하면 저장된 값을 새 토큰으로 교체. **이미 폐기된 토큰이 다시 오면 탈취로 보고 그 계정의 리프레시 토큰을 전부 삭제** |
| 4 | **액세스 토큰 블랙리스트** | 없음. 정지·탈퇴·담당자 비활성화해도 액세스 토큰(15분)은 계속 통함 | 아래 "즉시 차단" 참고 |
| 5 | **이메일 인증번호** | 없음 (부록 A: Redis로 처리하기로 함) | 인증번호와 재발송 제한을 TTL 키로 저장 |

**즉시 차단 (4번) 설계 메모**
- 지금 액세스 토큰에는 `jti`(토큰 고유 번호)가 없다. 토큰 한 장씩 막으려면 `jti`를 `JwtProvider`에 추가해야 한다.
- 더 단순한 방법: 계정별로 "이 시각 이전에 발급된 토큰은 무효" 값(`invalidated-before`)을 Redis에 두고, `JwtAuthenticationFilter`에서 토큰의 `iat`와 비교한다. 정지·탈퇴·비활성화 때 이 값만 갱신하면 그 계정의 토큰이 한 번에 막힌다. TTL은 액세스 토큰 유효 시간(15분)이면 충분하다.
- 확인할 곳: `JwtAuthenticationFilter`(필터에서 매 요청 조회하므로 Redis 장애 시 동작을 정해야 함: 막을지 통과시킬지)

**Redis 키 규칙**
- 예매자·판매자·관리자는 `id`가 겹칠 수 있다. 키에는 **항상 계정 종류를 붙인다.** 예: `refresh:USER:5`, `refresh:SELLER:5`
- 키 접두사는 한곳(상수)에서 만든다.

**같이 고칠 코드**
- `TokenService`: `issue()`에서 저장, `refresh()`에서 비교·교체
- 관련 테스트에 케이스 추가
  - 로그아웃 후 그 쿠키로 재발급 불가
  - 재발급 후 옛 리프레시 토큰으로 재발급 불가, 재사용하면 새 토큰도 무효
  - 같은 리프레시 토큰으로 **동시에** 재발급을 요청해도 한 번만 성공 (rotation 경쟁)
  - 계정 정지 후 액세스 토큰이 즉시 막힘
- `AuthController` 설명(`@Operation`)과 `TokenService` 주석의 "로그아웃으로 무효화할 수 없다"는 문구를 고친다.

## 2. 환경 설정

| 항목 | 내용 |
|---|---|
| Redis 컨테이너 | `docker-compose.yml`에 추가. (이 파일은 `.gitignore`라서 팀원은 각자 만든다. 필요하면 추가할 내용을 팀에 공유) |
| 접속 정보 | `.env`와 `application-local.yaml`에 Redis 호스트·포트. `spring.data.redis.*` |
| 상태 확인 | actuator health에 Redis가 포함된다. Redis가 없으면 health가 DOWN으로 보이니, 로컬에서 안 쓸 때는 `management.health.redis.enabled` 처리를 확인 |
| 테스트 | `TestcontainersConfiguration`에 Redis 컨테이너가 **이미 있다.** 다만 지금은 쓰지 않으므로 Redis를 쓰기 시작하면 그대로 동작하는지 확인 |
| 운영 프로필 | `application-prod.yaml`을 만들 때 Redis 접속 정보와 `JWT_SECRET`을 환경 변수로 받는다 |

## 3. 다른 Redis 작업 (인증 이후)

- **대기열·캡챠·매크로 방어**: `queue-captcha-macro.md` 참고
- **구역 요약 좌석 조회 캐시**: 부하 테스트로 먼저 측정한 뒤에 한다. 1~2초 정도의 짧은 캐시도 충분하다. (`operations-and-performance-review.md` B-1)
- **좌석 점유 만료 보조**: 상태의 기준은 DB이고, Redis는 만료 감지를 돕는 보조 수단일 뿐이다. (3부)

## 4. 프론트와 붙일 때 같이 확인

Redis 자체는 아니지만, 리프레시 쿠키를 다루는 작업이라 같이 확인할 것들이다. 테스트(MockMvc)에서는 확인하지 못했다.

- 프론트가 다른 주소에서 쿠키를 주고받을 때: CORS 설정, `credentials: 'include'`, `SameSite` 값
- 운영 환경의 `Secure` 쿠키 설정 (공통 설정 기본값은 `true`, 로컬만 `false`)
- 쿠키 `Path`가 `/api/v1/auth`라서, 로그아웃 API도 이 경로 아래에 두어야 쿠키가 전달된다.
