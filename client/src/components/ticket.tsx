import type { ReactNode } from "react";

// 절취선이 있는 종이 티켓. 결제와 예매 상세처럼 "내 표"를 보여 주는 곳에만 쓴다.
export function Ticket({
  head,
  children,
  stub,
}: {
  head: ReactNode;
  children: ReactNode;
  stub: ReactNode;
}) {
  return (
    <div className="ticket">
      <div className="p-6 pb-4">{head}</div>
      <div className="px-6 pb-6">{children}</div>
      <div className="ticket-perforation" />
      <div className="p-6">{stub}</div>
    </div>
  );
}
