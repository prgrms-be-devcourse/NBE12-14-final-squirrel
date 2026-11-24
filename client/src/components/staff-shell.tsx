"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { clearAccount, useAccount } from "@/lib/auth";

// 판매자 센터·관리자 화면의 상단 바. 예매자 사이트의 머리글과 겹치지 않는 별도 틀이다.
// 판매자는 강조색 바, 관리자는 어두운 바로 구분한다. (강조색은 globals.css 의 data-shell 로 정한다)
type StaffRole = "SELLER" | "ADMIN";

const SHELL = {
  SELLER: { home: "/seller", login: "/seller/login", tag: "판매자 센터", bar: "bg-accent-soft text-accent-strong" },
  ADMIN: { home: "/admin", login: "/admin/login", tag: "ADMIN", bar: "bg-foreground text-white" },
} as const;

export function StaffBar({ role }: { role: StaffRole }) {
  const router = useRouter();
  const account = useAccount();
  const shell = SHELL[role];
  const signedIn = account?.accountType === role;

  async function logout() {
    try {
      await api("/api/v2/auth/logout", { method: "POST" });
    } catch {
      // 서버가 이미 세션을 잃은 경우에도 이 브라우저에서는 로그아웃한 것으로 둔다.
    } finally {
      clearAccount();
      router.push(shell.login);
    }
  }

  return (
    <header className={`border-b border-line ${shell.bar}`}>
      <div className="mx-auto flex h-14 max-w-[1400px] items-center gap-3 px-4 sm:px-6">
        <Link href={signedIn ? shell.home : shell.login} className="text-lg leading-none font-medium">
          tick<span className="opacity-70">ting</span>
        </Link>
        <span className="rounded-full border border-current px-2.5 py-0.5 text-xs">{shell.tag}</span>
        {signedIn && (
          <span className="ml-auto flex items-center gap-4 text-sm">
            {role === "SELLER" && account?.role && (
              <span className="opacity-80">{account.role === "SELLER_OWNER" ? "대표" : "담당자"}</span>
            )}
            <button type="button" onClick={logout} className="underline-offset-4 hover:underline">
              로그아웃
            </button>
          </span>
        )}
      </div>
    </header>
  );
}

// 로그인·가입 같은 공개 화면의 틀. 상단 바는 로고와 이름표만 두고 가운데에 내용을 놓는다.
export function StaffPublicShell({ role, children }: { role: StaffRole; children: React.ReactNode }) {
  return (
    <>
      <StaffBar role={role} />
      <main className="mx-auto w-full max-w-md flex-1 px-4 py-16 sm:px-6">{children}</main>
    </>
  );
}
