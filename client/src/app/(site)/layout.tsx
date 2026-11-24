import { PopupBridge } from "@/components/popup-bridge";

// 랜딩을 뺀 일반 화면의 공통 폭과 여백
export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      {/* 예매 창에서 결제를 마쳤다는 알림을 받는다 */}
      <PopupBridge />
      {children}
    </div>
  );
}
