package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.infra.repository.CommonGuideRepository;
import com.squirrel.tickting.domain.performance.type.GuideType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 모든 공연에 똑같이 보여주는 공통 안내. 관리자만 바꾼다. 예매자는 공연 상세에서 읽기만 한다. */
@Service
@RequiredArgsConstructor
public class CommonGuideService {

	private final CommonGuideRepository commonGuideRepository;

	/** 지금 저장된 안내. 아직 넣지 않은 유형은 목록에 없다. */
	@Transactional(readOnly = true)
	public List<CommonGuide> getGuides() {
		return commonGuideRepository.findAllOrderByType();
	}

	/**
	 * 바꾸기 전 내용을 잠그고 읽는다. 아직 없으면 null. 감사 로그에 남길 "바뀌기 전 값"이 실제 직전 값이 되도록
	 * 같은 트랜잭션에서 save 보다 먼저 부른다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public String getContentForUpdate(GuideType type) {
		return commonGuideRepository.findForUpdate(type).map(CommonGuide::getContent).orElse(null);
	}

	/**
	 * 그 유형의 내용을 통째로 바꾼다. 행이 없으면 새로 만든다. (운영 DB 에는 시드가 없어서 관리자가 처음 저장할 때 생긴다)
	 * 같은 유형을 동시에 처음 만들면 UNIQUE(uq_common_guide_type)가 하나만 남기고 나머지는 409 다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public CommonGuide save(GuideType type, String content, Long adminId) {
		CommonGuide guide = commonGuideRepository.findForUpdate(type)
			.orElseGet(() -> CommonGuide.builder().type(type).build());
		guide.update(content.strip(), adminId);
		// 처음 만들 때도 수정 시각이 채워진 값을 돌려주려고 바로 반영한다
		return commonGuideRepository.saveAndFlush(guide);
	}
}
