package com.squirrel.tickting.domain.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.s3.S3Properties;
import com.squirrel.tickting.global.s3.S3Uploader;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * 이미지 업로드(IMG-01). S3 는 올린 것을 기록만 하는 가짜로 바꿔서 AWS 를 부르지 않는다. (실제 S3 로는 따로 확인했다. project-log 참고)
 * 계정 종류별 경로 규칙은 SecurityConfigTest 가 확인하므로 여기서는 "로그인만 하면 통과하고, 용도는 서비스가 막는지"를 본다.
 * MockMvc 는 서블릿 컨테이너의 multipart 크기 제한을 거치지 않아서, 그 제한(spring.servlet.multipart)은 여기서 보지 못한다.
 */
@Import(ImageUploadApiTest.FakeS3Config.class)
class ImageUploadApiTest extends IntegrationTestSupport {

	private static final long TEN_MB = 10L * 1024 * 1024;
	private static final String BASE = "https://cdn.example.com/";
	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	record Uploaded(String key, String contentType, byte[] content) {
	}

	/** 올린 것을 기록만 하는 S3. 부모의 init() 은 AWS 를 부르지 않고 클라이언트 객체만 만든다. */
	static class FakeS3Uploader extends S3Uploader {

		final List<Uploaded> uploads = new ArrayList<>();
		boolean fail;

		FakeS3Uploader(S3Properties properties) {
			super(properties);
		}

		@Override
		public String upload(String key, String contentType, byte[] content) {
			if (fail) {
				throw new BusinessException(CommonExceptionCode.INTERNAL_ERROR);
			}
			uploads.add(new Uploaded(key, contentType, content));
			return "https://cdn.example.com/" + key;
		}
	}

	@TestConfiguration
	static class FakeS3Config {

		@Bean
		@Primary
		FakeS3Uploader fakeS3Uploader(S3Properties properties) {
			return new FakeS3Uploader(properties);
		}
	}

	@Autowired
	FakeS3Uploader fakeS3;

	@BeforeEach
	void resetFake() {
		fakeS3.uploads.clear();
		fakeS3.fail = false;
	}

	private String token(AccountType type, long id) {
		boolean seller = type == AccountType.SELLER;
		return "Bearer " + jwtProvider.createAccessToken(
			new CustomUserDetails(id, type, seller ? "STAFF" : null, seller ? 791L : null));
	}

	private ResultActions upload(String token, String purpose, MockMultipartFile file) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/images");
		if (file != null) {
			request.file(file);
		}
		if (purpose != null) {
			request.param("purpose", purpose);
		}
		if (token != null) {
			request.header("Authorization", token);
		}
		return mockMvc.perform(request);
	}

	private static MockMultipartFile image(String contentType, byte[] content) {
		return new MockMultipartFile("file", "원본 이름(1).bin", contentType, content);
	}

	@Test
	@DisplayName("판매자는 포스터·상세, 예매자는 프로필을 올리고, 경로에 용도와 계정이 들어가며 형식에 맞는 확장자가 붙는다")
	void upload() throws Exception {
		byte[] poster = {1, 2, 3};
		var result = upload(token(AccountType.SELLER, 7), "POSTER", image("image/jpeg", poster))
			.andExpect(status().isOk()).andReturn();
		String imageUrl = read(result, "$.data.imageUrl");

		assertThat(imageUrl).matches(BASE + "public/poster/seller-7/" + UUID_PATTERN + "\\.jpg");
		Uploaded saved = fakeS3.uploads.get(0);
		assertThat(BASE + saved.key()).isEqualTo(imageUrl);
		assertThat(saved.contentType()).isEqualTo("image/jpeg");
		assertThat(saved.content()).isEqualTo(poster);

		assertThat(read(upload(token(AccountType.SELLER, 7), "DETAIL", image("image/png", new byte[] {1}))
			.andExpect(status().isOk()).andReturn(), "$.data.imageUrl"))
			.startsWith(BASE + "public/detail/seller-7/").endsWith(".png");
		assertThat(read(upload(token(AccountType.USER, 5), "PROFILE", image("image/webp", new byte[] {1}))
			.andExpect(status().isOk()).andReturn(), "$.data.imageUrl"))
			.startsWith(BASE + "public/profile/user-5/").endsWith(".webp");
	}

	@Test
	@DisplayName("용도에 맞지 않는 계정은 403 이고 올리지 않는다 (예매자의 포스터·상세, 판매자의 프로필, 관리자는 모두)")
	void purposeMismatch() throws Exception {
		String[][] cases = {{"USER", "POSTER"}, {"USER", "DETAIL"}, {"SELLER", "PROFILE"},
			{"ADMIN", "POSTER"}, {"ADMIN", "DETAIL"}, {"ADMIN", "PROFILE"}};
		for (String[] c : cases) {
			upload(token(AccountType.valueOf(c[0]), 5), c[1], image("image/jpeg", new byte[] {1}))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH100"));
		}
		assertThat(fakeS3.uploads).isEmpty();
	}

	@Test
	@DisplayName("로그인하지 않으면 401 이다")
	void unauthenticated() throws Exception {
		upload(null, "PROFILE", image("image/jpeg", new byte[] {1}))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH200"));
		assertThat(fakeS3.uploads).isEmpty();
	}

	@Test
	@DisplayName("jpg, png, webp 가 아닌 형식이거나 형식이 없거나 빈 파일이면 400 이고 올리지 않는다")
	void invalidFile() throws Exception {
		String user = token(AccountType.USER, 5);
		for (String type : new String[] {"image/gif", "image/svg+xml", "application/pdf", "image/JPEG", "text/html", null}) {
			upload(user, "PROFILE", image(type, new byte[] {1}))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		}
		upload(user, "PROFILE", image("image/jpeg", new byte[0])).andExpect(status().isBadRequest());
		upload(user, "PROFILE", null).andExpect(status().isBadRequest());
		assertThat(fakeS3.uploads).isEmpty();
	}

	@Test
	@DisplayName("10MB 는 올라가고 1바이트라도 넘으면 400 이다")
	void sizeLimit() throws Exception {
		String user = token(AccountType.USER, 5);
		upload(user, "PROFILE", image("image/jpeg", new byte[(int) TEN_MB])).andExpect(status().isOk());
		upload(user, "PROFILE", image("image/jpeg", new byte[(int) TEN_MB + 1]))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		assertThat(fakeS3.uploads).hasSize(1);
	}
}
