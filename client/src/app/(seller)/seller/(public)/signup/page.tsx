"use client";

import Link from "next/link";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";

// 판매자 가입 신청 (AUTH-09). 사업체(승인 대기)와 대표자 계정이 함께 만들어지고, 관리자가 승인하기 전에는 로그인할 수 없다.
// 가입 응답은 로그인이 아니라 접수 결과라서, 접수했다는 안내만 보여 준다.

const EMPTY_FORM = {
  email: "",
  password: "",
  passwordConfirm: "",
  name: "",
  phone: "",
  companyName: "",
  representativeName: "",
  businessNumber: "",
  contactPhone: "",
  contactEmail: "",
};

export default function SellerSignupPage() {
  const [form, setForm] = useState(EMPTY_FORM);
  const [termsAgreed, setTermsAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: e.target.value });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (form.password !== form.passwordConfirm) {
      setMessage("비밀번호와 비밀번호 확인이 다릅니다");
      return;
    }
    if (!termsAgreed) {
      setMessage("판매자 약관에 동의해 주세요");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await api("/api/v1/auth/sellers/signup", {
        method: "POST",
        body: {
          email: form.email.trim(),
          password: form.password,
          passwordConfirm: form.passwordConfirm,
          name: form.name.trim(),
          phone: form.phone,
          companyName: form.companyName.trim(),
          representativeName: form.representativeName.trim(),
          businessNumber: form.businessNumber,
          contactPhone: form.contactPhone,
          contactEmail: form.contactEmail.trim(),
          termsAgreed,
        },
      });
      setDone(true);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "가입 신청에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="card mx-auto max-w-md space-y-4 p-8 text-center">
        <h1 className="text-xl font-semibold">가입 신청이 접수되었습니다</h1>
        <p className="text-sm text-secondary">관리자가 사업체를 확인하고 승인하면 판매자로 로그인할 수 있습니다. 승인 전에는 로그인할 수 없습니다.</p>
        <Link href="/seller/login" className="btn btn-primary">
          로그인 화면으로
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-2 text-3xl font-semibold">판매자 가입 신청</h1>
      <p className="mb-8 text-sm text-secondary">사업체와 대표자 정보를 접수하면 관리자가 확인한 뒤 승인합니다</p>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <fieldset className="space-y-4">
          <legend className="label">대표자 계정</legend>
          <div>
            <label className="label" htmlFor="email">
              로그인 이메일
            </label>
            <input id="email" type="email" className="input" value={form.email} onChange={set("email")} required />
          </div>
          <div>
            <label className="label" htmlFor="password">
              비밀번호 (8~25자, 영문·숫자·특수문자)
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              className="input"
              value={form.password}
              onChange={set("password")}
              required
            />
          </div>
          <div>
            <label className="label" htmlFor="passwordConfirm">
              비밀번호 확인
            </label>
            <input
              id="passwordConfirm"
              type="password"
              autoComplete="new-password"
              className="input"
              value={form.passwordConfirm}
              onChange={set("passwordConfirm")}
              required
            />
          </div>
          <div>
            <label className="label" htmlFor="name">
              이름
            </label>
            <input id="name" className="input" maxLength={50} value={form.name} onChange={set("name")} required />
          </div>
          <div>
            <label className="label" htmlFor="phone">
              휴대폰 (하이픈 없이)
            </label>
            <input id="phone" className="input" inputMode="tel" placeholder="01012345678" value={form.phone} onChange={set("phone")} required />
          </div>
        </fieldset>

        <fieldset className="space-y-4 border-t border-line pt-4">
          <legend className="label">사업체</legend>
          <div>
            <label className="label" htmlFor="companyName">
              상호
            </label>
            <input id="companyName" className="input" maxLength={100} value={form.companyName} onChange={set("companyName")} required />
          </div>
          <div>
            <label className="label" htmlFor="representativeName">
              대표자명 (사업자등록증 기준)
            </label>
            <input
              id="representativeName"
              className="input"
              maxLength={50}
              value={form.representativeName}
              onChange={set("representativeName")}
              required
            />
          </div>
          <div>
            <label className="label" htmlFor="businessNumber">
              사업자등록번호 (하이픈 없이 10자리)
            </label>
            <input
              id="businessNumber"
              className="input"
              inputMode="numeric"
              maxLength={10}
              pattern="[0-9]{10}"
              value={form.businessNumber}
              onChange={(e) => setForm({ ...form, businessNumber: e.target.value.replace(/\D/g, "") })}
              required
            />
          </div>
          <div>
            <label className="label" htmlFor="contactPhone">
              담당 연락처
            </label>
            <input id="contactPhone" className="input" inputMode="tel" value={form.contactPhone} onChange={set("contactPhone")} required />
          </div>
          <div>
            <label className="label" htmlFor="contactEmail">
              담당 이메일
            </label>
            <input id="contactEmail" type="email" className="input" value={form.contactEmail} onChange={set("contactEmail")} required />
          </div>
        </fieldset>

        <label className="flex items-center gap-2 border-t border-line pt-4 text-sm">
          <input type="checkbox" checked={termsAgreed} onChange={(e) => setTermsAgreed(e.target.checked)} />
          판매자 이용 약관에 동의합니다 (필수)
        </label>

        {message && (
          <p role="alert" className="text-sm text-accent-strong">
            {message}
          </p>
        )}
        <button type="submit" className="btn btn-primary w-full" disabled={busy}>
          {busy ? "접수 중" : "가입 신청"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-muted">
        이미 승인받았나요?{" "}
        <Link href="/seller/login" className="text-foreground underline">
          로그인
        </Link>
      </p>
    </div>
  );
}
