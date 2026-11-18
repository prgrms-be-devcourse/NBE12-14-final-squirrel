package com.squirrel.tickting.domain.image.dto;

/** 올린 이미지의 공개 주소. 공연·프로필의 이미지 주소로 그대로 등록한다. */
public record ImageResponse(String imageUrl) {
}
