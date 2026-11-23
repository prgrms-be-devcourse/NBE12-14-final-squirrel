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

## 1-2. 메일(Mailpit) 추가 (이메일 인증이 들어오면 필요, 초안)

> **구현 전 초안입니다.** 이메일 인증(`doc/api-spec.md` AUTH-17·18)이 들어오면, 예매하기를 누르려면 먼저 이메일 인증을 해야 합니다. 로컬은 대기열이 기본으로 켜져 있어서, **화면으로 예매를 눌러 보는 사람은 모두** 아래 설정이 필요합니다. 포트와 이미지 버전은 구현할 때 Mailpit 공식 안내로 확인하고 이 문서를 고칩니다.

Mailpit은 개발용 가짜 메일 서버입니다. 앱이 보낸 인증번호 메일을 밖으로 내보내지 않고 받아 두었다가 브라우저로 보여 줍니다. (가짜 주소로 가입해도 진짜 메일이 나가지 않습니다)

`docker-compose.yml`의 `services:` 아래(`nginx:` 다음)에 붙여 넣습니다.

```yaml
  mailpit:
    image: axllent/mailpit
    container_name: tickting-mailpit
    restart: unless-stopped
    ports:
      - "127.0.0.1:1025:1025"
      - "127.0.0.1:8025:8025"
```

- `1025`: 앱이 메일을 보내는 문(SMTP), `8025`: 받은 메일을 보는 화면입니다. 브라우저에서 `http://localhost:8025`를 열면 인증번호 메일이 보입니다.
- `.env`에 아래 네 줄을 추가합니다. (로컬은 로그인이 없어 사용자·비밀번호는 비워 둡니다. 값이 없으면 앱이 뜨지 않습니다.)

```
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
```

- 부하 테스트 앱(`loadtest/`)도 같은 `.env`를 읽으므로 같은 네 줄이 있어야 합니다.
- 운영은 Mailpit을 쓰지 않습니다. 배포 서버의 환경 변수에 진짜 메일 서버(Gmail SMTP, AWS SES 등) 값을 넣습니다. (배포 담당과 정함)

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
