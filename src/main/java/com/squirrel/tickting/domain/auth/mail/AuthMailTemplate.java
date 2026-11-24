package com.squirrel.tickting.domain.auth.mail;

import org.springframework.web.util.HtmlUtils;

/**
 * 인증 관련 메일(이메일 인증번호, 비밀번호 재설정 링크, 비밀번호 변경 알림)의 본문. 보내는 일(SmtpEmailSender)과 모양을 나누려고 따로 둔다.
 * 세 메일이 같은 바깥 틀(layout)을 써서 같은 서비스의 메일로 보이게 한다.
 *
 * <p>메일 앱은 웹 브라우저와 달라서 모양을 만드는 방식이 정해져 있다.
 * <ul>
 *   <li>스타일은 {@code <style>} 이 아니라 태그마다 직접(인라인) 쓴다. Gmail·네이버·아웃룩이 {@code <style>} 을 지우거나 무시한다</li>
 *   <li>배치는 {@code div} 가 아니라 표({@code table})로 한다. 아웃룩에서도 틀이 깨지지 않는다</li>
 *   <li>이미지는 쓰지 않는다. 외부 이미지는 기본으로 막는 메일 앱이 많아서, 로고는 글자로 둔다</li>
 *   <li>폭은 600px 이하로 맞춰 휴대폰에서도 읽힌다</li>
 * </ul>
 * 색은 서비스 화면(client/src/app/globals.css)과 같은 값이다. 화면의 색이 바뀌면 여기도 같이 바꾼다.
 * HTML 을 보지 못하는 메일 앱을 위해 텍스트 본문도 함께 만든다.
 */
final class AuthMailTemplate {

	// 서비스 화면의 색 (globals.css)
	private static final String ACCENT = "#d23a1f";
	private static final String FOREGROUND = "#191b21";
	private static final String SECONDARY = "#4e5361";
	private static final String MUTED = "#747a8a";
	private static final String BACKGROUND = "#f3f4f8";
	private static final String RAISED = "#eff1f5";
	private static final String LINE = "#e3e4e8";
	private static final String WARN = "#965808";
	private static final String WARN_SOFT = "#fbefd8";

	private static final String FONT = "'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',Arial,sans-serif";

	private AuthMailTemplate() {
	}

	static String verificationText(String code, long validMinutes) {
		return """
			인증번호: %s
			%d분 동안 유효합니다.
			본인이 요청하지 않았다면 이 메일을 무시해 주세요.
			""".formatted(code, validMinutes);
	}

	/** 링크는 넣지 않는다. (피싱 메일과 헷갈리지 않게. 사용자가 번호를 화면에 직접 입력한다) 번호는 한눈에 보이게 큰 글씨 박스에 둔다. */
	static String verificationHtml(String brand, String code, long validMinutes) {
		String content = """
			<tr><td style="padding:36px 40px 8px 40px;font-family:{{font}};">
			  <div style="font-size:22px;line-height:1.4;font-weight:700;color:{{foreground}};">이메일 인증번호</div>
			  <div style="padding-top:14px;font-size:15px;line-height:1.7;color:{{secondary}};">
			    아래 인증번호를 화면에 입력해 이메일 인증을 마쳐 주세요.
			  </div>
			</td></tr>
			<tr><td align="center" style="padding:24px 40px 8px 40px;font-family:{{font}};">
			  <div style="padding:20px 16px;background-color:{{raised}};border-radius:8px;font-size:34px;line-height:1.2;font-weight:800;letter-spacing:10px;color:{{accent}};text-align:center;">{{code}}</div>
			</td></tr>
			<tr><td style="padding:20px 40px 0 40px;font-family:{{font}};">
			  <div style="padding:12px 16px;background-color:{{warnSoft}};border-radius:8px;font-size:14px;line-height:1.6;color:{{warn}};">
			    이 인증번호는 <strong>{{minutes}}분 동안</strong>만 유효합니다. 다시 받으면 이전 인증번호는 사용할 수 없게 됩니다.
			  </div>
			</td></tr>
			<tr><td style="padding:28px 40px 36px 40px;font-family:{{font}};">
			  <div style="padding-top:20px;border-top:1px solid {{line}};font-size:13px;line-height:1.6;color:{{muted}};">
			    본인이 요청하지 않았다면 이 메일을 무시해 주세요. 인증번호를 알려 주지 않으면 아무 일도 일어나지 않습니다.
			  </div>
			</td></tr>
			""";
		return layout(brand, "이메일 인증번호입니다. " + validMinutes + "분 동안 유효합니다.",
			content.replace("{{code}}", HtmlUtils.htmlEscape(code))
				.replace("{{minutes}}", String.valueOf(validMinutes)));
	}

	static String resetText(String account, long validMinutes, String link) {
		return """
			%s 계정의 비밀번호 재설정 안내입니다.
			아래 링크에서 새 비밀번호를 정해 주세요. %d분 동안 유효합니다.
			%s
			본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다.
			""".formatted(account, validMinutes, link);
	}

	/** 버튼이 보이지 않는 메일 앱을 위해 같은 링크를 글자로도 넣는다. */
	static String resetHtml(String brand, String account, long validMinutes, String link) {
		String safeLink = HtmlUtils.htmlEscape(link);
		String content = """
			<tr><td style="padding:36px 40px 8px 40px;font-family:{{font}};">
			  <div style="font-size:22px;line-height:1.4;font-weight:700;color:{{foreground}};">비밀번호 재설정</div>
			  <div style="padding-top:14px;font-size:15px;line-height:1.7;color:{{secondary}};">
			    <strong style="color:{{foreground}};">{{account}}</strong> 계정의 비밀번호 재설정을 요청하셨습니다.<br>
			    아래 버튼을 눌러 새 비밀번호를 정해 주세요.
			  </div>
			</td></tr>
			<tr><td align="center" style="padding:28px 40px 8px 40px;">
			  <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
			    <td align="center" bgcolor="{{accent}}" style="border-radius:8px;">
			      <a href="{{link}}" style="display:inline-block;padding:15px 40px;font-family:{{font}};font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:8px;">비밀번호 재설정</a>
			    </td>
			  </tr></table>
			</td></tr>
			<tr><td style="padding:20px 40px 0 40px;font-family:{{font}};">
			  <div style="padding:12px 16px;background-color:{{warnSoft}};border-radius:8px;font-size:14px;line-height:1.6;color:{{warn}};">
			    이 링크는 <strong>{{minutes}}분 동안</strong>만 유효합니다. 비밀번호를 바꾸면 이전에 받은 링크도 모두 사용할 수 없게 됩니다.
			  </div>
			</td></tr>
			<tr><td style="padding:24px 40px 0 40px;font-family:{{font}};">
			  <div style="font-size:13px;line-height:1.6;color:{{muted}};">버튼이 보이지 않으면 아래 주소를 복사해 브라우저에 붙여 넣어 주세요.</div>
			  <div style="margin-top:8px;padding:12px 14px;background-color:{{raised}};border-radius:8px;font-size:12px;line-height:1.6;color:{{secondary}};word-break:break-all;">{{link}}</div>
			</td></tr>
			<tr><td style="padding:28px 40px 36px 40px;font-family:{{font}};">
			  <div style="padding-top:20px;border-top:1px solid {{line}};font-size:13px;line-height:1.6;color:{{muted}};">
			    본인이 요청하지 않았다면 이 메일을 무시해 주세요. 링크를 누르지 않으면 비밀번호는 바뀌지 않습니다.
			  </div>
			</td></tr>
			""";
		return layout(brand, account + " 계정의 비밀번호 재설정 링크입니다. " + validMinutes + "분 동안 유효합니다.",
			content.replace("{{account}}", HtmlUtils.htmlEscape(account))
				.replace("{{minutes}}", String.valueOf(validMinutes))
				.replace("{{link}}", safeLink));
	}

	static String changedText(String account) {
		return """
			%s 계정의 비밀번호가 변경되었습니다.
			본인이 변경한 것이 아니라면 문의해 주세요.
			""".formatted(account);
	}

	static String changedHtml(String brand, String account) {
		String content = """
			<tr><td style="padding:36px 40px 8px 40px;font-family:{{font}};">
			  <div style="font-size:22px;line-height:1.4;font-weight:700;color:{{foreground}};">비밀번호가 변경되었습니다</div>
			  <div style="padding-top:14px;font-size:15px;line-height:1.7;color:{{secondary}};">
			    <strong style="color:{{foreground}};">{{account}}</strong> 계정의 비밀번호가 방금 변경되었습니다.<br>
			    새 비밀번호로 다시 로그인해 주세요.
			  </div>
			</td></tr>
			<tr><td style="padding:24px 40px 0 40px;font-family:{{font}};">
			  <div style="padding:12px 16px;background-color:{{warnSoft}};border-radius:8px;font-size:14px;line-height:1.6;color:{{warn}};">
			    본인이 변경한 것이 아니라면 계정이 위험할 수 있습니다. 바로 문의해 주세요.
			  </div>
			</td></tr>
			<tr><td style="padding:28px 40px 36px 40px;font-family:{{font}};">
			  <div style="padding-top:20px;border-top:1px solid {{line}};font-size:13px;line-height:1.6;color:{{muted}};">
			    이 메일은 비밀번호가 바뀐 계정으로 자동으로 발송되었습니다.
			  </div>
			</td></tr>
			""";
		return layout(brand, account + " 계정의 비밀번호가 변경되었습니다.",
			content.replace("{{account}}", HtmlUtils.htmlEscape(account)));
	}

	/** 두 메일이 같이 쓰는 바깥 틀. 맨 위 강조색 띠와 글자 로고, 아래 서비스 이름을 둔다. */
	private static String layout(String brand, String preheader, String content) {
		String html = """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			<meta charset="UTF-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0">
			<title>{{brand}}</title>
			</head>
			<body style="margin:0;padding:0;background-color:{{background}};">
			<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:{{background}};">{{preheader}}</div>
			<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="{{background}}" style="background-color:{{background}};">
			<tr><td align="center" style="padding:32px 12px;">
			  <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;background-color:#ffffff;border:1px solid {{line}};border-top:4px solid {{accent}};border-radius:12px;">
			    <tr><td style="padding:28px 40px 0 40px;font-family:{{font}};">
			      <span style="font-size:22px;font-weight:800;letter-spacing:-0.5px;color:{{accent}};">{{brand}}</span>
			    </td></tr>
			    {{content}}
			  </table>
			  <div style="padding-top:16px;font-family:{{font}};font-size:12px;line-height:1.6;color:{{muted}};">&copy; {{brand}}</div>
			</td></tr>
			</table>
			</body>
			</html>
			""";
		return html.replace("{{content}}", content)
			.replace("{{preheader}}", HtmlUtils.htmlEscape(preheader))
			.replace("{{brand}}", HtmlUtils.htmlEscape(brand))
			.replace("{{font}}", FONT)
			.replace("{{accent}}", ACCENT)
			.replace("{{foreground}}", FOREGROUND)
			.replace("{{secondary}}", SECONDARY)
			.replace("{{muted}}", MUTED)
			.replace("{{background}}", BACKGROUND)
			.replace("{{raised}}", RAISED)
			.replace("{{line}}", LINE)
			.replace("{{warn}}", WARN)
			.replace("{{warnSoft}}", WARN_SOFT);
	}
}
