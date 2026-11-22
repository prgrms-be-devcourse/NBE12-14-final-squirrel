import { PopupFrame } from "@/components/popup-frame";

// 예매 창. 사이트 머리글·바닥글 없이 상단에 작은 제목 줄만 둔다.
export default function PopupLayout({ children }: { children: React.ReactNode }) {
  return <PopupFrame>{children}</PopupFrame>;
}
