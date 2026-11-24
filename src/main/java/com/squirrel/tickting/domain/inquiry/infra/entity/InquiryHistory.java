package com.squirrel.tickting.domain.inquiry.infra.entity;

import com.squirrel.tickting.domain.inquiry.type.InquiryAction;
import com.squirrel.tickting.global.entity.CreatedTimeEntity;
import com.squirrel.tickting.global.security.AccountType;
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
 * 문의 이력. 작성·수정·삭제·답변마다 그 순간의 내용을 통째로 한 행씩 남기고, 고치거나 지우지 않는다.
 * 답변 전 수정은 문의 본문을 덮어써서, 처음에 무엇을 썼는지와 관리자가 어떤 내용에 답했는지는 여기에만 남는다.
 * 감사 로그(audit_log)는 판매자·관리자·시스템 행동만 남기는 공통 테이블이라 예매자 행동을 넣지 않고 문의 도메인에 따로 둔다.
 */
@Entity
@Table(name = "inquiry_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InquiryHistory extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long inquiryId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType actorType;

	@Column(nullable = false)
	private Long actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private InquiryAction action;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 2000)
	private String content;

	/** 그 순간의 첨부 사진 주소 목록(JSON 배열). 읽기만 하므로 문자열로 둔다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String imageUrls;

	@Column(length = 2000)
	private String answer;
}
