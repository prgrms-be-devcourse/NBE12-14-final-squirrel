package com.squirrel.tickting.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LikeUtilTest {

	@Test
	@DisplayName("검색어가 null 이거나 비어 있거나 공백뿐이면 모든 행이 일치하는 % 가 된다")
	void blankKeywordMatchesAll() {
		assertThat(LikeUtil.containsPattern(null)).isEqualTo("%");
		assertThat(LikeUtil.containsPattern("")).isEqualTo("%");
		assertThat(LikeUtil.containsPattern("   ")).isEqualTo("%");
	}

	@Test
	@DisplayName("앞뒤 공백을 지우고 소문자로 바꾼 뒤 앞뒤에 % 를 붙인다 (한글은 그대로)")
	void trimsLowercasesAndWraps() {
		assertThat(LikeUtil.containsPattern("  Acme  ")).isEqualTo("%acme%");
		assertThat(LikeUtil.containsPattern("가상기획")).isEqualTo("%가상기획%");
		assertThat(LikeUtil.containsPattern("A B")).isEqualTo("%a b%");
	}

	@Test
	@DisplayName("% 와 _ 는 와일드카드가 아니라 글자 그대로 찾도록 이스케이프 문자를 붙인다")
	void escapesWildcards() {
		assertThat(LikeUtil.containsPattern("%")).isEqualTo("%!%%");
		assertThat(LikeUtil.containsPattern("_")).isEqualTo("%!_%");
		assertThat(LikeUtil.containsPattern("100%")).isEqualTo("%100!%%");
		assertThat(LikeUtil.containsPattern("a_b")).isEqualTo("%a!_b%");
	}

	@Test
	@DisplayName("이스케이프 문자 자신은 두 번 쓴다")
	void escapesTheEscapeCharacter() {
		assertThat(LikeUtil.containsPattern("!")).isEqualTo("%!!%");
		assertThat(LikeUtil.containsPattern("a!b")).isEqualTo("%a!!b%");
	}

	@Test
	@DisplayName("이스케이프 문자를 먼저 처리해서, %·_ 앞에 붙인 이스케이프 문자가 다시 이스케이프되지 않는다")
	void escapeCharacterIsHandledFirst() {
		// 먼저 처리하지 않으면 "%" → "!%" → "!!%" 처럼 두 번 바뀐다.
		assertThat(LikeUtil.containsPattern(" 100%_a! ")).isEqualTo("%100!%!_a!!%");
	}
}
