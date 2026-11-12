package com.squirrel.tickting.domain.admin.infra.entity;

import com.squirrel.tickting.domain.admin.type.AdminStatus;
import com.squirrel.tickting.global.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관리자 계정. 가입과 비밀번호 변경이 없고, 개발 때는 db/seed 의 시드로 넣는다. 로그인 아이디는 이메일(소문자)이다. */
@Entity
@Table(name = "admin_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AdminMember extends SoftDeletableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AdminStatus status;

	public boolean isActive() {
		return status == AdminStatus.ACTIVE && !isDeleted();
	}
}
