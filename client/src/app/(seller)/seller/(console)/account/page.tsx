"use client";

import { ConsoleTitle } from "@/components/console";
import { PasswordChangeForm } from "@/components/password-change-form";

export default function SellerAccountPage() {
  return (
    <div className="max-w-xl">
      <ConsoleTitle title="내 계정" />
      <h2 className="mb-3 font-semibold">비밀번호 변경</h2>
      <PasswordChangeForm endpoint="/api/v1/seller/me/password" />
    </div>
  );
}
