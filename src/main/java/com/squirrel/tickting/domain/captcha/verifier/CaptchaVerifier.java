package com.squirrel.tickting.domain.captcha.verifier;

/**
 * 요청이 사람에게서 왔는지 확인한다. 구현(구글 reCAPTCHA, Mock)을 설정으로 바꿔 끼울 수 있게 호출하는 쪽은 이 인터페이스만 안다.
 * 어느 구현을 쓸지는 app.captcha.verifier 로 고른다.
 */
public interface CaptchaVerifier {

	/**
	 * @param token  브라우저가 받은 캡챠 토큰. 한 번만 쓸 수 있고 약 2분 뒤 만료된다. 없으면 null
	 * @param action 토큰을 받은 화면 이름. 다른 화면에서 받은 토큰을 돌려쓰지 못하게 비교한다
	 * @return 사람으로 판단되면 true, 토큰이 없거나 틀렸거나 점수가 낮으면 false
	 * @throws com.squirrel.tickting.global.exception.BusinessException 캡챠 서버에 물어볼 수 없을 때 (CAPTCHA001)
	 */
	boolean verify(String token, String action);
}
