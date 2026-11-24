import Link from "next/link";

export function Footer() {
  return (
    <footer className="border-t border-line bg-deep">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-12 text-sm sm:grid-cols-[1fr_auto_auto] sm:px-6">
        <div>
          <p className="text-lg leading-none font-medium">
            tick<span className="text-accent-strong">ting</span>
          </p>
          <p className="mt-4 max-w-sm text-secondary">
            좌석을 직접 고르고, 7분 안에 결제하고, 공연일까지 남은 기간에 따라 수수료를 내고 취소하는 공연 예매 서비스입니다.
          </p>
        </div>
        <nav className="space-y-2">
          <p className="text-muted">공연</p>
          <Link href="/performances" className="block text-secondary hover:text-foreground">
            전체 공연
          </Link>
          <Link href="/performances?genre=CONCERT" className="block text-secondary hover:text-foreground">
            콘서트
          </Link>
          <Link href="/performances?genre=MUSICAL" className="block text-secondary hover:text-foreground">
            뮤지컬
          </Link>
        </nav>
        <nav className="space-y-2">
          <p className="text-muted">내 계정</p>
          <Link href="/bookings" className="block text-secondary hover:text-foreground">
            내 예매
          </Link>
          <Link href="/me" className="block text-secondary hover:text-foreground">
            내 정보
          </Link>
          <Link href="/login" className="block text-secondary hover:text-foreground">
            로그인
          </Link>
          <Link href="/seller/login" className="block text-secondary hover:text-foreground">
            판매자 센터
          </Link>
        </nav>
      </div>
      <p className="border-t border-line py-6 text-center text-xs text-muted">
        tickting · 취업 포트폴리오 프로젝트
      </p>
    </footer>
  );
}
