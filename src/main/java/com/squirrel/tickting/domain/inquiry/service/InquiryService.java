package com.squirrel.tickting.domain.inquiry.service;

import com.squirrel.tickting.domain.inquiry.dto.UserInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryHistory;
import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryImage;
import com.squirrel.tickting.domain.inquiry.infra.repository.InquiryHistoryRepository;
import com.squirrel.tickting.domain.inquiry.infra.repository.InquiryImageRepository;
import com.squirrel.tickting.domain.inquiry.infra.repository.InquiryRepository;
import com.squirrel.tickting.domain.inquiry.type.InquiryAction;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.InquiryExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 문의. 예매자·판매자·관리자가 함께 쓰는 문의 도메인의 서비스다. 문의·첨부 사진·이력은 같은 도메인이라 한 트랜잭션으로 저장한다.
 * "내 것인가"는 작성자 종류 + 번호를 조회 조건에 넣어서 거른다.
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

	/** 최근 문의가 먼저. 같은 시각이면 번호가 큰 쪽이 먼저라 페이지를 넘겨도 순서가 바뀌지 않는다. */
	private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

	private final InquiryRepository inquiryRepository;
	private final InquiryImageRepository inquiryImageRepository;
	private final InquiryHistoryRepository inquiryHistoryRepository;
	private final ObjectMapper objectMapper;

	/** 그 계정이 고를 수 있는 문의 유형. 화면의 유형 드롭다운에 쓴다. */
	public List<UserInquiryResponse.Category> getCategories(AccountType accountType) {
		return InquiryCategory.allowedFor(accountType).stream().map(UserInquiryResponse.Category::from).toList();
	}

	/**
	 * 예매자 문의를 작성한다. 답변 대기로 만들고, 첨부 사진과 작성 이력(CREATED)을 같은 트랜잭션에 남긴다.
	 * 예매가 내 것인지와 작성자 이름·이메일은 다른 도메인이라 파사드가 확인해서 넘긴다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public UserInquiryResponse.Created createForUser(Long userId, String authorName, String authorEmail,
		UserInquiryRequest.Create request) {
		if (!request.category().isAllowedFor(AccountType.USER)) {
			throw new BusinessException(InquiryExceptionCode.CATEGORY_NOT_ALLOWED);
		}
		List<String> imageUrls = request.imageUrls();

		Inquiry inquiry = inquiryRepository.save(Inquiry.builder()
			.authorType(AccountType.USER)
			.authorId(userId)
			.authorName(authorName)
			.authorEmail(authorEmail)
			.category(request.category())
			.bookingId(request.bookingId())
			.title(request.title().strip())
			.content(request.content().strip())
			.status(InquiryStatus.WAITING)
			.build());
		inquiryImageRepository.saveAll(IntStream.range(0, imageUrls.size())
			.mapToObj(i -> InquiryImage.builder()
				.inquiryId(inquiry.getId())
				.url(imageUrls.get(i))
				.sortOrder(i + 1)
				.build())
			.toList());
		recordHistory(inquiry, AccountType.USER, userId, InquiryAction.CREATED, imageUrls);
		return UserInquiryResponse.Created.from(inquiry);
	}

	/** 내 문의 목록. 삭제한 문의는 빠지고 최신 작성순이다. pageable 은 페이지 번호와 크기만 쓴다. */
	@Transactional(readOnly = true)
	public Page<UserInquiryResponse.Summary> getMyInquiries(Long userId, Pageable pageable) {
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
		return inquiryRepository.findByAuthorTypeAndAuthorIdAndDeletedAtIsNull(AccountType.USER, userId, sorted)
			.map(UserInquiryResponse.Summary::from);
	}

	/** 내 문의 하나. 남의 문의·삭제한 문의는 없는 문의와 같은 404 다. */
	@Transactional(readOnly = true)
	public UserInquiryResponse.Detail getMyInquiry(Long userId, Long inquiryId) {
		Inquiry inquiry = inquiryRepository
			.findByIdAndAuthorTypeAndAuthorIdAndDeletedAtIsNull(inquiryId, AccountType.USER, userId)
			.orElseThrow(() -> new BusinessException(InquiryExceptionCode.INQUIRY_NOT_FOUND));
		return UserInquiryResponse.Detail.from(inquiry);
	}

	/** 그 순간의 문의 내용을 통째로 이력에 남긴다. 사진 목록은 엔티티의 연관이 아니라 방금 저장한 값을 받는다. */
	private void recordHistory(Inquiry inquiry, AccountType actorType, Long actorId, InquiryAction action,
		List<String> imageUrls) {
		inquiryHistoryRepository.save(InquiryHistory.builder()
			.inquiryId(inquiry.getId())
			.actorType(actorType)
			.actorId(actorId)
			.action(action)
			.title(inquiry.getTitle())
			.content(inquiry.getContent())
			.imageUrls(objectMapper.writeValueAsString(imageUrls))
			.answer(inquiry.getAnswer())
			.build());
	}
}
