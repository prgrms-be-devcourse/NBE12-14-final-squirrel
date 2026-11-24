package com.squirrel.tickting.domain.image.service;

import com.squirrel.tickting.domain.image.dto.ImageResponse;
import com.squirrel.tickting.domain.image.type.ImagePurpose;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.s3.S3Uploader;
import com.squirrel.tickting.global.security.AccountType;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 업로드. DB 를 쓰지 않아서 트랜잭션이 없다.
 * 저장 경로는 public/{용도}/{계정 종류}-{계정 번호}/{UUID}.{확장자} 다. public/ 아래만 누구나 읽을 수 있다. (버킷 정책)
 * 이름은 서버가 UUID 로 만든다. 원본 파일 이름을 쓰면 특수문자 문제가 생기고 같은 이름이 덮어써진다.
 * 계정 번호는 요청이 아니라 토큰에서 받고, 누가 올렸는지 경로에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ImageService {

	/** 10MB. application.yaml 의 spring.servlet.multipart.max-file-size 와 같은 값이다. */
	private static final long MAX_SIZE = 10L * 1024 * 1024;

	private final S3Uploader s3Uploader;

	public ImageResponse upload(AccountType accountType, Long accountId, ImagePurpose purpose, MultipartFile file) {
		// 용도는 계정 종류로 나뉜다. 업로드 API 가 하나라 시큐리티 경로 규칙으로는 막을 수 없어서 여기서 본다.
		if (purpose.getAllowedAccountType() != accountType) {
			throw new BusinessException(AuthExceptionCode.ACCESS_DENIED);
		}
		if (file == null || file.isEmpty()) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "이미지 파일을 선택해 주세요.");
		}
		// 형식은 요청이 보낸 값이라 완전히 믿을 수는 없다. 그래도 S3 에 이 값으로 저장되어 브라우저는 그 형식으로만 읽는다.
		String extension = extensionOf(file.getContentType());
		if (extension == null) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "jpg, png, webp 형식의 이미지만 올릴 수 있습니다.");
		}
		if (file.getSize() > MAX_SIZE) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "이미지는 10MB 이하만 올릴 수 있습니다.");
		}

		String key = "public/" + purpose.name().toLowerCase() + "/" + accountType.name().toLowerCase() + "-" + accountId
			+ "/" + UUID.randomUUID() + "." + extension;
		try {
			return new ImageResponse(s3Uploader.upload(key, file.getContentType(), file.getBytes()));
		} catch (IOException e) {
			throw new BusinessException(CommonExceptionCode.INTERNAL_ERROR);
		}
	}

	/** 허용하는 파일 형식의 확장자. 그 밖의 형식은 null 이다. gif 는 용량이 커지기 쉬워서 받지 않는다. */
	private static String extensionOf(String contentType) {
		if (contentType == null) {
			return null;
		}
		return switch (contentType) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			case "image/webp" -> "webp";
			default -> null;
		};
	}
}
