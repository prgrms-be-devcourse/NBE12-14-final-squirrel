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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 예매자 계정. 로그인 아이디는 이메일(소문자로 저장)이고, 이름과 휴대폰은 본인인증으로 확인된 값이다. */
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

	/** 본인인증 CI 의 해시. 탈퇴하면 비운다. */
	@Column(length = 64)
	private String ciHash;

	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	public boolean isActive() {
		return status == UserStatus.ACTIVE && !isDeleted();
	}

	public void suspend() {
		this.status = UserStatus.SUSPENDED;
	}
}
