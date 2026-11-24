import Link from "next/link";
import { LoginForm } from "@/components/login-form";

// 예매자 로그인. 판매자·관리자 로그인은 이 화면에 두지 않고 각자의 주소로 따로 둔다.
export default function LoginPage() {
  return (
    <LoginForm accountType="USER" title="로그인" home="/">
      <p className="mt-4 text-center text-sm text-muted">
        비밀번호를 잊었나요?{" "}
        <Link href="/forgot-password" className="text-foreground underline">
          비밀번호 찾기
        </Link>
      </p>
      <p className="mt-4 text-center text-sm text-muted">
        계정이 없나요?{" "}
        <Link href="/signup" className="text-foreground underline">
          가입하기
        </Link>
      </p>
    </LoginForm>
  );
}
