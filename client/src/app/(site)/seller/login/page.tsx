import Link from "next/link";
import { LoginForm } from "@/components/login-form";

// 판매자 로그인. /seller/** 콘솔의 가드와 다른 곳에 두어야 로그인하기 전에도 열린다. (route group 이 달라서 가드가 씌워지지 않는다)
export default function SellerLoginPage() {
  return (
    <LoginForm accountType="SELLER" title="판매자 로그인" home="/seller">
      <p className="mt-4 text-center text-sm text-muted">
        비밀번호를 잊었나요?{" "}
        <Link href="/forgot-password?type=seller" className="text-foreground underline">
          비밀번호 찾기
        </Link>
      </p>
      <p className="mt-4 text-center text-sm text-muted">
        판매자로 등록하려면{" "}
        <Link href="/signup/seller" className="text-foreground underline">
          가입 신청
        </Link>
      </p>
    </LoginForm>
  );
}
