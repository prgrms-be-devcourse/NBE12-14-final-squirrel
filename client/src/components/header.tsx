"use client";

import { Search, Ticket, User } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api, useMockSeen } from "@/lib/api";
import { clearAccount, useAccount } from "@/lib/auth";
import { GENRE_LABEL } from "@/lib/format";
import type { Genre } from "@/lib/types";

const TAB_GENRES: Genre[] = ["CONCERT", "MUSICAL", "PLAY", "CLASSIC"];

export function Header() {
  const account = useAccount();
  const mockSeen = useMockSeen();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [keyword, setKeyword] = useState(searchParams.get("keyword") ?? "");

  const genre = searchParams.get("genre");
  const onList = pathname === "/performances";

  async function logout() {
    try {
      await api("/api/v2/auth/logout", { method: "POST" });
    } finally {
      clearAccount();
      router.push("/");
    }
  }

  function search(event: React.FormEvent) {
    event.preventDefault();
    const text = keyword.trim();
    router.push(text ? `/performances?keyword=${encodeURIComponent(text)}` : "/performances");
  }

  const tabs: { href: string; label: string; active: boolean }[] = [
    { href: "/", label: "홈", active: pathname === "/" },
    { href: "/performances", label: "전체 공연", active: onList && !genre },
    ...TAB_GENRES.map((g) => ({
      href: `/performances?genre=${g}`,
      label: GENRE_LABEL[g],
      active: onList && genre === g,
    })),
  ];

  const iconLink =
    "flex flex-col items-center gap-1 text-xs text-secondary transition-colors duration-150 hover:text-foreground";

  return (
    <header className="sticky top-0 z-20 border-b border-line bg-background/95 backdrop-blur">
      {mockSeen && (
        <div className="bg-foreground px-4 py-2 text-center text-xs text-white">
          일부 화면은 목 데이터로 동작 중입니다 (Spring 에 없거나 연결되지 않은 API)
        </div>
      )}
      <div className="mx-auto flex h-20 max-w-6xl items-center gap-4 px-4 sm:gap-8 sm:px-6">
        <Link href="/" className="text-2xl leading-none font-semibold">
          tick<span className="text-accent">ting</span>
        </Link>

        <form onSubmit={search} className="relative hidden max-w-xl flex-1 sm:block">
          <input
            className="input h-12 rounded-full pr-14 pl-6"
            placeholder="공연 제목이나 출연진을 검색하세요"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            aria-label="공연 검색"
          />
          <button
            type="submit"
            aria-label="검색"
            className="absolute top-1/2 right-4 -translate-y-1/2 text-accent transition-colors duration-150 hover:text-accent-strong"
          >
            <Search size={20} strokeWidth={1.5} />
          </button>
        </form>

        <div className="ml-auto flex items-center gap-5 text-sm">
          {account?.accountType === "USER" && (
            <>
              <Link href="/bookings" className={iconLink}>
                <Ticket size={20} strokeWidth={1.5} />내 예매
              </Link>
              <Link href="/me" className={iconLink}>
                <User size={20} strokeWidth={1.5} />
                마이
              </Link>
            </>
          )}
          {account?.accountType === "SELLER" && (
            <Link href="/seller" className="text-secondary hover:text-foreground">
              판매자 센터
            </Link>
          )}
          {account?.accountType === "ADMIN" && (
            <Link href="/admin" className="text-secondary hover:text-foreground">
              관리자
            </Link>
          )}
          {account === undefined ? null : account ? (
            <button
              type="button"
              onClick={logout}
              className="text-secondary transition-colors duration-150 hover:text-foreground"
            >
              로그아웃
            </button>
          ) : (
            <>
              <Link href="/login" className="text-secondary transition-colors duration-150 hover:text-foreground">
                로그인
              </Link>
              <Link href="/signup" className="btn btn-dark rounded-full">
                가입
              </Link>
            </>
          )}
        </div>
      </div>

      <nav className="mx-auto max-w-6xl overflow-x-auto px-4 no-scrollbar sm:px-6" aria-label="공연 메뉴">
        <ul className="flex gap-8 text-[15px]">
          {tabs.map((tab) => (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={tab.active ? "page" : undefined}
                className={`block border-b-2 py-3 whitespace-nowrap transition-colors duration-150 ${
                  tab.active
                    ? "border-accent font-semibold text-foreground"
                    : "border-transparent text-secondary hover:text-foreground"
                }`}
              >
                {tab.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </header>
  );
}
