import { StaffPublicShell } from "@/components/staff-shell";

// 판매자 로그인·가입 신청. 콘솔 가드((console))와 다른 그룹에 두어 로그인하기 전에도 열린다.
export default function SellerPublicLayout({ children }: { children: React.ReactNode }) {
  return <StaffPublicShell role="SELLER">{children}</StaffPublicShell>;
}
