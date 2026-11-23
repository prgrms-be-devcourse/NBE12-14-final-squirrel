# 운영용 앱 이미지. 빌드는 GitHub Actions 가 ./gradlew bootJar 로 jar 를 만든 뒤 이 파일로 이미지만 만든다.
# (EC2 에서 Gradle 빌드를 하지 않는다. t3 급 서버는 메모리와 CPU 크레딧이 모자라 서비스가 느려지거나 죽을 수 있다)
# 비밀 값(DB 비밀번호, JWT 키, S3·토스 키)은 이미지에 넣지 않는다. 서버의 .env 를 compose 가 실행할 때 넣어 준다.
FROM eclipse-temurin:25-jre

# 시간대: 서버·컨테이너 기본은 UTC 라서 판매 시작·종료, 회차 일시, 예매번호 날짜가 한국 시간과 9시간 어긋난다.
# 메모리: 컨테이너 한도의 60% 를 힙으로 쓴다. 한도가 작은 서버(t3.small/medium)에서 Redis·Nginx 와 함께 돌아도 메모리가 남도록 한다.
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -Duser.timezone=Asia/Seoul"

# root 로 실행하지 않는다. 앱이 뚫려도 컨테이너 안에서 할 수 있는 일을 줄인다.
RUN useradd --system --uid 10001 --create-home app

WORKDIR /app
COPY --chown=app:app build/libs/tickting-0.0.1-SNAPSHOT.jar app.jar

USER app
EXPOSE 8080

# 앱이 뜬 뒤에만 "정상"이다. 이 이미지에는 curl 이 없어서 bash 의 TCP 연결로 헬스 엔드포인트를 읽는다.
# start-period 동안은 실패해도 세지 않는다. (Flyway 마이그레이션과 기동에 시간이 걸린다)
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

# exec 로 java 가 PID 1 이 되게 해서 종료 신호(SIGTERM)를 직접 받고 정상 종료한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
