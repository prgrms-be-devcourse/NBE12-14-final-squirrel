"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { clearAccount } from "@/lib/auth";
import { dateText } from "@/lib/format";
import type { UserMe } from "@/lib/types";
import { ImageUpload } from "@/components/image-upload";
import { ErrorBox, Loading, PageTitle } from "@/components/ui";

type Message = { text: string; error: boolean };

export default function MePage() {
  const router = useRouter();
  const { data, error, loading, reload } = useApi<UserMe>("/api/v1/users/me");

  const [editing, setEditing] = useState(false);
  const [nickname, setNickname] = useState("");
  const [profileImg, setProfileImg] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [confirmingWithdraw, setConfirmingWithdraw] = useState(false);

  // 화면을 그리는 도중에 이동하면 React 가 경고한다. 로그인이 풀렸으면 그린 뒤에 이동한다.
  const unauthorized = error?.status === 401;
  useEffect(() => {
    if (unauthorized) router.push("/login");
  }, [unauthorized, router]);
  if (unauthorized) return null;

  function startEdit(me: UserMe) {
    setNickname(me.nickname);
    setProfileImg(me.profileImg ?? "");
    setMessage(null);
    setEditing(true);
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      // 닉네임과 사진을 보낸 값으로 통째로 바꾼다. 사진을 비우면(null) 사진이 지워진다.
      await api("/api/v1/users/me", {
        method: "PATCH",
        body: { nickname: nickname.trim(), profileImg: profileImg || null },
      });
      setEditing(false);
      setMessage({ text: "내 정보를 저장했습니다", error: false });
      reload();
    } catch (e) {
      setMessage({ text: e instanceof ApiError ? e.message : "저장하지 못했습니다", error: true });
    } finally {
      setBusy(false);
    }
  }

  async function withdraw() {
    setBusy(true);
    setMessage(null);
    try {
      await api("/api/v1/users/me", { method: "DELETE" });
      // 서버가 이 기기의 세션을 지웠다. 화면의 로그인 표시도 지우고 처음으로 돌아간다.
      clearAccount();
      router.replace("/");
    } catch (e) {
      setConfirmingWithdraw(false);
      setMessage({ text: e instanceof ApiError ? e.message : "탈퇴하지 못했습니다", error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl">
      <PageTitle title="내 정보" />
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {message && (
        <p
          role="status"
          className={`mb-5 rounded-xl px-4 py-3 text-sm ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}
        >
          {message.text}
        </p>
      )}
      {data && (
        <>
          <div className="mb-5 flex items-center gap-4">
            {data.profileImg ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={data.profileImg} alt="프로필 사진" className="size-16 rounded-full border border-line object-cover" />
            ) : (
              <span className="flex size-16 items-center justify-center rounded-full bg-raised text-xl font-semibold text-secondary">
                {data.nickname.slice(0, 1)}
              </span>
            )}
            <div>
              <p className="text-lg font-semibold">{data.nickname}</p>
              <p className="text-sm text-muted">{data.email}</p>
            </div>
            {!editing && (
              <button type="button" className="btn ml-auto" onClick={() => startEdit(data)}>
                수정
              </button>
            )}
          </div>

          {editing && (
            <form onSubmit={save} className="card mb-5 space-y-5 p-6">
              <div>
                <label className="label" htmlFor="nickname">
                  닉네임
                </label>
                <input
                  id="nickname"
                  className="input"
                  value={nickname}
                  minLength={2}
                  maxLength={20}
                  onChange={(e) => setNickname(e.target.value)}
                  required
                />
                <p className="mt-1 text-xs text-muted">2~20자</p>
              </div>
              <div>
                <span className="label">프로필 사진</span>
                <ImageUpload value={profileImg} purpose="PROFILE" label="사진" onChange={setProfileImg} />
                {profileImg && (
                  <button type="button" className="mt-2 text-sm text-secondary hover:text-foreground" onClick={() => setProfileImg("")}>
                    사진 지우기
                  </button>
                )}
              </div>
              <div className="flex justify-end gap-2">
                <button type="button" className="btn" onClick={() => setEditing(false)}>
                  취소
                </button>
                <button type="submit" className="btn btn-primary" disabled={busy}>
                  저장
                </button>
              </div>
            </form>
          )}

          <dl className="card divide-y divide-line text-sm">
            {[
              ["이메일", data.email],
              ["이름", data.name],
              ["닉네임", data.nickname],
              ["휴대폰", data.phone],
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

          <section className="mt-12 border-t border-line pt-6">
            <h2 className="font-semibold">회원 탈퇴</h2>
            <p className="mt-2 text-sm text-secondary">
              개인정보가 지워지고 되돌릴 수 없습니다. 결제 대기 중이거나 공연 전인 예매, 취소되어 환불을 기다리는 예매가 있으면 탈퇴할 수 없습니다.
            </p>
            {confirmingWithdraw ? (
              <div className="mt-4 flex gap-2">
                <button type="button" className="btn" onClick={() => setConfirmingWithdraw(false)}>
                  취소
                </button>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={withdraw}>
                  탈퇴하기
                </button>
              </div>
            ) : (
              <button type="button" className="btn mt-4" onClick={() => setConfirmingWithdraw(true)}>
                회원 탈퇴
              </button>
            )}
          </section>
        </>
      )}
    </div>
  );
}
