package com.squirrel.tickting.domain.image.controller;

import com.squirrel.tickting.domain.image.dto.ImageResponse;
import com.squirrel.tickting.domain.image.service.ImageService;
import com.squirrel.tickting.domain.image.type.ImagePurpose;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Image", description = "이미지 업로드 API")
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

	private final ImageService imageService;

	/** 시큐리티는 로그인만 확인한다. 용도에 맞는 계정인지는 서비스가 본다. */
	@Operation(summary = "이미지 업로드",
		description = "이미지를 S3 에 올리고 공개 주소를 돌려준다. 응답의 imageUrl 을 공연·프로필·문의의 이미지 주소로 등록한다. jpg, png, webp 만, 최대 10MB.")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<ImageResponse>> upload(
		@AuthenticationPrincipal CustomUserDetails account,
		// 이름을 명시해 -parameters 컴파일 옵션에 의존하지 않는다. 파일이 없을 때의 400 은 서비스가 낸다.
		@RequestParam("purpose") ImagePurpose purpose,
		@RequestPart(value = "file", required = false) MultipartFile file
	) {
		return ResponseEntity.ok(ApiResponse.success(
			imageService.upload(account.getAccountType(), account.getId(), purpose, file)));
	}
}
