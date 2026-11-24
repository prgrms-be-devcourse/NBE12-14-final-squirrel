package com.squirrel.tickting.domain.inquiry.infra.entity;

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

/** 문의 첨부 사진. 이미지 업로드(IMG-01)로 올린 우리 저장소의 주소만 저장한다. 순서는 작성할 때 보낸 순서다. */
@Entity
@Table(name = "inquiry_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InquiryImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 문의 번호 (inquiry). 문의의 읽기 전용 연관(Inquiry.images)이 같은 컬럼을 써서 이름을 적어 둔다. */
	@Column(name = "inquiry_id", nullable = false)
	private Long inquiryId;

	@Column(nullable = false, length = 2048)
	private String url;

	@Column(nullable = false)
	private Integer sortOrder;
}
