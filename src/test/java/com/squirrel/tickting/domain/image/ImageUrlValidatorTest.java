package com.squirrel.tickting.domain.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.image.validation.ImageUrl;
import com.squirrel.tickting.global.s3.S3Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이미지 주소가 우리 저장소 주소로 시작하는 https 인지 확인한다. 스프링 없이 검증기만 만들어서 본다. */
class ImageUrlValidatorTest {

	private static final String BASE = "https://bucket.s3.ap-northeast-2.amazonaws.com";

	private static boolean valid(String baseUrl, String value) {
		S3Properties properties = new S3Properties("bucket", "ap-northeast-2", "key", "secret", baseUrl);
		return new ImageUrl.Validator(properties).isValid(value, null);
	}

	private static boolean valid(String value) {
		return valid(BASE, value);
	}

	@Test
	@DisplayName("우리 저장소 주소로 시작하는 https 주소는 통과한다 (경로 규칙이 없는 옛 주소도 같은 저장소라 통과한다)")
	void ourUrlPasses() {
		assertThat(valid(BASE + "/public/poster/seller-7/a.jpg")).isTrue();
		assertThat(valid(BASE + "/public/124112.jpg")).isTrue();
		assertThat(valid(BASE + "/public/images+(2).jpg")).isTrue();
	}

	@Test
	@DisplayName("값이 없으면 통과한다 (필수 여부는 다른 검증이 본다)")
	void nullPasses() {
		assertThat(valid(null)).isTrue();
	}

	@Test
	@DisplayName("다른 사이트, http, javascript:, 빈 값, 공백이 든 주소는 거절한다")
	void otherUrlsFail() {
		for (String url : new String[] {"https://cdn.evil.com/a.jpg", "http://bucket.s3.ap-northeast-2.amazonaws.com/public/a.jpg",
			"javascript:alert(1)", "ftp://bucket.s3.ap-northeast-2.amazonaws.com/a.jpg", "", BASE, BASE + "/",
			BASE + "/public/a b.jpg", BASE + "/public/a.jpg\n", " " + BASE + "/public/a.jpg"}) {
			assertThat(valid(url)).as(url).isFalse();
		}
	}

	@Test
	@DisplayName("저장소 주소를 앞에 붙인 다른 사이트는 거절한다")
	void lookalikeHostFails() {
		assertThat(valid(BASE + ".evil.com/a.jpg")).isFalse();
		assertThat(valid(BASE + "@evil.com/a.jpg")).isFalse();
		assertThat(valid(BASE + "evil/a.jpg")).isFalse();
	}

	@Test
	@DisplayName("설정한 저장소 주소 끝의 / 는 무시하고, 저장소 주소 설정이 https 가 아니면 모든 주소를 거절한다")
	void baseUrlSettings() {
		assertThat(valid(BASE + "/", BASE + "/public/a.jpg")).isTrue();
		assertThat(valid("http://localhost:4566/bucket", "http://localhost:4566/bucket/public/a.jpg")).isFalse();
	}
}
