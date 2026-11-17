"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useApi } from "@/lib/api";
import { dateText } from "@/lib/format";
import type { UserMe } from "@/lib/types";
import { ErrorBox, Loading, PageTitle } from "@/components/ui";

export default function MePage() {
  const router = useRouter();
  const { data, error, loading, reload } = useApi<UserMe>("/api/v1/users/me");

  if (error?.status === 401) {
    router.push("/login");
    return null;
  }

  return (
    <div className="mx-auto max-w-xl">
      <PageTitle title="내 정보" />
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && (
        <>
          <dl className="card divide-y divide-line text-sm">
            {[
              ["이메일", data.email],
              ["이름", data.name],
              ["닉네임", data.nickname],
              ["휴대폰", data.phone],
              ["생년월일", data.birthDate],
              ["가입일", dateText(data.createdAt)],
            ].map(([label, value]) => (
              <div key={label} className="flex justify-between gap-4 px-6 py-4">
                <dt className="text-muted">{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <Link href="/bookings" className="btn btn-lg mt-6 w-full">
            내 예매 보기
          </Link>
        </>
      )}
    </div>
  );
}
