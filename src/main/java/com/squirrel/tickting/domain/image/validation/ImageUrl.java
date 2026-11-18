package com.squirrel.tickting.domain.image.validation;

import com.squirrel.tickting.global.s3.S3Properties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lombok.RequiredArgsConstructor;

/**
 * 우리 이미지 저장소의 주소만 받는다. (IMAGE_BASE_URL + "/" 로 시작하는 https 주소, 공백 없음)
 * 화면이 이 값을 그대로 이미지 주소로 쓰므로 javascript: 같은 값이나 임의의 외부 사이트 주소를 막으려는 것이다.
 * 값이 비어 있는지는 검사하지 않으므로 필수 항목에는 @NotBlank 를 같이 붙인다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ImageUrl.Validator.class)
public @interface ImageUrl {

	String message() default "이미지는 업로드한 사진의 주소만 등록할 수 있습니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/**
	 * 저장소 주소 뒤에 "/" 까지 붙여서 비교한다. 그래야 저장소 주소를 앞에 붙인 다른 사이트(주소.evil.com, 주소@evil.com)가 통과하지 못한다.
	 * 스프링이 검증기를 만들 때 설정값을 넣어 준다.
	 */
	@RequiredArgsConstructor
	class Validator implements ConstraintValidator<ImageUrl, String> {

		private final S3Properties s3Properties;

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			String prefix = s3Properties.baseUrl() + "/";
			return prefix.startsWith("https://")
				&& value.length() > prefix.length()
				&& value.startsWith(prefix)
				&& value.chars().noneMatch(Character::isWhitespace);
		}
	}
}
