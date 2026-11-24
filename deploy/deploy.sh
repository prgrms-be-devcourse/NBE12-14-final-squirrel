#!/usr/bin/env bash
# 서버(EC2)에서 실행하는 배포 스크립트. GitHub Actions 가 SSH 로 이 스크립트를 부른다. 사람이 서버에서 직접 불러도 된다.
#   사용법: /opt/tickting/deploy.sh <이미지 태그>      예) /opt/tickting/deploy.sh a1b2c3d4e5f6
#
# 하는 일: 새 이미지를 받고 → 이미지 안의 배포 파일(compose·Nginx 설정)을 꺼내 놓고 → .env 의 APP_IMAGE 를 바꾸고 → 컨테이너를 교체한다.
#          앱이 건강해지지 않으면 이전 이미지로 되돌리고 실패로 끝낸다.
#
# 배포 전용 SSH 키는 서버의 ~/.ssh/authorized_keys 에서 이 스크립트 하나만 실행하도록 제한한다.
#   command="/opt/tickting/deploy.sh",restrict ssh-ed25519 AAAA... github-actions-deploy
# 그러면 SSH 로 넘길 수 있는 것은 이미지 태그뿐이다. (SSH_ORIGINAL_COMMAND 로 들어온다) 키가 유출돼도 임의의 명령이나 컨테이너를 실행할 수 없다.
# 그래서 설정 파일(compose.prod.yaml 등)을 SSH 로 보내지 않고, 이미 Docker Hub 에서 받은 이미지 안에서 꺼내 쓴다.
#
# 이 스크립트는 이미지 안에 들어 있지만 서버에서 자동으로 바뀌지 않는다. 고치면 서버의 파일을 직접 다시 놓는다.
set -Eeuo pipefail

DIR="${DEPLOY_DIR:-/opt/tickting}"
cd "$DIR"

# 배포가 겹치면 .env 와 컨테이너가 꼬인다. 이미 진행 중이면 바로 실패한다.
exec 9>"$DIR/.deploy.lock"
flock -n 9 || { echo "다른 배포가 진행 중입니다."; exit 1; }

# 태그만 받는다. 형식을 엄격히 검사해서 .env 나 명령에 이상한 문자가 들어가지 못하게 한다.
TAG="${SSH_ORIGINAL_COMMAND:-${1:-}}"
if [[ ! "$TAG" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]]; then
	echo "이미지 태그가 올바르지 않습니다."
	exit 1
fi

# 이미지 저장소는 .env 의 APP_IMAGE 에서 읽는다. (태그만 바꾼다. 다른 저장소로 배포할 수 없다)
PREV="$(grep '^APP_IMAGE=' .env | cut -d= -f2-)"
[[ -n "$PREV" ]] || { echo ".env 에 APP_IMAGE 가 없습니다."; exit 1; }
REPO="${PREV%:*}"
NEW="$REPO:$TAG"
echo "배포: $NEW (이전: $PREV)"

docker pull "$NEW"

# 이미지 안의 /opt/deploy 에 이 커밋의 compose·Nginx 설정이 들어 있다. 꺼내서 서버의 파일을 바꾼다.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
CID="$(docker create "$NEW")"
docker cp "$CID:/opt/deploy/." "$TMP"
docker rm "$CID" >/dev/null
install -m 644 "$TMP/compose.prod.yaml" "$DIR/compose.prod.yaml"
install -D -m 644 "$TMP/nginx/conf.d/api.conf" "$DIR/nginx/conf.d/api.conf"

COMPOSE=(docker compose -f "$DIR/compose.prod.yaml")

# 컨테이너를 새 이미지로 교체한다. 이미지 이름은 compose 가 .env 의 APP_IMAGE 에서 읽는다.
set_image() { sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$1|" .env; }
set_image "$NEW"

# 새 이미지가 건강하지 않으면 이전 이미지로 되돌리고 실패로 끝낸다.
# 되돌리면 새 컨테이너와 로그가 사라지므로, 지우기 전에 마지막 로그를 보여 준다. (배포 로그가 GitHub 에 남아서 임시 비밀번호 줄은 가린다)
rollback() {
	echo "앱이 건강해지지 않았습니다. 이전 이미지($PREV)로 되돌립니다."
	APP_CID="$("${COMPOSE[@]}" ps -aq app || true)"
	[[ -z "$APP_CID" ]] || docker logs --tail 60 "$APP_CID" 2>&1 | grep -v 'generated security password' || true
	set_image "$PREV"
	"${COMPOSE[@]}" up -d || true
	exit 1
}

# Nginx 가 앱이 건강해진 뒤에 뜨도록 걸려 있어서(depends_on), 앱이 건강해지지 않으면 up 이 여기서 실패한다.
# set -e 로 그냥 끝나면 되돌리지 못하고 고장 난 이미지가 남는다.
"${COMPOSE[@]}" up -d || rollback

# Nginx 는 설정 파일이 바뀌어도 컨테이너가 그대로라 다시 읽게 한다. (설정이 틀리면 reload 하지 않고 실패한다)
if [[ -n "$("${COMPOSE[@]}" ps -q nginx)" ]]; then
	"${COMPOSE[@]}" exec -T nginx nginx -t
	"${COMPOSE[@]}" exec -T nginx nginx -s reload
fi

# 앱이 건강해질 때까지 기다린다. (Flyway 와 기동에 시간이 걸린다) 최대 5분
healthy=0
for _ in $(seq 1 60); do
	APP_CID="$("${COMPOSE[@]}" ps -q app)"
	status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$APP_CID" 2>/dev/null || echo none)"
	if [[ "$status" == "healthy" ]]; then
		healthy=1
		break
	fi
	sleep 5
done

if [[ "$healthy" != "1" ]]; then
	rollback
fi

# 디스크가 작아서(16GB) 옛 이미지가 쌓이지 않게 정리한다. 이틀 안에 만든 이미지(되돌릴 때 쓸 수 있는 것)는 남긴다.
docker image prune -af --filter "until=48h" >/dev/null || true

echo "배포 완료: $NEW"
