"use client";

import { X } from "lucide-react";

export function PopupFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-5xl px-4 pb-10 sm:px-6">
      <div className="flex items-center justify-between border-b border-line py-4">
        <p className="text-lg leading-none font-medium">
          tick<span className="text-accent-strong">ting</span>
          <span className="ml-3 text-sm font-normal text-muted">예매</span>
        </p>
        <button
          type="button"
          aria-label="예매 창 닫기"
          className="text-muted transition-colors duration-150 hover:text-foreground"
          onClick={() => window.close()}
        >
          <X size={20} strokeWidth={1.5} />
        </button>
      </div>
      <div className="pt-8">{children}</div>
    </div>
  );
}
