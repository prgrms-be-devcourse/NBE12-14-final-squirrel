import { Noto_Sans_KR } from "next/font/google";

// 예매자·판매자·관리자 루트 레이아웃이 같이 쓰는 글꼴. next/font 는 모듈 안에서 한 번만 불러야 해서 한곳에 둔다.
export const sans = Noto_Sans_KR({
  variable: "--font-noto",
  subsets: ["latin"],
  display: "swap",
});
