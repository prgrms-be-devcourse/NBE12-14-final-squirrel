import Link from "next/link";

export default function NotFound() {
  return (
    <div className="mx-auto max-w-xl px-4 py-32 text-center">
      <p className="text-6xl font-semibold text-accent-strong">404</p>
      <h1 className="mt-6 text-3xl font-semibold">찾을 수 없는 페이지입니다</h1>
      <p className="mt-3 text-secondary">주소가 바뀌었거나 없는 페이지입니다</p>
      <Link href="/performances" className="btn btn-primary btn-lg mt-10">
        공연 보러 가기
      </Link>
    </div>
  );
}
