"use client";

import Link from "next/link";

// 화면을 그리다 예상하지 못한 오류가 나도 빈 화면 대신 안내와 다시 시도 버튼을 보여 준다.
// 오류 내용은 사용자에게 보이지 않는다. (서버 내부 정보가 섞일 수 있다)
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="mx-auto max-w-md py-24 text-center">
      <h1 className="text-2xl font-semibold">문제가 생겼습니다</h1>
      <p className="mt-3 text-sm text-secondary">잠시 뒤 다시 시도해 주세요. 계속 안 되면 처음 화면으로 돌아가 주세요</p>
      <div className="mt-6 flex justify-center gap-2">
        <button type="button" className="btn" onClick={reset}>
          다시 시도
        </button>
        <Link href="/" className="btn btn-primary">
          처음으로
        </Link>
      </div>
    </div>
  );
}
