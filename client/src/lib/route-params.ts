import { useParams, usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";

// Next(Cache Components)는 미리 그려 둔 껍데기에서 경로 값을 %%drp:id:해시%% 같은 자리표시로 채운다.
// 운영에서 useParams() 가 이 값을 그대로 돌려주는 일이 있어서(Vercel 커뮤니티에 같은 보고가 있다),
// 그 값으로 요청하거나 저장소 키를 만들면 화면이 "불러오는 중"에서 멈춘다.
// 그래서 자리표시가 보이면 지금 주소(진짜 값)에서 경로 값을 직접 읽는다.
export const ROUTE_PLACEHOLDER = "%%drp:";

// pattern 은 경로 틀이다. 예: "/bookings/[id]/pay". 라우트 그룹((site) 등)은 주소에 없으므로 쓰지 않는다.
export function resolveRouteParams<T extends Record<string, string>>(params: T, pathname: string, pattern: string): T {
  if (!Object.values(params).some((value) => typeof value === "string" && value.includes(ROUTE_PLACEHOLDER))) {
    return params;
  }
  const names = pattern.split("/");
  const parts = pathname.split("/");
  if (names.length !== parts.length) return params;
  const real: Record<string, string> = {};
  names.forEach((segment, index) => {
    const matched = /^\[(.+)\]$/.exec(segment);
    if (matched) real[matched[1]] = decodeURIComponent(parts[index]);
  });
  return { ...params, ...real };
}

const subscribeNothing = () => () => {};

// useParams 대신 쓴다. 평소에는 같은 값을 돌려주고, 자리표시가 보일 때만 주소에서 읽은 값으로 바꾼다.
export function useRouteParams<T extends Record<string, string>>(pattern: string): T {
  const params = useParams<T>();
  const routed = usePathname();
  // 서버 렌더와 하이드레이션 때는 라우터가 준 경로를 쓰고, 브라우저에서는 실제 주소를 읽는다.
  const pathname = useSyncExternalStore(
    subscribeNothing,
    () => window.location.pathname,
    () => routed,
  );
  return resolveRouteParams(params, pathname, pattern);
}
