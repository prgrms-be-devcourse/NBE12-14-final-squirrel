package com.squirrel.tickting.domain.audit.infra.entity;

import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.global.entity.CreatedTimeEntity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 로그. 판매자·관리자·시스템의 변경 행동만 추가하고 수정·삭제하지 않는다. (고칠 수 있는 로그는 증거가 되지 못한다)
 * 행위자와 대상이 제각각이라 FK 대신 종류 + 번호로 가리키고, 계정이나 공연이 지워져도 로그는 남는다.
 * 그래서 setter 나 수정 메서드를 만들지 않는다.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuditLog extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ActorType actorType;

	/** 판매자·관리자 계정 번호. 시스템이면 비운다. */
	@Column
	private Long actorId;

	/** 판매자 이력 조회용. 관리자·시스템 행동은 비울 수 있다. */
	@Column
	private Long businessId;

	/** PERFORMANCE, SCHEDULE, SELLER_BUSINESS, REFUND 등 */
	@Column(nullable = false, length = 50)
	private String targetType;

	@Column(nullable = false)
	private Long targetId;

	/** UPDATE, PAUSE, POSTPONE, CANCEL, APPROVE, RETRY 등 */
	@Column(nullable = false, length = 50)
	private String action;

	/** 바뀌기 전 내용(JSON). 대상마다 컬럼이 달라서 통째로 저장하고, 읽기만 한다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String beforeValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String afterValue;
}
