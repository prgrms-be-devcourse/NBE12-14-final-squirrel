package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import com.squirrel.tickting.global.util.LikeUtil;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;
	private final VenueZoneRepository venueZoneRepository;

	/**
	 * 판매자가 공연을 등록할 때 고르는 공연장 목록. 사용 중인 공연장만, 이름순으로 준다.
	 * 지역이 비어 있으면 전체 지역이고, 있으면 정확히 같은 지역만 찾는다.
	 * 검색어는 LikeUtil 로 LIKE 패턴으로 바꿔서 넘긴다. (%, _ 를 글자 그대로 찾고, 비면 전체가 일치하는 패턴이 된다)
	 */
	@Transactional(readOnly = true)
	public Page<Venue> searchActive(String region, String keyword, Pageable pageable) {
		return search(List.of(VenueStatus.ACTIVE), region, keyword, pageable);
	}

	/** 관리자의 공연장 목록(ADM-10). 상태를 주지 않으면 모든 상태다. 검색 조건과 정렬은 판매자 목록과 같다. */
	@Transactional(readOnly = true)
	public Page<Venue> searchAll(VenueStatus status, String region, String keyword, Pageable pageable) {
		return search(status == null ? List.of(VenueStatus.values()) : List.of(status), region, keyword, pageable);
	}

	private Page<Venue> search(List<VenueStatus> statuses, String region, String keyword, Pageable pageable) {
		// 지역이 없으면 쿼리가 "모든 지역"으로 읽도록 null 대신 빈 문자열을 넘긴다.
		String regionCondition = region == null ? "" : region.trim();
		return venueRepository.search(statuses, regionCondition, LikeUtil.containsPattern(keyword), pageable);
	}

	/** 공연장 한 건. 없으면 404 다. */
	@Transactional(readOnly = true)
	public Venue get(Long venueId) {
		return venueRepository.findById(venueId)
			.orElseThrow(() -> new BusinessException(VenueExceptionCode.VENUE_NOT_FOUND));
	}

	/**
	 * 공연장을 사용 중지한다. 없으면 404, 이미 중지됐으면 409 다. 호출한 쪽이 공연장 락과 트랜잭션을 연 상태에서 부른다.
	 * 읽어 둔 엔티티에는 반영되지 않는다.
	 */
	@Transactional
	public void deactivate(Long venueId, LocalDateTime now) {
		changeStatus(venueId, VenueStatus.ACTIVE, VenueStatus.INACTIVE, VenueExceptionCode.VENUE_ALREADY_INACTIVE, now);
	}

	/** 사용 중지한 공연장을 다시 쓴다. 없으면 404, 이미 사용 중이면 409 다. 호출 조건은 deactivate 와 같다. */
	@Transactional
	public void activate(Long venueId, LocalDateTime now) {
		changeStatus(venueId, VenueStatus.INACTIVE, VenueStatus.ACTIVE, VenueExceptionCode.VENUE_ALREADY_ACTIVE, now);
	}

	private void changeStatus(Long venueId, VenueStatus from, VenueStatus to, VenueExceptionCode conflict,
		LocalDateTime now) {
		if (get(venueId).getStatus() != from || venueRepository.changeStatus(venueId, from, to, now) == 0) {
			throw new BusinessException(conflict);
		}
	}

	/**
	 * 새 공연을 등록할 수 있는 공연장의 구역 번호 목록. 공연은 공연장의 모든 구역마다 구역 설정을 하나씩 만든다.
	 * 사용 중지된 공연장은 선택 목록(searchActive)에서 빠지지만 번호를 직접 보낼 수 있어서 여기서 한 번 더 막는다.
	 * 구역이 없는 공연장도 통과시킨다. (판매할 구역이 없으면 공개(SLR-04)의 "ASSIGNED 구역 1개 이상" 조건에서 막힌다)
	 */
	@Transactional(readOnly = true)
	public List<Long> getActiveZoneIds(Long venueId) {
		Venue venue = get(venueId);
		if (venue.getStatus() != VenueStatus.ACTIVE) {
			throw new BusinessException(VenueExceptionCode.VENUE_INACTIVE);
		}
		return venueZoneRepository.findIdsByVenueId(venueId);
	}
}
