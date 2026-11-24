"use client";

import { ImagePlus } from "lucide-react";
import { useRef, useState } from "react";
import { ApiError, type ImagePurpose, uploadImage } from "@/lib/api";

// 사진 한 장을 골라 올리고, 서버가 준 주소를 onChange 로 돌려준다.
// 올리기만 하고 저장하지 않으면 사진은 어디에도 연결되지 않으므로, 주소는 폼을 저장할 때 같이 보낸다.
export function ImageUpload({
  value,
  purpose,
  label,
  onChange,
}: {
  value: string;
  purpose: ImagePurpose;
  label: string;
  onChange: (url: string) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function pick(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setMessage(null);
    try {
      onChange(await uploadImage(file, purpose));
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "사진을 올리지 못했습니다");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  return (
    <div>
      <div className="flex items-center gap-4">
        {value ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={value} alt={label} className="h-28 w-[84px] rounded-xl border border-line object-cover" />
        ) : (
          <div className="flex h-28 w-[84px] items-center justify-center rounded-xl border border-dashed border-line text-muted">
            <ImagePlus size={20} strokeWidth={1.5} />
          </div>
        )}
        <div className="space-y-2">
          <button type="button" className="btn" disabled={busy} onClick={() => input.current?.click()}>
            {busy ? "올리는 중" : value ? `${label} 바꾸기` : `${label} 올리기`}
          </button>
          <p className="text-xs text-muted">jpg, png, webp · 4MB 이하</p>
        </div>
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="sr-only"
          aria-label={label}
          onChange={(e) => void pick(e.target.files?.[0])}
        />
      </div>
      {message && (
        <p role="alert" className="mt-2 text-sm text-accent-strong">
          {message}
        </p>
      )}
    </div>
  );
}
