import type { Metadata } from "next";
import { Suspense } from "react";
import { sans } from "@/app/fonts";
import { AccountScope } from "@/components/account-scope";
import { Footer } from "@/components/footer";
import { Header } from "@/components/header";
import { HideInPopup } from "@/components/site-chrome";
import "@/app/globals.css";

// 예매자 화면의 루트 레이아웃. 판매자((seller))·관리자((admin))는 각자의 루트 레이아웃을 따로 갖는다.
// 루트 레이아웃이 달라서 세 화면은 머리글·바닥글·색을 서로 섞지 않는다.
export const metadata: Metadata = {
  title: "tickting",
  description: "공연 티켓을 좌석까지 골라 예매하는 서비스",
};

export default function BookerRootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={`${sans.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        {/* 현재 경로와 검색 조건을 읽는 헤더도 요청 때 채워지게 한다 */}
        <Suspense fallback={<div className="h-28 border-b border-line" />}>
          <HideInPopup>
            <Header />
          </HideInPopup>
        </Suspense>
        <main className="flex-1">
          {/* 경로 값(useParams)을 읽는 화면이 미리 그려지지 않고 요청 때 채워지게 한다 */}
          <Suspense fallback={<p className="py-24 text-center text-sm text-muted">불러오는 중</p>}>
            <AccountScope>{children}</AccountScope>
          </Suspense>
        </main>
        <Suspense>
          <HideInPopup>
            <Footer />
          </HideInPopup>
        </Suspense>
      </body>
    </html>
  );
}
