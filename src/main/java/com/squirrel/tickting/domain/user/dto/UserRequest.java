package com.squirrel.tickting.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UserRequest {

	private UserRequest() {
	}

	/**
	 * 보낸 필드만 수정한다. 그래서 두 필드 모두 생략(null)할 수 있고, 검증 어노테이션은 값이 있을 때만 동작한다.
	 * 닉네임 길이 규칙은 가입(AuthRequest.UserSignup)과 같게 맞췄다. 수정으로 가입 규칙을 우회하지 못하게 하려는 것이다.
	 */
	@Schema(name = "UserUpdateMeRequest", description = "내 정보 수정 요청. 보낸 필드만 바뀐다")
	public record UpdateMe(

		@Size(min = 2, max = 20, message = "닉네임은 2글자 이상 20글자 이하로 입력해 주세요.")
		@Pattern(regexp = ".*\\S.*", message = "닉네임은 공백만으로 입력할 수 없습니다.")
		@Schema(description = "새 닉네임. 생략하면 그대로 둔다", example = "길동이2")
		String nickname,

		/* 화면이 이 값을 그대로 이미지 주소로 쓰기 때문에 javascript: 같은 값이 들어오지 못하게 http(s) 만 받는다. */
		@Size(max = 2048, message = "프로필 이미지 주소는 2048자 이하로 입력해 주세요.")
		@Pattern(regexp = "^$|^https?://\\S+$", message = "프로필 이미지는 http 또는 https 주소로 입력해 주세요.")
		@Schema(description = "새 프로필 이미지 주소. 생략하면 그대로 두고, 빈 문자열(\"\")이면 지운다",
			example = "https://cdn.example.com/profiles/5.png")
		String profileImg
	) {
	}
}
