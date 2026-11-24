package com.squirrel.tickting.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 휴대폰 번호 형식 검증. 하이픈 없이 숫자만 받는다. (하이픈은 프론트가 표시)
 * 값이 비어 있는지는 검사하지 않으므로 필수 항목에는 @NotBlank 를 같이 붙인다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Pattern(
	regexp = "^01[016789]\\d{7,8}$",
	message = "휴대폰 번호는 하이픈 없이 숫자만 입력해 주세요."
)
public @interface ValidPhone {

	String message() default "";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
