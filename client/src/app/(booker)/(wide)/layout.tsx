// 좌석 선택처럼 도면이 넓게 필요한 화면의 폭. 일반 화면((site))보다 넓게 잡아 좌석을 누르기 쉽게 한다.
export default function WideLayout({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto w-full max-w-[1600px] px-4 py-10 sm:px-6">{children}</div>;
}
