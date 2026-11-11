package com.squirrel.tickting.global.exception;

import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.query.sqm.UnknownPathException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러 예외를 ApiResponse 형식으로 통일해 응답한다.
 * 필터에서 난 예외(JWT 검증 실패 등)도 HandlerExceptionResolver 를 거쳐 이곳으로 들어온다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 서비스에서 의도적으로 던진 비즈니스 예외 (도메인별 ExceptionCode 전부) */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
		ExceptionCode ec = e.getExceptionCode();
		logByStatus(ec, e);
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(e.getMessage(), ec.getCode()));
	}

	/** 시큐리티 인증 실패. 주로 컨트롤러 구간. 토큰 없는 요청의 401 은 EntryPoint 가 처리한다 */
	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
		AuthExceptionCode ec = AuthExceptionCode.AUTHENTICATION_FAILED;
		log.warn("[{}] AuthenticationException: {}", ec.getCode(), e.getMessage());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/** 인증은 됐으나 권한 부족. 주로 @PreAuthorize 실패. 경로 규칙의 403 은 AccessDeniedHandler 가 처리한다 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
		AuthExceptionCode ec = AuthExceptionCode.ACCESS_DENIED;
		log.warn("[{}] AccessDeniedException: {}", ec.getCode(), e.getMessage());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/** @Valid 요청 본문 검증 실패 (필드 제약 위반) */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
			.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
			.collect(Collectors.joining(", "));
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] {}", ec.getCode(), message);
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(message, ec.getCode()));
	}

	/** 요청 본문을 읽지 못한 경우(깨진 JSON, 잘못된 enum 등). 원문 메시지는 노출하지 않는다. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] 본문을 읽지 못함: {}", ec.getCode(), e.getMessage());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error("요청 형식이 올바르지 않습니다.", ec.getCode()));
	}

	/** 경로 변수·쿼리 파라미터 타입이 맞지 않는 경우. (예: 숫자 자리에 문자열) */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] 파라미터 타입 불일치: {}={}", ec.getCode(), e.getName(), e.getValue());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(
				"'" + e.getName() + "' 값이 올바르지 않습니다.", ec.getCode()));
	}

	/** 필수 쿼리 파라미터가 빠진 경우 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] 필수 파라미터 누락: {}", ec.getCode(), e.getParameterName());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(
				"'" + e.getParameterName() + "' 값이 필요합니다.", ec.getCode()));
	}

	/** sort에 엔티티에 없는 필드가 들어온 경우. 안 잡으면 500이라 여기서 400으로 바꾼다. */
	@ExceptionHandler(PropertyReferenceException.class)
	public ResponseEntity<ApiResponse<Void>> handleBadSort(PropertyReferenceException e) {
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] 잘못된 정렬 필드: {}", ec.getCode(), e.getPropertyName());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(
				"'" + e.getPropertyName() + "' 으로는 정렬할 수 없습니다.", ec.getCode()));
	}

	/** 경로는 맞지만 메서드가 틀린 경우. 안 잡으면 500이라 여기서 405로 바꾼다. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
		CommonExceptionCode ec = CommonExceptionCode.METHOD_NOT_ALLOWED;
		log.warn("[{}] 지원하지 않는 메서드: {}", ec.getCode(), e.getMethod());
		return ResponseEntity.status(ec.getStatus()).body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/** 매핑되지 않은 경로. 안 잡으면 500이라 여기서 404로 바꾼다. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
		CommonExceptionCode ec = CommonExceptionCode.ENDPOINT_NOT_FOUND;
		log.warn("[{}] 없는 경로: {}", ec.getCode(), e.getResourcePath());
		return ResponseEntity.status(ec.getStatus()).body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/**
	 * @Query 로 직접 쓴 쿼리에 없는 정렬 필드가 들어온 경우.
	 * Spring Data 는 이때 정렬 필드를 검증하지 않고 JPQL 에 그대로 붙이므로
	 * PropertyReferenceException 이 아니라 Hibernate 예외가 래핑되어 올라온다.
	 *
	 * 같은 예외 타입이 진짜 서버 버그일 수도 있어, 원인이 경로 해석 실패일 때만 400으로 본다.
	 */
	@ExceptionHandler(InvalidDataAccessApiUsageException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidDataAccess(InvalidDataAccessApiUsageException e) {
		if (!hasUnknownPathCause(e)) {
			return handleUnknown(e);
		}
		CommonExceptionCode ec = CommonExceptionCode.INVALID_INPUT;
		log.warn("[{}] 잘못된 정렬 필드: {}", ec.getCode(), e.getMessage());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error("정렬할 수 없는 필드입니다.", ec.getCode()));
	}

	private boolean hasUnknownPathCause(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof UnknownPathException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	/**
	 * DB 제약(UNIQUE 등) 위반. 서비스가 미리 확인하지만 동시 요청은 그 확인을 함께 통과할 수 있다.
	 * 안 잡으면 500 이라 409 로 바꾼다. 어떤 UNIQUE 인덱스인지 UniqueConstraintCodes 에 등록돼 있으면
	 * 그 에러(이메일 중복 등)로, 아니면 일반 충돌 에러(COMMON003)로 응답한다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
		ExceptionCode ec = UniqueConstraintCodes.find(e).orElse(CommonExceptionCode.CONFLICT);
		log.warn("[{}] DB 제약 위반: {}", ec.getCode(), e.getMostSpecificCause().getMessage());
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/** 위에서 못 잡은 모든 예외. 원문 메시지는 노출하지 않는다 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
		CommonExceptionCode ec = CommonExceptionCode.INTERNAL_ERROR;
		log.error("[UNHANDLED] 처리되지 않은 예외", e);
		return ResponseEntity.status(ec.getStatus())
			.body(ApiResponse.error(ec.getMessage(), ec.getCode()));
	}

	/** 5xx는 스택트레이스까지, 4xx는 메시지만 남긴다 */
	private void logByStatus(ExceptionCode ec, Exception e) {
		if (ec.getStatus().is5xxServerError()) {
			log.error("[{}] {}", ec.getCode(), e.getMessage(), e);
		} else {
			log.warn("[{}] {}", ec.getCode(), e.getMessage());
		}
	}
}
