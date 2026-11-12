# tickting 프로젝트 가이드

티켓 예매 백엔드 (취업 포트폴리오). 핵심은 **좌석 동시성 처리, 대기열, 매크로 방어, 토스 결제 연동**이다.
이 문서는 팀 공통 컨벤션이다. 바꾸고 싶으면 PR로 고친다.

## 설계 문서 (기준)

- 설계의 기준은 `doc/`다. 테이블·흐름·정책은 `doc/part1~5-*.md`와 `doc/appendix.md`를 먼저 본다.
- 결정 사항과 남은 일은 `doc/project-log.md`, 나중에 할 것은 `doc/later/`에 있다.
- **코드와 문서가 다르면 추측하지 말고 문서를 확인한다.** 설계를 바꿔야 하면 코드만 고치지 말고 문서도 같이 고친다.
- `erd-ticketing.md`, `table-guide-for-team.md`는 폐기된 문서다. 참고하지 않는다.

## 빌드·실행

- JDK 25, Spring Boot 4.1.2, Gradle(Groovy DSL). 패키지는 `com.squirrel.tickting`.
- 빌드: `./gradlew build`, 컴파일만: `./gradlew compileJava`, 테스트: `./gradlew test`
- DB는 PostgreSQL이다. 테스트는 Testcontainers를 쓰므로 Docker가 켜져 있어야 한다.
- 터미널에서 `JAVA_HOME`이 JDK 25를 가리켜야 `./gradlew`가 동작한다. IntelliJ는 프로젝트 SDK를 25로 지정한다.

## 패키지 구조

기능은 도메인 폴더 안에 세로로 넣는다.

```
com.squirrel.tickting
  domain/{도메인}
    controller      # API
    service         # 비즈니스 로직 (자기 도메인만)
    facade          # 여러 도메인의 서비스를 엮는 흐름 (필요할 때만 만든다)
    dto             # 요청/응답 (XxxRequest, XxxResponse)
    infra/entity    # JPA 엔티티
    infra/repository
    type            # enum 등
  global
    config          # 설정
    entity          # BaseTimeEntity, SoftDeletableEntity
    exception       # BusinessException, ExceptionCode, GlobalExceptionHandler
    exception/code  # 도메인별 XxxExceptionCode
    response        # ApiResponse
```

- `global`에는 도메인 로직을 넣지 않는다.
- **서비스는 다른 서비스를 호출하지 않는다.** 서비스는 자기 도메인의 repository만 쓴다. (서로 부르기 시작하면 순환 의존과 트랜잭션 경계가 흐려진다)
- 여러 도메인이 얽히는 흐름(가입 = 약관 + 회원 + 토큰 등)은 **파사드(`facade`)**가 각 서비스를 순서대로 호출한다. 컨트롤러는 파사드(또는 서비스 하나)만 부른다.
- 트랜잭션이 여러 서비스에 걸치면 파사드에서 `TransactionTemplate`으로 묶는다. 서비스의 `@Transactional`은 그 트랜잭션에 참여한다.
- 남의 도메인 repository를 직접 쓰지 않는다.

## 응답과 예외

- 모든 응답은 `ApiResponse<T>` (`success`, `code`, `message`, `data`)로 내린다. 컨트롤러는 `ResponseEntity<ApiResponse<...>>`를 반환한다.
- 비즈니스 규칙 위반은 `throw new BusinessException(XxxExceptionCode.XXX)` 한 줄로 던진다. 응답 변환은 `GlobalExceptionHandler`가 한다.
- 에러 코드 enum은 도메인마다 `global/exception/code/XxxExceptionCode`에 만들고 `ExceptionCode`를 구현한다.
- 코드 형식은 **접두사 + 3자리 숫자**다. (`COMMON000`, `AUTH001`, `BOOKING000`) 코드마다 `/** ... */` 주석으로 언제 쓰는지 적는다.
- 5xx 원인(서버 내부 메시지)은 클라이언트에 노출하지 않는다.

## 엔티티

- `BaseTimeEntity`(created_at, updated_at)를 상속한다. 소프트 삭제가 필요하면 `SoftDeletableEntity`를 상속한다.
- ID는 `Long`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`.
- 어노테이션: `@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED) @Builder`. **setter는 만들지 않는다.** 값 변경은 `post.update(...)`처럼 의도가 드러나는 메서드로 한다.
- enum 컬럼은 `@Enumerated(EnumType.STRING)`. 금액은 `Integer`(원).
- **연관관계**: `@ManyToOne(fetch = LAZY)`로 연결하되 **DB에는 FK 제약을 걸지 않는다**(논리 FK). 단 행이 매우 많은 테이블(`schedule_seat`, `booking_seat`)은 연관관계 없이 `Long` ID만 가진다.
- **소프트 삭제**는 전역 필터(`@SQLRestriction`)를 쓰지 않는다. 조회 메서드 이름에 조건을 드러낸다. (`findByIdAndDeletedAtIsNull`)
- 동시에 바뀌는 값(수량, 카운터)은 엔티티에서 더하지 않고 `UPDATE ... SET x = x + 1`로 DB에서 바꾼다.

## 비밀 값·환경 설정

- **`.env`에 있어야 할 값(비밀번호, 키, 접속 정보)은 코드·yaml·테스트에 적지 않는다.** `${KEY:기본값}`처럼 대체값을 두지 않는다. (키가 없으면 앱이 뜨지 않는 게 맞다. 저장소에 값이 남으면 유출이다)
- 비밀 값이 필요한 테스트는 값을 코드에 쓰지 말고 **실행할 때 무작위로 만든다.** (`TestSecrets` 참고)
- `.env`와 `docker-compose.yml`은 git에 올리지 않는다. 필요한 키 이름은 `application-local.yaml` 주석에 적는다.
- 로그에 개인정보·토큰·키를 남기지 않는다. 비밀번호는 BCrypt, 본인인증 CI는 해시만 저장한다. 운영의 JWT 키는 로컬과 다른 값을 쓴다.

## 서버가 여러 대로 늘어난다 (운영은 ALB + Auto Scaling, 로컬은 Nginx + 앱 여러 개)

- 앱은 상태를 갖지 않는다. 로그인·대기열·잠금 정보를 서버 메모리에 두지 않고 DB나 Redis에 둔다. 어느 서버로 요청이 가도 같은 결과가 나와야 한다.
- 외부 호출(토스 등)은 DB 트랜잭션 밖에서 한다. 외부 서비스는 인터페이스 뒤에 두고 Mock 구현부터 만든다. (`IdentityVerifier` 참고)

## DB·마이그레이션

- 스키마 변경은 **Flyway**만 쓴다. `src/main/resources/db/migration/V{n}__설명.sql`.
- **이미 적용된 파일은 수정하지 않는다.** 바꿀 것이 있으면 다음 번호로 새 파일을 만든다. (체크섬 오류가 난다)
- 엔티티를 바꾸면 SQL 마이그레이션도 같이 만든다. `ddl-auto`는 `validate`다.
- 부분 UNIQUE 인덱스, `jsonb`, `fillfactor` 같은 PostgreSQL 기능은 SQL로 만든다. (설계 문서 참고)
- 데모·샘플 데이터(공연장 시드 등)는 `db/seed`에 두고 로컬에서만 적용한다. 스키마 변경을 시드 파일에 넣지 않는다.
- 조회에 쓰는 컬럼은 인덱스를 직접 만든다. (논리 FK라 자동으로 생기지 않는다)
- **중복(UNIQUE) 에러는 인덱스 이름으로 연결한다.** 서비스가 미리 확인해도 동시 요청은 뚫을 수 있어서 마지막 방어선은 DB의 UNIQUE 인덱스다. 사용자에게 구체적으로 알려야 하는 인덱스는 `UniqueConstraintCodes`에 `인덱스 이름 → 에러 코드`로 한 줄 등록한다. (등록하지 않으면 일반 충돌 `COMMON003`) 서비스에서 실패 후 다시 조회하거나 try/catch 하지 않는다.

## DTO·컨트롤러·서비스

- 요청/응답 DTO는 `record`다. 도메인마다 `XxxRequest`, `XxxResponse`(안에 용도별 record)를 둔다. 응답 record에는 `from(엔티티)` 정적 메서드를 둔다.
- 요청 검증은 `@Valid` + `jakarta.validation` 어노테이션, 메시지는 한국어로 쓴다.
- API 경로는 `/api/v1/` 아래에 둔다. 관리자는 `/admin/**`, 판매자는 `/seller/**`로 구분한다. (임시)
- 서비스는 `@Service @RequiredArgsConstructor`. 쓰기는 `@Transactional`, 읽기는 `@Transactional(readOnly = true)`.
- 의존성은 `@RequiredArgsConstructor`로 만든 생성자 주입만 쓴다. **생성자를 직접 쓰지 않는다.** 생성 후 초기화가 필요하면 `@PostConstruct`를 쓰거나 그 일을 맡는 별도 컴포넌트로 뺀다. (`PasswordVerifier` 참고)
- 복잡한 조회는 `@Query` 텍스트 블록으로 쓰고 페이징은 `countQuery`를 분리한다.

## 코드 스타일

- 들여쓰기는 **탭**이다. IDE 설정을 확인한다.
- Lombok을 쓴다. IDE에서 Lombok 플러그인과 Annotation Processing을 켠다.
- 주석은 한국어로, **"무엇"이 아니라 "왜"**를 적는다. 코드만 봐서는 알 수 없는 이유와 제약을 남긴다.
- 사용하지 않는 import, 주석 처리된 코드, 디버그 출력은 남기지 않는다.

## 테스트

- 서비스 단위 테스트: Mockito(`@ExtendWith(MockitoExtension.class)`).
- 리포지토리·통합 테스트: 실제 PostgreSQL(Testcontainers).
- 좌석 점유, 예매, 1인 최대 매수처럼 **동시성이 걸리는 로직은 동시성 테스트를 만든다.**
- 테스트 메서드에는 한국어 `@DisplayName`을 붙여 무엇을 검증하는지 적는다.

## Git·협업

- `dev`가 개발 통합 브랜치다. `main`과 `dev`에는 직접 push하지 않고, 작업 브랜치에서 **`dev`로 PR**을 올린다. (`origin/dev` 기준으로 브랜치를 딴다)
- 브랜치: `feat/…`, `fix/…`, `docs/…`, `chore/…` (예: `feat/seat-hold`)
- 커밋 메시지: `feat: 좌석 점유 기능 추가`처럼 `타입: 내용`. 타입은 `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.
- PR은 **기능 하나 단위**로 작게 올린다. 리뷰어 1명 이상의 승인을 받고 머지한다.
- 리뷰 기준은 "동작하는가"보다 먼저 **"설계 문서와 같은가"**다.
- 구현 전에 API 명세(요청·응답 예시)를 먼저 맞춘다.

## Claude에게

- 작업 전에 관련된 `doc/` 문서의 해당 부분을 읽는다.
- 설계 문서와 어긋나는 구현이 필요하면 임의로 정하지 말고 먼저 물어본다.
- 모르는 값(정책, 수치)을 추측해서 코드에 넣지 않는다. 문서의 "임시"/"미정" 표시를 확인한다.
