import { ConsoleShell } from "@/components/console";

export default function SellerLayout({ children }: { children: React.ReactNode }) {
  return <ConsoleShell role="SELLER">{children}</ConsoleShell>;
}
