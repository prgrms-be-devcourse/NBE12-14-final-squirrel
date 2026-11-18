package com.squirrel.tickting.global.s3;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 에 파일을 올린다. 키는 .env 에서 읽은 값을 직접 넘긴다. (SDK 의 기본 탐색은 .env 가 아니라 운영체제 환경 변수를 본다)
 * DB 에는 key 가 아니라 파일 주소를 저장하는 설계라서, 올린 뒤 공개 주소를 돌려준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(S3Properties.class)
public class S3Uploader {

	private final S3Properties properties;
	private S3Client s3Client;

	@PostConstruct
	void init() {
		s3Client = S3Client.builder()
			.region(Region.of(properties.region()))
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())))
			.build();
	}

	@PreDestroy
	void close() {
		s3Client.close();
	}

	/**
	 * 올리고 공개 주소를 돌려준다. 실패하면 원인(AWS 메시지)은 로그에만 남기고 클라이언트에는 서버 오류로 답한다.
	 * key 가 UUID 라서 같은 주소의 내용이 바뀌지 않으므로 브라우저가 오래 캐시해도 안전하다.
	 */
	public String upload(String key, String contentType, byte[] content) {
		try {
			s3Client.putObject(PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.contentType(contentType)
				.cacheControl("public, max-age=31536000, immutable")
				.build(), RequestBody.fromBytes(content));
		} catch (SdkException e) {
			log.error("S3 업로드 실패 key={}", key, e);
			throw new BusinessException(CommonExceptionCode.INTERNAL_ERROR);
		}
		return properties.baseUrl() + "/" + key;
	}
}
