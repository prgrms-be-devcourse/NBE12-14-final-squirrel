package com.squirrel.tickting.global.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 설정. 값은 application.yaml 의 app.s3 에 있고, 키는 .env 에서 읽는다. 시크릿 키는 로그에 남기거나 브라우저로 내려보내면 안 된다.
 *
 * @param bucket          버킷 이름 (.env 의 S3_BUCKET)
 * @param region          버킷 리전 (.env 의 AWS_REGION)
 * @param accessKeyId     서버 전용 IAM 사용자의 액세스 키 (.env 의 AWS_ACCESS_KEY_ID)
 * @param secretAccessKey 같은 사용자의 시크릿 키 (.env 의 AWS_SECRET_ACCESS_KEY)
 * @param baseUrl         버킷 루트 주소 (.env 의 IMAGE_BASE_URL). 파일 주소 = 이 주소 + "/" + 저장 경로. 끝의 / 는 지운다
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(String bucket, String region, String accessKeyId, String secretAccessKey, String baseUrl) {

	public S3Properties {
		baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
	}
}
