package com.squirrel.tickting.domain.user.infra.entity;

import com.squirrel.tickting.domain.user.type.UserStatus;
import com.squirrel.tickting.global.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 예매자 계정. 로그인 아이디는 이메일(소문자로 저장)이고, 이름과 휴대폰은 가입할 때 입력한 값이다. */
@Entity
@Table(name = "user_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UserMember extends SoftDeletableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(length = 2048)
	private String profileImg;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	/** 가입 이메일의 주인임을 인증한 시각. 가입 전에 인증을 마쳐야 가입할 수 있어서 가입 시각과 같게 채워진다. */
	@Column(name = "email_verified_at")
	private LocalDateTime emailVerifiedAt;

	public boolean isActive() {
		return status == UserStatus.ACTIVE && !isDeleted();
	}

	/**
	 * 내 정보 수정. 받은 값으로 통째로 바꾼다. 값 검증(닉네임 필수, 이미지 주소 형식)은 요청 DTO 가 맡는다.
	 * profileImg 가 null 이면 사진 없음으로 저장된다.
	 */
	public void updateProfile(String nickname, String profileImg) {
		this.nickname = nickname.trim();
		this.profileImg = profileImg;
	}

	/**
	 * 탈퇴. 예매 내역이 이 행을 가리켜서 지우지 않고, 사람을 알아볼 수 있는 값만 지운다. (설계 1부 "탈퇴 시 처리")
	 * 이름·연락처는 예매에 스냅샷으로 따로 남아 있어 거래 기록은 유지된다.
	 * 비밀번호는 BCrypt 해시가 아닌 값이라 어떤 입력과도 맞지 않는다.
	 */
	public void withdraw() {
		this.email = "deleted-" + id + "@withdrawn.invalid";
		this.password = "WITHDRAWN";
		this.name = "탈퇴회원";
		this.nickname = "탈퇴회원";
		this.phone = "-";
		this.profileImg = null;
		delete();
	}

	public void suspend() {
		this.status = UserStatus.SUSPENDED;
	}
}
