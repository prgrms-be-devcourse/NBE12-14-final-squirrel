# tickting 클라이언트

Next.js 16(App Router, pnpm) 프론트입니다. 브라우저는 Spring 서버를 직접 부르지 않고 이 앱의 BFF(`/api/*`)만 부릅니다.
BFF가 같은 경로로 Spring에 넘기고 `SESSION` 쿠키를 그대로 전달하므로, Spring의 Redis 세션이 로그인 상태를 판단합니다.

```
브라우저 → Next 서버(BFF, /api/*) → Spring (api.noresell.store)
```

## 실행

```bash
cd client
pnpm install
pnpm dev
```

`client/.env.local`에 아래 환경 변수를 넣습니다. (git에 올라가지 않습니다. 값은 팀에서 받습니다.)

## 환경 변수

| 이름 | 필수 | 설명 |
|---|---|---|
| `SPRING_API_URL` | 필수 | BFF가 호출할 Spring 주소. 로컬은 `http://localhost:8080`, 운영은 `https://api.noresell.store` |
| `NEXT_PUBLIC_TOSS_CLIENT_KEY` | 운영 필수 | 토스 결제위젯 **클라이언트** 키(`test_gck_…`/`live_gck_…`). 시크릿 키는 서버(Spring)만 갖고 여기에 넣지 않습니다 |
| `NEXT_PUBLIC_RECAPTCHA_SITE_KEY` | 운영 필수 | reCAPTCHA v3 **사이트** 키. 구글 콘솔의 허용 도메인에 배포 도메인(`noresell.store`)을 넣어야 합니다 |
| `MOCK_FALLBACK` | 선택 | `true`/`false`. 서버에 닿지 않을 때 목 응답으로 대신할지. 정하지 않으면 개발(`pnpm dev`)에서만 켜집니다 |

`NEXT_PUBLIC_` 값은 브라우저 번들에 들어갑니다. 비밀 값을 이 이름으로 만들지 마세요.

## 목(mock) 응답

서버에 아직 없는 API나 서버가 꺼져 있을 때 개발이 막히지 않게, BFF가 같은 경로의 목 응답으로 대신합니다. (`src/server/mock/`)
응답에는 `x-mock` 헤더가 붙고 화면 위에 안내가 뜹니다.

- **운영에서는 꺼집니다.** 켜 두면 서버가 잠깐 안 닿을 때 가짜 공연·예매가 보이고 아무나 목으로 로그인할 수 있습니다.
- 운영에서 서버에 없는 API(회차 추가·수정, 판매 좌석 생성, 회차·공연 취소, 관리자 공연장 생성·환불)는 오류로 보입니다. 서버에 구현되면 동작합니다.
- 목의 이메일 인증번호는 항상 `123456`입니다.
- `src/server/mock/concert-arena-44.json`은 `doc/venue-seed-concert-arena-44.json`의 복사본입니다. 시드가 바뀌면 같이 고칩니다.

## Vercel 배포

1. 프로젝트를 가져올 때 **Root Directory를 `client`**로 지정합니다. (프레임워크는 Next.js, 패키지 관리자는 `pnpm-lock.yaml`로 자동 인식)
2. Environment Variables(Production)에 위 표의 `SPRING_API_URL`, `NEXT_PUBLIC_TOSS_CLIENT_KEY`, `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`를 넣습니다. `MOCK_FALLBACK`은 넣지 않습니다.
3. 함수 실행 지역은 `vercel.json`에서 서울(`icn1`)로 고정합니다. 서버(`api.noresell.store`)와 가깝게 두려는 것입니다.
4. 도메인 `noresell.store`를 프로젝트에 연결합니다.

### 배포 환경에서 알아 둘 것

- **CORS 설정은 필요 없습니다.** 브라우저는 같은 주소(`noresell.store/api/*`)만 부르고, Spring은 Next 서버가 서버 간으로 호출합니다.
- **로그인 쿠키(`SESSION`)는 Spring이 `Secure`로 내려줍니다.** https에서만 동작하므로 운영은 https 도메인으로만 확인합니다.
- **사진 업로드는 4MB까지**입니다. 서버는 10MB까지 받지만 Vercel 함수가 요청 본문 4.5MB를 넘으면 거절합니다.
- **결제 승인·예매 취소·사진 업로드는 최대 25초까지 기다립니다.** (토스 응답을 기다리는 동안 먼저 끊으면 결제는 됐는데 화면은 실패로 보입니다.) 그 밖의 요청은 10초입니다.
- Spring이 보는 접속 IP는 Vercel의 IP입니다. IP 기반 로그인 잠금은 이메일과 함께 걸리지만, 같은 이메일로는 사용자 구분이 안 됩니다. (`X-Forwarded-For`는 그대로 전달합니다)
- 토스 결제 성공·실패 주소는 현재 접속 주소로 만들어서(`/bookings/{id}/pay/success`) 따로 등록할 주소가 없습니다.

## 점검 명령

```bash
pnpm lint
pnpm exec tsc --noEmit
pnpm build
```
