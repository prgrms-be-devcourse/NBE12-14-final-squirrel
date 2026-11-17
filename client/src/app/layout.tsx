import type { Metadata } from "next";
import { Noto_Sans_KR } from "next/font/google";
import { Suspense } from "react";
import { Footer } from "@/components/footer";
import { Header } from "@/components/header";
import "./globals.css";

const sans = Noto_Sans_KR({
  variable: "--font-noto",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "tickting",
  description: "공연 티켓을 좌석까지 골라 예매하는 서비스",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className={`${sans.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        {/* 현재 경로와 검색 조건을 읽는 헤더도 요청 때 채워지게 한다 */}
        <Suspense fallback={<div className="h-28 border-b border-line" />}>
          <Header />
        </Suspense>
        <main className="flex-1">
          {/* 경로 값(useParams)을 읽는 화면이 미리 그려지지 않고 요청 때 채워지게 한다 */}
          <Suspense fallback={<p className="py-24 text-center text-sm text-muted">불러오는 중</p>}>
            {children}
          </Suspense>
        </main>
        <Footer />
      </body>
    </html>
  );
}
