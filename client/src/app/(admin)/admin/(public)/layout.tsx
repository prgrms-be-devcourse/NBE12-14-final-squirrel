import { StaffPublicShell } from "@/components/staff-shell";

// 관리자 로그인. 콘솔 가드((console))와 다른 그룹에 두어 로그인하기 전에도 열린다.
export default function AdminPublicLayout({ children }: { children: React.ReactNode }) {
  return <StaffPublicShell role="ADMIN">{children}</StaffPublicShell>;
}
