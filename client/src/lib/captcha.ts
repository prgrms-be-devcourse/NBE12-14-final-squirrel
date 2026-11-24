// 대기열 입장(POST /schedules/{id}/queue)은 줄을 서기 전에 구글 reCAPTCHA v3 토큰을 확인한다. (api-spec "예매하기(대기열 입장)의 캡챠")
// 토큰은 헤더 X-Captcha-Token 으로 보내고, 요청 직전에 받아야 한다. 한 번만 쓸 수 있고 약 2분 뒤 만료된다.

export const CAPTCHA_HEADER = "X-Captcha-Token";

type Grecaptcha = {
  ready: (callback: () => void) => void;
  execute: (siteKey: string, options: { action: string }) => Promise<string>;
};

// 사이트 키는 브라우저에 내려가도 되는 키다. (비밀 키는 서버만 읽는다)
const SITE_KEY = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;

// 로컬 서버가 캡챠를 mock 으로 돌릴 때(CAPTCHA_VERIFIER=mock)는 토큰이 비어 있지만 않으면 통과한다.
// 사이트 키가 없을 때 이 값을 보내서 로컬에서 키 없이 흐름을 확인한다. 실제 검증(recaptcha)을 켠 서버에서는 거절된다.
const LOCAL_TOKEN = "local-dev";

let loading: Promise<Grecaptcha> | null = null;

function loadScript(siteKey: string): Promise<Grecaptcha> {
  if (loading) return loading;
  loading = new Promise<Grecaptcha>((resolve, reject) => {
    const existing = (window as unknown as { grecaptcha?: Grecaptcha }).grecaptcha;
    if (existing) {
      resolve(existing);
      return;
    }
    const script = document.createElement("script");
    script.src = `https://www.google.com/recaptcha/api.js?render=${encodeURIComponent(siteKey)}`;
    script.async = true;
    script.onload = () => {
      const loaded = (window as unknown as { grecaptcha?: Grecaptcha }).grecaptcha;
      if (loaded) resolve(loaded);
      else reject(new Error("grecaptcha 를 찾을 수 없습니다"));
    };
    script.onerror = () => {
      loading = null;
      reject(new Error("reCAPTCHA 스크립트를 불러오지 못했습니다"));
    };
    document.head.appendChild(script);
  });
  return loading;
}

export async function getCaptchaToken(action: string): Promise<string> {
  if (!SITE_KEY) return LOCAL_TOKEN;
  const grecaptcha = await loadScript(SITE_KEY);
  await new Promise<void>((resolve) => grecaptcha.ready(resolve));
  return grecaptcha.execute(SITE_KEY, { action });
}
