package com.squirrel.tickting.global.util;

import java.util.Locale;

/**
 * SQL 의 LIKE 검색에 쓰는 검색어를 다듬는 유틸. 검색 API 가 여러 개여도 같은 규칙을 쓰도록 한 곳에 모았다.
 *
 * LIKE 에서 %(아무 글자 여러 개)와 _(아무 글자 하나)는 글자가 아니라 와일드카드다.
 * 사용자가 입력한 검색어를 그대로 %검색어% 로 감싸면 이런 일이 생긴다.
 *   - "%" 를 입력하면 LIKE '%%%' 가 되어 전체가 검색된다.
 *   - "100%" 를 입력하면 뒤의 % 가 와일드카드라서 "100" 이 들어간 것이 전부 나온다.
 * SQL 주입은 아니다. (값은 파라미터로 바인딩된다) 하지만 의도하지 않은 결과와 전체 검색(느림)이 되므로
 * 이 유틸로 와일드카드를 "글자 그대로" 찾도록 바꾼다.
 *
 * 사용하는 쿼리는 반드시 ESCAPE '!' 를 함께 써야 한다. (이 파일의 ESCAPE 값과 같아야 한다)
 * <pre>
 * WHERE LOWER(b.companyName) LIKE :pattern ESCAPE '!'
 * </pre>
 */
public final class LikeUtil {

	/**
	 * 이스케이프 문자. 이 글자 바로 뒤의 %, _, ! 는 와일드카드가 아니라 글자 그대로 취급된다.
	 * 쿼리의 ESCAPE '!' 와 같은 값이어야 한다.
	 * (SQL 에서 흔히 쓰는 \ 는 자바 문자열과 JPQL 에서 이스케이프가 겹쳐 헷갈려서 쓰지 않는다)
	 */
	public static final char ESCAPE = '!';

	/** "조건 없음"을 뜻하는 패턴. % 는 아무 글자나 일치하므로 모든 행이 걸린다. */
	private static final String MATCH_ALL = "%";

	private LikeUtil() {
	}

	/**
	 * 검색어를 "이 글자가 어디에 들어 있어도 일치"하는 LIKE 패턴으로 바꾼다.
	 *
	 * 검색어가 null 이거나 비어 있거나 공백뿐이면 "%" 를 돌려준다.
	 * 그러면 쿼리에서 검색어가 있는지 없는지 나눌 필요 없이 항상 같은 쿼리를 쓸 수 있다.
	 * (PostgreSQL 에서 null 파라미터가 타입 오류를 내는 것도 피할 수 있다)
	 *
	 * 변환 규칙
	 *   1. 앞뒤 공백을 지우고 소문자로 바꾼다. (쿼리에서 대상 컬럼도 LOWER 로 비교하므로 영문 대소문자를 가리지 않게 된다)
	 *   2. 검색어 안의 !, %, _ 앞에 이스케이프 문자를 붙여 글자 그대로 찾게 한다.
	 *   3. 앞뒤에 % 를 붙여 부분 일치로 만든다.
	 *
	 * 예: " 100%_a! " → "%100!%!_a!!%"
	 */
	public static String containsPattern(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return MATCH_ALL;
		}

		// Locale.ROOT: 실행하는 컴퓨터의 언어 설정에 따라 소문자 변환 결과가 달라지지 않게 한다.
		String text = keyword.trim().toLowerCase(Locale.ROOT);

		// ESCAPE 는 char 라서 replace 가 받는 문자열(CharSequence)로 바꿔야 한다.
		// 그리고 char + char 는 문자열 이어 붙이기가 아니라 숫자 덧셈이므로 둘 중 하나는 반드시 String 이어야 한다.
		String escape = String.valueOf(ESCAPE);

		// 이스케이프 문자 자신을 가장 먼저 처리한다.
		// 나중에 하면 아래에서 %, _ 앞에 붙인 이스케이프 문자까지 다시 두 번 이스케이프되어 버린다.
		String escaped = text
			.replace(escape, escape + escape)
			.replace("%", escape + "%")
			.replace("_", escape + "_");

		return "%" + escaped + "%";
	}
}
