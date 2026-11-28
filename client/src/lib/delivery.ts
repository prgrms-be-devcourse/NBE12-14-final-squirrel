import { dateTimeText } from "@/lib/format";
import type { DeliveryTarget, ShipmentItem } from "@/lib/types";

// 발송 처리(api-spec SLR-40~42)의 택배사 목록과 CSV 읽기·쓰기. 서버는 JSON 만 받아서 파일은 여기서 만들고 읽는다.

// 서버 설정(application.yaml 의 app.booking.delivery.carriers)과 같아야 한다. 목록을 주는 API 가 없다.
export const CARRIERS = [
  { code: "CJ", name: "CJ대한통운" },
  { code: "EPOST", name: "우체국택배" },
  { code: "HANJIN", name: "한진택배" },
  { code: "LOTTE", name: "롯데택배" },
  { code: "LOGEN", name: "로젠택배" },
];

export const carrierName = (code: string) => CARRIERS.find((c) => c.code === code)?.name ?? code;

// =, +, -, @ 로 시작하는 칸은 엑셀이 수식으로 실행한다. 받는 사람·주소는 예매자가 적는 값이라 앞에 ' 를 붙여 글자로 만든다.
function cell(value: string | number): string {
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

// 하이픈이 없으면 엑셀이 숫자로 읽어 맨 앞 0 을 지운다.
const phoneText = (phone: string) => phone.replace(/^(0\d{1,2})(\d{3,4})(\d{4})$/, "$1-$2-$3");

export function targetsToCsv(targets: DeliveryTarget[]): string {
  const rows: (string | number)[][] = [
    ["예매번호", "회차", "받는 사람", "연락처", "주소", "매수", "택배사", "송장번호"],
    ...targets.map((t) => [
      t.bookingNo,
      dateTimeText(t.scheduleStartAt),
      t.recipientName,
      phoneText(t.recipientPhone),
      t.recipientAddress,
      t.seatCount,
      t.carrier ? carrierName(t.carrier) : "",
      t.trackingNo ?? "",
    ]),
  ];
  // BOM 이 없으면 엑셀에서 한글이 깨진다.
  return `﻿${rows.map((row) => row.map(cell).join(",")).join("\r\n")}\r\n`;
}

export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    if (quoted) {
      if (char !== '"') field += char;
      else if (text[i + 1] === '"') {
        field += '"';
        i += 1;
      } else quoted = false;
    } else if (char === '"' && field === "") {
      quoted = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[i + 1] === "\n") i += 1;
      rows.push([...row, field]);
      row = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field !== "" || row.length > 0) rows.push([...row, field]);
  return rows;
}

export type ShipmentFile = {
  items: ShipmentItem[];
  // items 와 같은 위치의 파일 줄 번호(제목 줄이 1)
  lines: number[];
  // 송장번호가 비어 있어 보내지 않는 줄 수
  blank: number;
  errors: string[];
};

// 올린 파일에서 보낼 송장을 고른다. 형식·중복·건수는 서버가 검사하고, 여기서는 서버가 알 수 없는 것만 본다.
export function toShipments(text: string, defaultCarrier: string): ShipmentFile {
  const result: ShipmentFile = { items: [], lines: [], blank: 0, errors: [] };
  const [header = [], ...rows] = parseCsv(text);
  const column = (name: string) => header.findIndex((h) => h.trim() === name);
  const bookingNoAt = column("예매번호");
  const trackingNoAt = column("송장번호");
  const carrierAt = column("택배사");
  if (bookingNoAt < 0 || trackingNoAt < 0) {
    result.errors.push("첫 줄에 '예매번호'와 '송장번호' 칸이 있는 CSV 파일이어야 합니다. 엑셀에서는 'CSV UTF-8'로 저장해 주세요");
    return result;
  }
  rows.forEach((row, index) => {
    const line = index + 2;
    const bookingNo = row[bookingNoAt]?.trim() ?? "";
    const trackingNo = row[trackingNoAt]?.trim() ?? "";
    if (!trackingNo) {
      if (bookingNo) result.blank += 1;
      return;
    }
    const written = row[carrierAt]?.trim() ?? "";
    const carrier = written
      ? CARRIERS.find((c) => c.code === written.toUpperCase() || c.name === written.replaceAll(" ", ""))?.code
      : defaultCarrier;
    // 엑셀은 긴 숫자를 1.23457E+11 로 바꿔 저장하고, 원래 번호는 파일에 남지 않는다.
    if (/^\d(\.\d+)?E\+\d+$/i.test(trackingNo)) {
      result.errors.push(`${line}번째 줄: 송장번호가 ${trackingNo} 로 바뀌었습니다. 송장번호 칸을 '텍스트' 서식으로 바꿔 다시 입력해 주세요`);
    } else if (!bookingNo) {
      result.errors.push(`${line}번째 줄: 예매번호가 비어 있습니다`);
    } else if (!carrier) {
      result.errors.push(`${line}번째 줄: ${written ? `모르는 택배사 '${written}'` : "택배사가 비어 있습니다"}`);
    } else {
      result.items.push({ bookingNo, carrier, trackingNo });
      result.lines.push(line);
    }
  });
  if (result.errors.length === 0 && result.items.length === 0) result.errors.push("송장번호가 적힌 줄이 없습니다");
  return result;
}

const FIELD_LABEL: Record<string, string> = { bookingNo: " 예매번호", carrier: " 택배사", trackingNo: " 송장번호" };

// 서버의 400 메시지에 있는 items[3].trackingNo 를 사용자가 보는 파일의 줄 번호로 바꾼다.
export function toFileMessage(message: string, lines: number[]): string {
  return message.replace(
    /items\[(\d+)\](?:\.(\w+))?/g,
    (all, row: string, field: string) =>
      lines[Number(row)] ? `${lines[Number(row)]}번째 줄${FIELD_LABEL[field] ?? ""}` : all,
  );
}

// 한국어 엑셀의 기본 'CSV' 저장은 UTF-8 이 아니라 EUC-KR 이다.
export async function readCsvFile(file: File): Promise<string> {
  const bytes = await file.arrayBuffer();
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder("euc-kr").decode(bytes);
  }
}
