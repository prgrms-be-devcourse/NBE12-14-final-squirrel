package com.squirrel.tickting.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 소프트 삭제(deleted_at)를 쓰는 엔티티의 상위 클래스.
 * delete() 호출 시 행을 지우지 않고 삭제 시각만 기록한다.
 *
 * 삭제된 행을 거르는 전역 필터(@SQLRestriction)는 쓰지 않는다.
 * 예매 이력이 탈퇴 회원을 참조하기 때문에, 조회 메서드마다
 * findByIdAndDeletedAtIsNull 처럼 이름으로 조건을 드러낸다.
 */
@Getter
@MappedSuperclass
public abstract class SoftDeletableEntity extends BaseTimeEntity {

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public void delete() {
		this.deletedAt = LocalDateTime.now();
	}

	/** 삭제를 되돌린다. 누가 지웠는지 구분할 수 없으니 되돌려도 되는지는 호출하는 쪽이 판단한다. */
	public void restore() {
		this.deletedAt = null;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}
}
