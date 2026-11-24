import { LoginForm } from "@/components/login-form";

// 관리자 로그인. 공개 화면 어디에서도 이 주소로 연결하지 않는다. (가입·비밀번호 찾기도 없다)
export default function AdminLoginPage() {
  return <LoginForm accountType="ADMIN" title="관리자 로그인" home="/admin" />;
}
