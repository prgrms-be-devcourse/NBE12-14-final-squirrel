package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.global.entity.CreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 취소 규정. businessId 가 비어 있으면 서비스 기본 규정이다.
 * 판매자는 기본 규정을 복사해서 자기 사업체 규정으로 만들고 구간을 고친다. 기본 규정 자체는 건드리지 않는다.
 */
@Entity
@Table(name = "cancel_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CancelPolicy extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 사업체 번호 (seller_business). 비어 있으면 서비스 기본 규정 */
	@Column
	private Long businessId;

	@Column(nullable = false, length = 100)
	private String name;
}
