package com.squirrel.tickting.global.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * API 공통 응답 래퍼.
 * 성공/실패 모두 success, code, message, data 형태로 내려 프론트 계약을 맞춘다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponse<T> {

	private static final String DEFAULT_CODE = "0000";

	private boolean success;
	private String code;
	private String message;
	private T data;

	public static <T> ApiResponse<T> success(T data) {
		return ApiResponse.<T>builder().success(true).code(DEFAULT_CODE).message("").data(data).build();
	}

	public static <T> ApiResponse<T> success(T data, String message) {
		return ApiResponse.<T>builder().success(true).code(DEFAULT_CODE).message(message).data(data).build();
	}

	public static ApiResponse<Void> noContentSuccess() {
		return ApiResponse.<Void>builder().success(true).code(DEFAULT_CODE).message("").data(null).build();
	}

	public static ApiResponse<Void> noContentSuccess(String message) {
		return ApiResponse.<Void>builder().success(true).code(DEFAULT_CODE).message(message).data(null).build();
	}

	public static <T> ApiResponse<T> error(String message, String code, T data) {
		return ApiResponse.<T>builder().success(false).code(code).message(message).data(data).build();
	}

	public static ApiResponse<Void> error(String message, String code) {
		return ApiResponse.<Void>builder().success(false).code(code).message(message).data(null).build();
	}
}
