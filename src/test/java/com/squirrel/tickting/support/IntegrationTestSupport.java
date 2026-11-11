package com.squirrel.tickting.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.TestcontainersConfiguration;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.infra.repository.TermsRepository;
import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 PostgreSQL(Testcontainers) 위에서 Flyway 마이그레이션까지 적용한 상태로 API 를 호출하는 통합 테스트의 공통 틀.
 * 테스트마다 계정·약관 데이터를 비우고 약관을 새로 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestSupport {

	protected static final String PASSWORD = "password1!";

	/** 서명 키는 코드에 적지 않고 실행할 때 만든다. (DB 는 Testcontainers 가 연결 정보를 덮어쓴다) */
	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		registry.add("jwt.secret", TestSecrets::randomJwtSecret);
	}

	private static final Pattern REFRESH_COOKIE = Pattern.compile("refreshToken=([^;]*)");

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected TermsRepository termsRepository;

	@Autowired
	protected JwtProvider jwtProvider;

	@Autowired
	protected JwtProperties jwtProperties;

	@Autowired
	protected ObjectMapper objectMapper;

	/** 필수 약관(서비스 이용, 개인정보)의 번호 */
	protected List<Long> requiredTermIds;
	/** 선택 약관(탈퇴 약관)의 번호 */
	protected Long optionalTermId;

	@BeforeEach
	void resetDatabase() {
		jdbc.execute("""
			TRUNCATE TABLE user_member, user_terms_agreement, terms, seller_business, seller_member,
			               seller_invitation, admin_member RESTART IDENTITY
			""");
		Long service = saveTerms(TermsType.SERVICE, "1.0", true);
		Long privacy = saveTerms(TermsType.PRIVACY, "1.0", true);
		requiredTermIds = List.of(service, privacy);
		optionalTermId = saveTerms(TermsType.WITHDRAW, "1.0", false);
	}

	protected Long saveTerms(TermsType type, String version, boolean required) {
		return termsRepository.save(Terms.builder()
			.type(type).version(version).content(type + " " + version).required(required)
			.build()).getId();
	}

	// ---- 요청 만들기 ----

	protected static String identityToken(String name, String ci, String phone) {
		return "mock|" + name + "|" + ci + "|" + phone + "|19900101";
	}

	protected Map<String, Object> userSignupBody(String email, String ci) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("nickname", "tester");
		body.put("identityToken", identityToken("홍길동", ci, "01012345678"));
		body.put("agreedTermIds", requiredTermIds);
		return body;
	}

	protected Map<String, Object> sellerSignupBody(String email, String ci, String businessNumber) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("identityToken", identityToken("김대표", ci, "01099998888"));
		body.put("companyName", "(주)가상기획");
		body.put("representativeName", "김대표");
		body.put("businessNumber", businessNumber);
		body.put("contactPhone", "01011112222");
		body.put("contactEmail", "Contact@Company.com");
		body.put("termsAgreed", true);
		return body;
	}

	protected Map<String, Object> loginBody(String email, String password) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		return body;
	}

	protected ResultActions postJson(String url, Object body) throws Exception {
		return mockMvc.perform(post(url)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	// ---- 응답 읽기 ----

	protected static String read(MvcResult result, String jsonPath) throws Exception {
		Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
		return String.valueOf(value);
	}

	protected static String accessToken(MvcResult result) throws Exception {
		return read(result, "$.data.accessToken");
	}

	/** Set-Cookie 헤더에서 리프레시 토큰 값을 꺼낸다. 쿠키가 없으면 null */
	protected static String refreshCookie(MvcResult result) {
		String header = result.getResponse().getHeader("Set-Cookie");
		if (header == null) {
			return null;
		}
		Matcher matcher = REFRESH_COOKIE.matcher(header);
		return matcher.find() ? matcher.group(1) : null;
	}

	protected long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	// ---- 동시성 ----

	/** 같은 순간에 시작하도록 맞춰 여러 스레드에서 작업을 실행하고 결과를 모은다. */
	protected <T> List<T> runConcurrently(int threads, IntFunction<Callable<T>> taskFactory) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<T> task = taskFactory.apply(i);
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await();
			start.countDown();

			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}
}
