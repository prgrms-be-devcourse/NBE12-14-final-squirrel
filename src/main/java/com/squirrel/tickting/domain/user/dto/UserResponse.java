package com.squirrel.tickting.domain.user.dto;

import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;

public class UserResponse {

	private UserResponse() {
	}

	/**
	 * 로그인한 예매자 본인의 정보. 본인에게만 보여 주는 값이라 이름·휴대폰도 그대로 준다.
	 * 비밀번호와 본인인증 해시(ciHash)는 어느 응답에도 넣지 않는다.
	 */
	@Builder
	@Schema(name = "UserMe", description = "내 정보")
	public record Me(
		@Schema(description = "회원 번호")
		Long userId,
		@Schema(description = "이메일 (로그인 아이디)", example = "user@example.com")
		String email,
		@Schema(description = "이름. 본인인증으로 확인된 값", example = "홍길동")
		String name,
		@Schema(description = "닉네임", example = "길동이")
		String nickname,
		@Schema(description = "휴대폰 번호. 본인인증으로 확인된 값", example = "01012345678")
		String phone,
		@Schema(description = "프로필 이미지 주소. 없으면 null")
		String profileImg,
		@Schema(description = "생년월일", example = "1990-01-01")
		LocalDate birthDate,
		@Schema(description = "가입 일시")
		LocalDateTime createdAt
	) {

		public static Me from(UserMember user) {
			return Me.builder()
				.userId(user.getId())
				.email(user.getEmail())
				.name(user.getName())
				.nickname(user.getNickname())
				.phone(user.getPhone())
				.profileImg(user.getProfileImg())
				.birthDate(user.getBirthDate())
				.createdAt(user.getCreatedAt())
				.build();
		}
	}

	/** 내 정보 수정 결과. 바꿀 수 있는 값과 수정 일시만 준다. (API 명세 AUTH-07) */
	@Builder
	@Schema(name = "UserUpdatedMe", description = "수정된 내 정보")
	public record UpdatedMe(
		@Schema(description = "회원 번호")
		Long userId,
		@Schema(description = "닉네임", example = "길동이2")
		String nickname,
		@Schema(description = "프로필 이미지 주소. 없으면 null")
		String profileImg,
		@Schema(description = "수정 일시")
		LocalDateTime updatedAt
	) {

		public static UpdatedMe from(UserMember user) {
			return UpdatedMe.builder()
				.userId(user.getId())
				.nickname(user.getNickname())
				.profileImg(user.getProfileImg())
				.updatedAt(user.getUpdatedAt())
				.build();
		}
	}
}
