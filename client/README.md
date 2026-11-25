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

`NEXT_PUBLIC_` 값은 브라우저 번들에 들어갑니다. 비밀 값을 이 이름으로 만들지 마세요.

## 서버가 꺼져 있을 때

BFF는 가짜 응답으로 대신하지 않습니다. Spring에 닿지 않으면 502(`COMMON502`)를 그대로 돌려주고, 서버에 아직 없는 API는 서버의 "없는 경로" 오류가 그대로 보입니다.
로컬에서 화면을 보려면 Spring을 먼저 띄웁니다. (`doc/local-multi-server.md`)

## Vercel 배포

조직의 프라이빗 저장소는 무료 Vercel 요금제로 GitHub에 연결할 수 없어서, **GitHub Actions가 Vercel CLI로 직접 올립니다.**
(`.github/workflows/deploy-client.yml`. `main`에 `client/`가 바뀐 채 머지되면 자동으로 돌고, Actions 탭의 "Run workflow"로 직접 실행할 수도 있습니다.)

### 처음 한 번 하는 설정

1. **Vercel 프로젝트 만들기** (GitHub 연결 없이). 내 컴퓨터에서:
   ```bash
   cd client
   npx vercel login
   npx vercel link
   ```
   `link`가 묻는 대로 새 프로젝트를 만듭니다. 이때 **Root Directory는 비워 둡니다.** (Actions가 `client/` 폴더를 프로젝트 루트로 올립니다) 끝나면 `client/.vercel/project.json`이 생기는데, git에는 올라가지 않습니다.
2. **환경 변수**를 Vercel 프로젝트(Settings → Environment Variables, Production)에 넣습니다. 위 표의 `SPRING_API_URL`, `NEXT_PUBLIC_TOSS_CLIENT_KEY`, `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`입니다. 이 값은 배포할 때 `vercel pull`이 받아 가고, `NEXT_PUBLIC_` 값은 빌드에 들어갑니다.
3. **GitHub 저장소 Secrets**(Settings → Secrets and variables → Actions)에 세 개를 넣습니다.
   - `VERCEL_TOKEN`: Vercel 계정 설정(Settings → Tokens)에서 만든 토큰
   - `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`: 1번이 만든 `client/.vercel/project.json`의 `orgId`, `projectId`
4. **도메인** `noresell.store`를 Vercel 프로젝트(Settings → Domains)에 연결하고, 가비아 DNS를 Vercel이 알려 주는 값으로 맞춥니다.
5. 첫 배포는 Actions 탭에서 "Deploy client"를 직접 실행해서 확인합니다.

함수 실행 지역은 `vercel.json`에서 서울(`icn1`)로 고정합니다. 서버(`api.noresell.store`)와 가깝게 두려는 것입니다.

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
