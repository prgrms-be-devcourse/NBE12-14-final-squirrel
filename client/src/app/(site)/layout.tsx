// 랜딩을 뺀 일반 화면의 공통 폭과 여백
export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">{children}</div>;
}
