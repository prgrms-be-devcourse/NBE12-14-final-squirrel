package com.squirrel.tickting.domain.inquiry.service;

import com.squirrel.tickting.domain.inquiry.dto.SellerInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.SellerInquiryResponse;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryHistory;
import com.squirrel.tickting.domain.inquiry.infra.repository.InquiryHistoryRepository;
import com.squirrel.tickting.domain.inquiry.infra.repository.InquiryRepository;
import com.squirrel.tickting.domain.inquiry.type.InquiryAction;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.InquiryExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의. 예매자·판매자·관리자가 함께 쓰는 문의 도메인의 서비스다. 문의와 이력은 같은 도메인이라 한 트랜잭션으로 저장한다.
 * "내 것인가"는 작성자 종류 + 번호를 조회 조건에 넣어서 거른다.
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

	/** 최근 문의가 먼저. 같은 시각이면 번호가 큰 쪽이 먼저라 페이지를 넘겨도 순서가 바뀌지 않는다. */
	private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

	/** 문의를 쓸 수 있는 계정 종류. 관리자 목록에서 작성자 종류를 거르지 않을 때 넘긴다. */
	private static final List<AccountType> AUTHOR_TYPES = List.of(AccountType.USER, AccountType.SELLER);

	private final InquiryRepository inquiryRepository;
	private final InquiryHistoryRepository inquiryHistoryRepository;

	/** 그 계정이 고를 수 있는 문의 유형. 화면의 유형 드롭다운에 쓴다. */
	public List<UserInquiryResponse.Category> getCategories(AccountType accountType) {
		return InquiryCategory.allowedFor(accountType).stream().map(UserInquiryResponse.Category::from).toList();
	}

	/**
	 * 예매자 문의를 작성한다. 답변 대기로 만들고, 작성 이력(CREATED)을 같은 트랜잭션에 남긴다.
	 * 예매가 내 것인지와 작성자 이름·이메일은 다른 도메인이라 파사드가 확인해서 넘긴다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public UserInquiryResponse.Created createForUser(Long userId, String authorName, String authorEmail,
		UserInquiryRequest.Create request) {
		Inquiry inquiry = create(Inquiry.builder()
			.authorType(AccountType.USER)
			.authorId(userId)
			.authorName(authorName)
			.authorEmail(authorEmail)
			.category(request.category())
			.bookingId(request.bookingId())
			.title(request.title().strip())
			.content(request.content().strip())
			.imageUrls(toImageUrls(request.images())));
		return UserInquiryResponse.Created.from(inquiry);
	}

	/**
	 * 판매자 문의를 작성한다. 같은 사업체의 대표·담당자가 함께 보도록 사업체 번호를 남긴다. 예매는 연결하지 않는다.
	 * 작성자 이름·이메일은 판매자 도메인이라 파사드가 읽어서 넘긴다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public UserInquiryResponse.Created createForSeller(Long memberId, Long businessId, String authorName,
		String authorEmail, SellerInquiryRequest.Create request) {
		Inquiry inquiry = create(Inquiry.builder()
			.authorType(AccountType.SELLER)
			.authorId(memberId)
			.businessId(businessId)
			.authorName(authorName)
			.authorEmail(authorEmail)
			.category(request.category())
			.title(request.title().strip())
			.content(request.content().strip())
			.imageUrls(toImageUrls(request.images())));
		return UserInquiryResponse.Created.from(inquiry);
	}

	/** 내 문의 목록. 삭제한 문의는 빠지고 최신 작성순이다. pageable 은 페이지 번호와 크기만 쓴다. */
	@Transactional(readOnly = true)
	public Page<UserInquiryResponse.Summary> getMyInquiries(Long userId, Pageable pageable) {
		return inquiryRepository.findByAuthorTypeAndAuthorIdAndDeletedAtIsNull(AccountType.USER, userId, latest(pageable))
			.map(UserInquiryResponse.Summary::from);
	}

	/** 내 문의 하나. 남의 문의·삭제한 문의는 없는 문의와 같은 404 다. */
	@Transactional(readOnly = true)
	public UserInquiryResponse.Detail getMyInquiry(Long userId, Long inquiryId) {
		return UserInquiryResponse.Detail.from(findMine(AccountType.USER, userId, inquiryId));
	}

	/**
	 * 우리 사업체 문의 목록. 대표·담당자 누가 썼든 같은 사업체면 모두 보인다. 삭제한 문의는 빠지고 최신 작성순이다.
	 * 항목은 문의 행의 값(작성 때 복사한 이름 포함)만 써서 목록·개수 쿼리 두 번으로 끝난다.
	 */
	@Transactional(readOnly = true)
	public Page<SellerInquiryResponse.Summary> getBusinessInquiries(Long businessId, Pageable pageable) {
		return inquiryRepository.findByBusinessIdAndDeletedAtIsNull(businessId, latest(pageable))
			.map(SellerInquiryResponse.Summary::from);
	}

	/** 우리 사업체 문의 하나. 다른 사업체의 문의·삭제한 문의는 없는 문의와 같은 404 다. */
	@Transactional(readOnly = true)
	public SellerInquiryResponse.Detail getBusinessInquiry(Long businessId, Long inquiryId) {
		Inquiry inquiry = inquiryRepository.findByIdAndBusinessIdAndDeletedAtIsNull(inquiryId, businessId)
			.orElseThrow(() -> new BusinessException(InquiryExceptionCode.INQUIRY_NOT_FOUND));
		return SellerInquiryResponse.Detail.from(inquiry);
	}

	/**
	 * 내 문의를 수정한다. 답변 대기일 때만 되고, 수정 이력(UPDATED, 바뀐 내용)을 같은 트랜잭션에 남긴다.
	 * 예매가 내 것인지는 다른 도메인이라 파사드가 확인한다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public UserInquiryResponse.Detail updateForUser(Long userId, Long inquiryId, UserInquiryRequest.Update request) {
		ensureCategoryAllowed(AccountType.USER, request.category());
		ensureWaiting(findMine(AccountType.USER, userId, inquiryId));

		int updated = inquiryRepository.updateIfWaiting(inquiryId, request.category(), request.bookingId(),
			request.title().strip(), request.content().strip(), toImageUrls(request.images()), LocalDateTime.now());
		if (updated == 0) {
			// 확인한 뒤 바꾸기 전에 답변(또는 삭제)이 먼저 끝났다
			throw new BusinessException(InquiryExceptionCode.INQUIRY_ALREADY_ANSWERED);
		}

		// 조건부 UPDATE 가 영속성 컨텍스트를 비워서 바뀐 값을 다시 읽는다
		Inquiry changed = findMine(AccountType.USER, userId, inquiryId);
		recordHistory(changed, AccountType.USER, userId, InquiryAction.UPDATED);
		return UserInquiryResponse.Detail.from(changed);
	}

	/** 내 문의를 삭제(소프트 삭제)한다. 답변 대기일 때만 되고, 지우기 직전 내용을 삭제 이력(DELETED)으로 남긴다. */
	@Transactional
	public void deleteForUser(Long userId, Long inquiryId) {
		Inquiry inquiry = findMine(AccountType.USER, userId, inquiryId);
		ensureWaiting(inquiry);

		if (inquiryRepository.deleteIfWaiting(inquiryId, LocalDateTime.now()) == 0) {
			// 동시에 지운 요청이나 답변이 먼저 끝났다
			throw new BusinessException(InquiryExceptionCode.INQUIRY_ALREADY_ANSWERED);
		}
		recordHistory(inquiry, AccountType.USER, userId, InquiryAction.DELETED);
	}

	/**
	 * 관리자 문의 목록. 작성자 종류·상태를 비우면 거르지 않는다. 기본은 삭제하지 않은 문의이고, deleted 면 삭제된 문의만 준다.
	 * 목록 항목은 문의 행의 값(작성 때 복사한 이름·이메일 포함)만 써서 연관을 읽지 않는다. 그래서 쿼리는 목록·개수 두 번이다.
	 */
	@Transactional(readOnly = true)
	public Page<Inquiry> searchForAdmin(AccountType authorType, InquiryStatus status, boolean deleted,
		Pageable pageable) {
		List<AccountType> authorTypes = authorType == null ? AUTHOR_TYPES : List.of(authorType);
		List<InquiryStatus> statuses = status == null ? List.of(InquiryStatus.values()) : List.of(status);
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
		return deleted
			? inquiryRepository.findByAuthorTypeInAndStatusInAndDeletedAtIsNotNull(authorTypes, statuses, sorted)
			: inquiryRepository.findByAuthorTypeInAndStatusInAndDeletedAtIsNull(authorTypes, statuses, sorted);
	}

	/**
	 * 관리자 문의 상세. 삭제된 문의도 준다(분쟁 확인용). 이력은 남긴 순서대로 한 번에 읽는다.
	 * 응답에 예매(읽기 전용 연관)를 꺼내므로 호출한 쪽이 넘긴 변환을 트랜잭션 안에서 부른다. (서비스가 관리자 DTO 를 몰라도 되게)
	 */
	@Transactional(readOnly = true)
	public <T> T getForAdmin(Long inquiryId, BiFunction<Inquiry, List<InquiryHistory>, T> mapper) {
		Inquiry inquiry = inquiryRepository.findById(inquiryId)
			.orElseThrow(() -> new BusinessException(InquiryExceptionCode.INQUIRY_NOT_FOUND));
		return mapper.apply(inquiry, inquiryHistoryRepository.findByInquiryIdOrderByIdAsc(inquiryId));
	}

	/**
	 * 관리자가 답변한다. 답변 대기이고 삭제하지 않은 문의에만 되고, 답변은 하나뿐이라 고치거나 지울 수 없다.
	 * 상태를 먼저 확인한 뒤 같은 조건의 UPDATE 로 한 번 더 막는다. 그 사이 작성자가 고치거나 지웠거나 다른 관리자가 먼저 답했으면 409 다.
	 * 답변 이력(ANSWERED)에는 답변한 순간의 문의 내용과 답변을 함께 남긴다.
	 */
	@Transactional
	public <T> T answer(Long adminId, Long inquiryId, String answer,
		BiFunction<Inquiry, List<InquiryHistory>, T> mapper) {
		Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
			.orElseThrow(() -> new BusinessException(InquiryExceptionCode.INQUIRY_NOT_FOUND));
		if (inquiry.getStatus() != InquiryStatus.WAITING) {
			throw new BusinessException(InquiryExceptionCode.INQUIRY_NOT_ANSWERABLE);
		}

		if (inquiryRepository.answerIfWaiting(inquiryId, answer.strip(), adminId, LocalDateTime.now()) == 0) {
			throw new BusinessException(InquiryExceptionCode.INQUIRY_NOT_ANSWERABLE);
		}

		// 조건부 UPDATE 가 영속성 컨텍스트를 비워서 답변이 달린 값을 다시 읽는다
		Inquiry answered = inquiryRepository.findById(inquiryId).orElseThrow();
		recordHistory(answered, AccountType.ADMIN, adminId, InquiryAction.ANSWERED);
		return mapper.apply(answered, inquiryHistoryRepository.findByInquiryIdOrderByIdAsc(inquiryId));
	}

	/**
	 * 예매자·판매자 문의 작성의 공통 부분. 유형 확인 → 답변 대기로 저장 → 작성 이력(CREATED).
	 * 작성자마다 다른 값(사업체, 예매)은 호출한 쪽이 builder 에 채워 넘겨서, 작성 규칙은 여기 한 곳에만 둔다.
	 */
	private Inquiry create(Inquiry.InquiryBuilder draft) {
		Inquiry inquiry = draft.status(InquiryStatus.WAITING).build();
		ensureCategoryAllowed(inquiry.getAuthorType(), inquiry.getCategory());

		inquiryRepository.save(inquiry);
		recordHistory(inquiry, inquiry.getAuthorType(), inquiry.getAuthorId(), InquiryAction.CREATED);
		return inquiry;
	}

	/** 화면만 막으면 API 를 직접 부르는 요청이 다른 계정의 유형(예: 예매자가 정산)으로 쓸 수 있어서 서버에서도 막는다. */
	private void ensureCategoryAllowed(AccountType authorType, InquiryCategory category) {
		if (!category.isAllowedFor(authorType)) {
			throw new BusinessException(InquiryExceptionCode.CATEGORY_NOT_ALLOWED);
		}
	}

	/**
	 * 내가 쓴 문의. 작성자 종류를 함께 거르는 이유는 예매자와 판매자 번호가 겹칠 수 있어서다.
	 * 남의 문의·삭제한 문의는 없는 문의와 같은 404 다.
	 */
	private Inquiry findMine(AccountType authorType, Long authorId, Long inquiryId) {
		return inquiryRepository
			.findByIdAndAuthorTypeAndAuthorIdAndDeletedAtIsNull(inquiryId, authorType, authorId)
			.orElseThrow(() -> new BusinessException(InquiryExceptionCode.INQUIRY_NOT_FOUND));
	}

	/** 목록은 최신 작성순으로 고정한다. 클라이언트가 보낸 정렬은 쓰지 않고 페이지 번호와 크기만 쓴다. */
	private Pageable latest(Pageable pageable) {
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
	}

	/** 답변이 끝난 문의는 그 답변이 어떤 내용에 대한 것인지가 흐려지지 않게 고치거나 지울 수 없다. */
	private void ensureWaiting(Inquiry inquiry) {
		if (inquiry.getStatus() != InquiryStatus.WAITING) {
			throw new BusinessException(InquiryExceptionCode.INQUIRY_ALREADY_ANSWERED);
		}
	}

	/** 첨부 사진을 저장할 주소 목록으로 바꾼다. 사진을 보내지 않았으면(null) 빈 목록이라 image_urls 는 늘 배열이다. */
	private List<String> toImageUrls(List<UserInquiryRequest.Image> images) {
		return images == null ? List.of() : images.stream().map(UserInquiryRequest.Image::url).toList();
	}

	/** 그 순간의 문의 내용(사진 목록 포함)을 통째로 이력에 남긴다. */
	private void recordHistory(Inquiry inquiry, AccountType actorType, Long actorId, InquiryAction action) {
		inquiryHistoryRepository.save(InquiryHistory.builder()
			.inquiryId(inquiry.getId())
			.actorType(actorType)
			.actorId(actorId)
			.action(action)
			.title(inquiry.getTitle())
			.content(inquiry.getContent())
			.imageUrls(inquiry.getImageUrls())
			.answer(inquiry.getAnswer())
			.build());
	}
}
