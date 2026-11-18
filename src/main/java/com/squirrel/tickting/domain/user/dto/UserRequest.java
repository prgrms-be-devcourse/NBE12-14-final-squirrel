package com.squirrel.tickting.domain.user.dto;

import com.squirrel.tickting.domain.image.validation.ImageUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserRequest {

	private UserRequest() {
	}

	/**
	 * 바꿀 수 있는 값 전체를 받아 통째로 바꾼다. 빈 값 확인을 여기서 끝내서 엔티티는 받은 값을 넣기만 한다.
	 * 닉네임 규칙은 가입(AuthRequest.UserSignup)과 같게 맞췄다. 수정으로 가입 규칙을 우회하지 못하게 하려는 것이다.
	 */
	@Schema(name = "UserUpdateMeRequest", description = "내 정보 수정 요청. 닉네임·프로필 이미지를 통째로 바꾼다")
	public record UpdateMe(

		@NotBlank(message = "닉네임을 입력해 주세요.")
		@Size(min = 2, max = 20, message = "닉네임은 2글자 이상 20글자 이하로 입력해 주세요.")
		@Schema(description = "새 닉네임 (필수)", example = "길동이2")
		String nickname,

		/* 이미지 업로드(IMG-01)로 올린 우리 저장소의 주소만 받는다. 화면이 이 값을 그대로 이미지 주소로 쓰기 때문이다. */
		@Size(max = 2048, message = "프로필 이미지 주소는 2048자 이하로 입력해 주세요.")
		@ImageUrl
		@Schema(description = "새 프로필 이미지 주소. 이미지 업로드 주소 발급(IMG-01)의 imageUrl 값. 생략하거나 null 이면 사진 없음으로 저장된다",
			example = "https://cdn.example.com/public/profile/user-5/3f2a9c1e.png")
		String profileImg
	) {
	}
}
