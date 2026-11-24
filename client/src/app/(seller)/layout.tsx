import type { Metadata } from "next";
import { Suspense } from "react";
import { sans } from "@/app/fonts";
import { AccountScope } from "@/components/account-scope";
import "@/app/globals.css";

// 판매자 센터의 루트 레이아웃. 예매자 사이트의 머리글·바닥글을 쓰지 않는다.
// 검색 엔진에는 노출하지 않는다.
export const metadata: Metadata = {
  title: "tickting 판매자 센터",
  robots: { index: false, follow: false },
};

export default function SellerRootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={`${sans.variable} h-full antialiased`}>
      <body data-shell="seller" className="flex min-h-full flex-col bg-deep">
        <Suspense fallback={<p className="py-24 text-center text-sm text-muted">불러오는 중</p>}>
          <AccountScope>{children}</AccountScope>
        </Suspense>
      </body>
    </html>
  );
}
