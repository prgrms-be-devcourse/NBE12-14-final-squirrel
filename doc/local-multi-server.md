# 내 PC에서 서버 2대 띄우기 (Nginx)

서버가 여러 대여도 같은 결과가 나오는지 내 PC에서 확인하는 방법입니다. 순서대로 따라 하면 됩니다.

```
브라우저 → Nginx(8080) ─┬→ 앱 #1 (8081)
                        └→ 앱 #2 (8082)
```

앱 2대는 IntelliJ에서 띄우고, Nginx·PostgreSQL·Redis는 Docker로 띄웁니다.

## 0. 준비물

- Docker Desktop (켜 둔 상태)
- JDK 25, IntelliJ
- `.env` 파일 (프로젝트 루트에 둡니다. 값은 팀에서 받습니다. git에 올리지 않습니다.)
- `docker-compose.yml` (팀에서 받습니다. 역시 git에 올리지 않습니다.)

## 1. docker-compose.yml에 Nginx 추가 (처음 한 번만)

받은 `docker-compose.yml`의 `services:` 아래, `redis:` 다음에 아래를 붙여 넣습니다. (들여쓰기는 `postgres:`, `redis:`와 같은 칸입니다.)

```yaml
  nginx:
    image: nginx:1.27-alpine
    container_name: tickting-nginx
    restart: unless-stopped
    volumes:
      - ./docker/nginx/local.conf:/etc/nginx/conf.d/default.conf:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - "127.0.0.1:8080:80"
```

Nginx 설정 파일(`docker/nginx/local.conf`)은 이미 저장소에 있으니 따로 만들지 않아도 됩니다.

## 1-2. 메일(Mailpit) 추가 (이메일 인증에 필요)

이메일 인증(`doc/api-spec.md` AUTH-17·18)이 들어와서, 예매하기를 누르려면 먼저 이메일 인증을 해야 합니다. 로컬은 대기열이 기본으로 켜져 있어서, **화면으로 예매를 눌러 보는 사람은 모두** 아래 설정이 필요합니다. `.env`에 `MAIL_*`가 없으면 **앱이 뜨지 않으므로** 앱을 띄우는 사람도 모두 필요합니다.

Mailpit은 개발용 가짜 메일 서버입니다. 앱이 보낸 인증번호 메일을 밖으로 내보내지 않고 받아 두었다가 브라우저로 보여 줍니다. (가짜 주소로 가입해도 진짜 메일이 나가지 않습니다)

`docker-compose.yml`의 `services:` 아래(`nginx:` 다음)에 붙여 넣습니다.

```yaml
  # 개발용 가짜 메일 서버(Mailpit). 앱이 보낸 이메일 인증번호 메일을 받아 두었다가 브라우저로 보여 준다. 진짜 메일은 나가지 않는다.
  mailpit:
    image: axllent/mailpit:v1.34.0
    container_name: tickting-mailpit
    restart: unless-stopped
    ports:
      # 앱이 메일을 보내는 문(SMTP). .env 의 MAIL_PORT 와 같아야 한다
      - "127.0.0.1:1025:1025"
      # 받은 메일을 보는 화면. 브라우저에서 http://localhost:8025
      - "127.0.0.1:8025:8025"
```

- `.env`에 아래 다섯 줄을 추가합니다. 값이 없으면 앱이 뜨지 않습니다. (주석은 **줄 맨 앞**에만 씁니다. 값 뒤에 `# …`를 붙이면 그 글자도 값에 들어갑니다.)

```
# 이메일 인증 메일(SMTP). 로컬은 위의 Mailpit 을 쓴다.
# 메일 서버 주소
MAIL_HOST=localhost
# 메일 서버의 SMTP 포트. Mailpit 은 1025
MAIL_PORT=1025
# 메일 서버 로그인 아이디. Mailpit 은 로그인이 없어 비워 둔다 (줄은 있어야 한다)
MAIL_USERNAME=
# 메일 서버 로그인 비밀번호. 이것도 비워 둔다
MAIL_PASSWORD=
# 메일의 보낸 사람 주소. Mailpit 은 아무 주소나 받는다
MAIL_FROM=no-reply@tickting.local
# 비밀번호 재설정 메일의 링크 앞부분(프론트 주소). 끝에 / 를 붙이지 않는다. 운영은 실제 프론트 주소
FRONTEND_BASE_URL=http://localhost:3000
```

- 인증번호 메일은 `http://localhost:8025`에서 봅니다. 요청이 성공(200)해도 메일은 몇 초 뒤에 도착합니다. (발송이 비동기)
- 부하 테스트 앱(`loadtest/`)도 같은 `.env`를 읽으므로 같은 다섯 줄이 있어야 합니다.
- 운영은 Mailpit을 쓰지 않습니다. 아래 "운영 배포 때 바꿀 값"을 봅니다.

### 운영 배포 때 바꿀 값 (SMTP, AWS SES)

운영에는 `.env` 파일이 없을 수 있어서 **서버의 환경 변수**로 같은 이름의 값을 넣습니다. 배포 방식은 아직 정해지지 않았습니다. (`application.yaml` 맨 위 주석 참고)

| 환경 변수 | 로컬 | 운영(AWS SES 예) | 비고 |
|---|---|---|---|
| `MAIL_HOST` | `localhost` | `email-smtp.{리전}.amazonaws.com` | 리전은 SES를 만든 곳(예: `ap-northeast-2`) |
| `MAIL_PORT` | `1025` | `587` (STARTTLS) | `465`(TLS)도 가능하지만 아래 TLS 설정이 달라진다 |
| `MAIL_USERNAME` | 빈 값 | SES **SMTP 자격 증명**의 사용자 이름 | IAM 액세스 키(`AWS_ACCESS_KEY_ID`)와 다른 값이다. SES 콘솔의 SMTP 설정에서 만든다 |
| `MAIL_PASSWORD` | 빈 값 | SES **SMTP 자격 증명**의 비밀번호 | 비밀 값이다. 저장소·로그에 남기지 않는다 |
| `MAIL_FROM` | `no-reply@tickting.local` | SES에서 **확인을 마친** 주소(또는 확인한 도메인의 주소) | 확인 안 된 주소는 SES가 거절한다 |

SES를 쓸 때 서버 밖에서 해 둘 일입니다.
1. SES에서 보낸 사람 도메인(또는 주소)을 **확인**한다. 도메인이면 DNS에 SPF·DKIM 레코드도 넣는다. (안 하면 스팸함으로 가기 쉽다)
2. 새 SES 계정은 **샌드박스**라서 확인된 주소로만 보낼 수 있고 한도도 낮다. 실제 사용자에게 보내려면 **프로덕션 액세스를 신청**한다.
3. SMTP 자격 증명을 만들어 사용자 이름·비밀번호를 위 환경 변수에 넣는다.

Gmail SMTP를 쓴다면 `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_USERNAME`은 계정 주소, `MAIL_PASSWORD`는 계정 비밀번호가 아니라 **앱 비밀번호**(2단계 인증 필요), `MAIL_FROM`은 그 계정 주소입니다. 개인 계정은 하루 발송 한도가 작아서 운영보다는 시험용입니다.

**코드·설정에서 아직 안 된 것 (배포 전에 해야 한다)**
- **암호화·로그인 설정은 넣었습니다.** `application.yaml`에 `mail.smtp.starttls.enable/required`와 `mail.smtp.auth`를 켜 두었고(587 포트 기준, 암호화를 못 하면 보내지 않음), 암호화·로그인이 없는 로컬 Mailpit만 `application-local.yaml`에서 낮춥니다. 로컬 Mailpit에 보내지는 것과, 암호화를 요구하면 암호화를 못 하는 서버에는 보내지 않는 것을 테스트로 확인했습니다. **진짜 암호화 연결(서버가 STARTTLS를 지원하는 경우)로 보내는 것은 아직 확인하지 못했습니다.** 465 포트(처음부터 TLS)는 설정이 다르고(`mail.smtp.ssl.enable`) 넣지 않았습니다.
- **운영 환경에서 프로필을 지정해야 합니다.** 이 프로젝트는 프로필을 안 주면 `local`이 켜지므로(`spring.profiles.default: local`), 운영에서 프로필을 안 정하면 위 로컬 완화(암호화·로그인 끔)가 그대로 적용됩니다. 쿠키 `secure`도 같은 문제입니다. 배포 방식을 정할 때 함께 정합니다.
- 진짜 SMTP 서버로 발송하는 것은 **아직 한 번도 해 보지 않았습니다.** 테스트는 Mailpit으로만 확인했습니다. SES·Gmail의 한도, 응답 시간, 거절 사유는 확인하지 않았고, 위 서비스 정책은 바뀔 수 있으므로 배포 때 공식 문서로 다시 확인합니다.
- 발송이 비동기라 메일 서비스가 거절해도 사용자 화면에는 성공으로 보입니다. 운영에서는 발송 실패 지표(`email.verification.send`)를 볼 수 있어야 하는데, 지표 주소는 아직 열지 않았습니다.

## 2. 컨테이너 켜기

프로젝트 루트에서 실행합니다.

```bash
docker compose up -d
```

`docker compose ps`에서 `postgres`, `redis`가 `healthy`가 될 때까지 기다립니다.

## 3. IntelliJ에서 앱 2개 만들기 (처음 한 번만)

1. 우측 상단 실행 설정 드롭다운 → **구성 편집**(Edit Configurations)을 엽니다.
2. `TicktingApplication`의 **옵션 수정(Alt+M)** → **프로그램 인수**를 켭니다.
3. 이름을 `Tickting-8081`로 바꾸고, 프로그램 인수에 아래를 넣습니다.

```
--server.port=8081
```

4. 왼쪽 위 **복사 아이콘**으로 구성을 복사합니다. 이름은 `Tickting-8082`, 프로그램 인수는 아래로 바꿉니다.

```
--server.port=8082
```

5. 활성화된 프로파일은 비워 둡니다. (비우면 `local`이 적용됩니다.)

## 4. 앱 실행

`Tickting-8081`을 먼저 실행하고, 기동이 끝나면 `Tickting-8082`를 실행합니다.

## 5. 잘 되는지 확인

```bash
curl -i http://localhost:8080/actuator/health
```

`200`이 나오고 응답 헤더 `X-Upstream`에 `8081`과 `8082`가 번갈아 나오면 성공입니다.
앞으로는 `http://localhost:8080`으로 접속하면 됩니다.

## 끄기

```bash
docker compose stop
```

데이터를 처음부터 다시 만들고 싶을 때만 아래를 씁니다. **로컬 DB 데이터와 로그인 세션이 모두 지워집니다.**

```bash
docker compose down -v
```

## 안 될 때

| 증상 | 확인할 것 |
|---|---|
| 접속하면 `502 Bad Gateway` | 앱 2대가 모두 켜져 있는지 확인합니다. 앱이 아직 기동 중이면 잠시 기다립니다. |
| `8080` 포트가 이미 사용 중이라는 오류 | 앱을 8080으로 띄운 실행 설정이 켜져 있는지 확인합니다. 앱은 8081, 8082로만 띄웁니다. |
| 앱이 `Migrations have failed validation`(체크섬 불일치)으로 안 뜸 | 예전에 만든 로컬 DB와 현재 시드가 달라서입니다. 위의 `docker compose down -v` 후 다시 `up -d` 합니다. |
| Windows 방화벽 창이 뜸 | 허용합니다. Nginx 컨테이너가 내 PC의 8081, 8082에 접속해야 합니다. |
| 앱이 `.env`를 못 읽음 | 실행 위치(작업 디렉터리)가 프로젝트 루트인지 확인합니다. |

## 참고

- 앱 2대는 같은 DB·Redis를 씁니다. 로그인 세션이 Redis에 있어서 어느 서버로 요청이 가도 로그인이 유지됩니다.
- 운영도 Nginx로 서버 여러 대를 묶습니다. (`doc/project-log.md`의 로드밸런서 결정 참고)
