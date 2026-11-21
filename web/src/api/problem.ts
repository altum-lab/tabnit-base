import type { Problem } from "./client";

// 서버의 오류를 화면에 보일 문구로 바꾼다. 모든 화면이 함께 쓴다.
//
// 서버의 오류는 공통 오류 형식으로 온다. 사용자에게 보일 문구는 detail 에 들어 있다.
// 공통 오류 형식이 아닌 응답의 내용은 화면에 그대로 보이지 않는다.

export const CONNECTION_FAILED = "서버에 연결하지 못했습니다. 서버가 켜져 있는지 확인한 뒤 다시 시도하세요.";
export const UNEXPECTED = "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도하세요.";

export function messageOf(error: unknown, status: number): string {
  if (isProblem(error)) {
    return error.detail;
  }
  // 서버 앞단(개발 서버의 넘겨주기, 역방향 프록시)이 서버에 닿지 못했을 때의 상태다.
  if (status === 502 || status === 503 || status === 504) {
    return CONNECTION_FAILED;
  }
  return UNEXPECTED;
}

export function isProblem(error: unknown): error is Problem {
  return (
    typeof error === "object" &&
    error !== null &&
    typeof (error as { detail?: unknown }).detail === "string" &&
    typeof (error as { code?: unknown }).code === "string"
  );
}
