"use client";

import { Briefcase, Building, FileText, LayoutDashboard, MapPin, RotateCcw, Ticket, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { StaffBar } from "@/components/staff-shell";
import { useAccount } from "@/lib/auth";
import type { AccountType } from "@/lib/types";

type NavItem = { href: string; label: string; icon: LucideIcon; exact?: boolean };

const SELLER_NAV: NavItem[] = [
  { href: "/seller", label: "대시보드", icon: LayoutDashboard, exact: true },
  { href: "/seller/performances", label: "공연", icon: Ticket },
  { href: "/seller/cancel-policies", label: "취소 규정", icon: FileText },
  { href: "/seller/venues", label: "공연장", icon: MapPin },
];

const ADMIN_NAV: NavItem[] = [
  { href: "/admin", label: "개요", icon: LayoutDashboard, exact: true },
  { href: "/admin/businesses", label: "사업체 심사", icon: Briefcase },
  { href: "/admin/venues", label: "공연장", icon: Building },
  { href: "/admin/refunds", label: "환불 처리", icon: RotateCcw },
];

// 판매자·관리자 화면의 공통 틀. 계정 종류가 맞지 않으면 내용을 보여 주지 않는다.
// 진짜 권한 검사는 Spring 이 하고, 여기는 잘못 들어온 사람에게 안내만 한다.
// 판매자·관리자는 각자의 로그인 화면으로 보낸다. 예매자 로그인(/login)으로는 보내지 않는다.
const LOGIN_PATH = { SELLER: "/seller/login", ADMIN: "/admin/login" } as const;

export function ConsoleShell({ role, children }: { role: Exclude<AccountType, "USER">; children: React.ReactNode }) {
  const account = useAccount();
  const router = useRouter();
  const pathname = usePathname();
  const nav = role === "SELLER" ? SELLER_NAV : ADMIN_NAV;

  useEffect(() => {
    if (account === null) router.replace(LOGIN_PATH[role]);
  }, [account, router, role]);

  if (account === undefined || account === null) {
    return <p className="py-24 text-center text-sm text-muted">확인하는 중</p>;
  }
  if (account.accountType !== role) {
    return (
      <div className="mx-auto max-w-md px-4 py-24 text-center">
        <p className="text-lg font-semibold">접근할 수 없는 화면입니다</p>
        <p className="mt-2 text-secondary">{role === "SELLER" ? "판매자" : "관리자"} 계정으로 로그인해 주세요</p>
        <Link href={LOGIN_PATH[role]} className="btn btn-primary btn-lg mt-8">
          로그인
        </Link>
      </div>
    );
  }

  return (
    <>
      <StaffBar role={role} />
      <div className="flex-1 bg-deep">
        <div className="mx-auto grid max-w-[1400px] gap-8 px-4 py-8 sm:px-6 lg:grid-cols-[220px_1fr]">
          <aside className="lg:sticky lg:top-8 lg:self-start">
          <nav
            aria-label="콘솔 메뉴"
            className="no-scrollbar -mx-4 flex gap-1 overflow-x-auto px-4 lg:mx-0 lg:flex-col lg:px-0"
          >
            {nav.map(({ href, label, icon: Icon, exact }) => {
              const active = exact ? pathname === href : pathname.startsWith(href);
              return (
                <Link
                  key={href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  className={`flex h-11 shrink-0 items-center gap-3 rounded-xl px-4 text-sm whitespace-nowrap transition-colors duration-150 ${
                    active
                      ? "bg-surface font-semibold text-foreground shadow-[0_1px_2px_rgba(25,27,33,0.06)]"
                      : "text-secondary hover:bg-surface/60 hover:text-foreground"
                  }`}
                >
                  <Icon size={18} strokeWidth={1.5} />
                  {label}
                </Link>
              );
            })}
          </nav>
          </aside>
          <div className="min-w-0">{children}</div>
        </div>
      </div>
    </>
  );
}

export function ConsoleTitle({
  title,
  sub,
  actions,
  back,
}: {
  title: string;
  sub?: string;
  actions?: React.ReactNode;
  back?: { href: string; label: string };
}) {
  return (
    <div className="mb-8">
      {back && (
        <Link
          href={back.href}
          className="mb-3 inline-block text-sm text-secondary transition-colors duration-150 hover:text-foreground"
        >
          ← {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{title}</h1>
          {sub && <p className="mt-1 text-secondary">{sub}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}

export function Panel({
  title,
  sub,
  actions,
  children,
  className = "",
}: {
  title?: string;
  sub?: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={`card p-6 ${className}`}>
      {(title || actions) && (
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            {title && <h2 className="font-semibold">{title}</h2>}
            {sub && <p className="mt-1 text-sm text-secondary">{sub}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function StatCard({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string | number;
  hint?: string;
  tone?: "accent";
}) {
  return (
    <div className="card p-5">
      <p className="text-sm text-secondary">{label}</p>
      <p className={`mt-2 text-3xl font-semibold ${tone === "accent" ? "text-accent-strong" : ""}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </div>
  );
}

export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="card w-full max-w-lg p-6 shadow-[0_16px_48px_rgba(0,0,0,0.2)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-5 flex items-center justify-between">
          <h2 className="text-lg font-semibold">{title}</h2>
          <button
            type="button"
            aria-label="닫기"
            onClick={onClose}
            className="text-muted transition-colors duration-150 hover:text-foreground"
          >
            <X size={20} strokeWidth={1.5} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function Field({
  label,
  hint,
  children,
  className = "",
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={`block ${className}`}>
      <span className="label">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-muted">{hint}</span>}
    </label>
  );
}

export function Table({ head, children }: { head: string[]; children: React.ReactNode }) {
  return (
    <div className="card overflow-x-auto">
      <table className="w-full min-w-[560px] text-sm">
        <thead className="bg-raised text-left text-secondary">
          <tr>
            {head.map((h, i) => (
              <th key={`${h}-${i}`} className="px-5 py-3 font-medium whitespace-nowrap">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">{children}</tbody>
      </table>
    </div>
  );
}
