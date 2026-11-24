"use client";

import { Fragment } from "react";
import { useAccountEpoch } from "@/lib/auth";

// 로그인·로그아웃이 일어나면 아래 화면을 모두 처음부터 다시 그린다.
// 화면을 벗어나도 그 화면의 상태(입력값, 불러온 데이터)가 보관되는 설정(cacheComponents)이라,
// 로그아웃 뒤 다른 계정으로 로그인하면 이전 계정의 이름·예매 내역·비밀번호 입력값이 잠깐 보이거나 남을 수 있다.
export function AccountScope({ children }: { children: React.ReactNode }) {
  const epoch = useAccountEpoch();
  return <Fragment key={epoch}>{children}</Fragment>;
}
