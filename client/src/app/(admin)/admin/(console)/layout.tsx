import { ConsoleShell } from "@/components/console";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <ConsoleShell role="ADMIN">{children}</ConsoleShell>;
}
