"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import { BUSINESS_BADGE, BUSINESS_LABEL } from "@/lib/console";
import type { BusinessDetail } from "@/lib/types";
import { ConsoleTitle, Field, Modal, Panel } from "@/components/console";
import { ErrorBox, Loading } from "@/components/ui";

export default function BusinessDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data: b, error, loading, reload } = useApi<BusinessDetail>(`/api/v1/admin/seller-businesses/${id}`);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  async function review(action: "approve" | "reject") {
    setBusy(true);
    setMessage(null);
    try {
      await api(`/api/v1/admin/seller-businesses/${id}/${action}`, {
        method: "POST",
        body: action === "reject" ? { reason: reason.trim() } : undefined,
      });
      setMessage({ text: action === "approve" ? "승인했습니다" : "반려했습니다", error: false });
      setRejecting(false);
      reload();
    } catch (e) {
      setMessage({ text: e instanceof ApiError ? e.message : "처리하지 못했습니다", error: true });
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!b) return null;

  return (
    <>
      <ConsoleTitle
        title={b.companyName}
        sub={`사업자번호 ${b.businessNumber}`}
        back={{ href: "/admin/businesses", label: "사업체 심사" }}
        actions={
          <>
            <span className={BUSINESS_BADGE[b.status]}>{BUSINESS_LABEL[b.status]}</span>
            {b.status === "PENDING" && (
              <>
                <button type="button" className="btn" disabled={busy} onClick={() => setRejecting(true)}>
                  반려
                </button>
                <button type="button" className="btn btn-primary" disabled={busy} onClick={() => review("approve")}>
                  승인
                </button>
              </>
            )}
          </>
        }
      />

      {message && (
        <p role="status" className={`mb-6 rounded-xl px-4 py-3 text-sm ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}>
          {message.text}
        </p>
      )}

      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="사업체 정보">
          <dl className="grid grid-cols-[104px_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted">상호</dt>
            <dd>{b.companyName}</dd>
            <dt className="text-muted">대표자</dt>
            <dd>{b.representativeName}</dd>
            <dt className="text-muted">업무 연락처</dt>
            <dd>{b.contactPhone}</dd>
            <dt className="text-muted">업무 이메일</dt>
            <dd>{b.contactEmail}</dd>
            <dt className="text-muted">약관 동의</dt>
            <dd>{dateTimeText(b.termsAgreedAt)}</dd>
            <dt className="text-muted">신청일</dt>
            <dd>{dateTimeText(b.createdAt)}</dd>
          </dl>
        </Panel>

        <Panel title="대표 계정" sub="신청과 함께 가입한 본인입니다. 승인되기 전에는 로그인할 수 없습니다">
          {b.owner ? (
            <dl className="grid grid-cols-[104px_1fr] gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">로그인 이메일</dt>
              <dd>{b.owner.email}</dd>
              <dt className="text-muted">본인인증 휴대폰</dt>
              <dd>{b.owner.phone}</dd>
              <dt className="text-muted">계정 상태</dt>
              <dd>{b.owner.status}</dd>
            </dl>
          ) : (
            <p className="text-sm text-muted">탈퇴 처리된 대표 계정입니다</p>
          )}
        </Panel>
      </div>

      {(b.reviewedAt || b.rejectReason) && (
        <Panel className="mt-6" title="심사 결과">
          <dl className="grid grid-cols-[104px_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted">처리 시각</dt>
            <dd>{b.reviewedAt ? dateTimeText(b.reviewedAt) : "-"}</dd>
            <dt className="text-muted">처리 관리자</dt>
            <dd>{b.reviewedBy ?? "-"}</dd>
            {b.rejectReason && (
              <>
                <dt className="text-muted">반려 사유</dt>
                <dd>{b.rejectReason}</dd>
              </>
            )}
          </dl>
        </Panel>
      )}

      {rejecting && (
        <Modal title="반려 사유" onClose={() => setRejecting(false)}>
          <form
            className="space-y-5"
            onSubmit={(e) => {
              e.preventDefault();
              if (reason.trim()) review("reject");
            }}
          >
            <p className="text-sm text-secondary">반려한 사업체는 같은 사업자번호로 다시 신청할 수 없습니다. 사유는 최대 500자입니다</p>
            <Field label="사유">
              <textarea className="input h-28 py-3" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required />
            </Field>
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={() => setRejecting(false)}>
                닫기
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                반려하기
              </button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
