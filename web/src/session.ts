import { createContext } from "react";
import type { Session } from "./api/client";

// 로그인한 사람. 화면이 역할에 따라 단추를 보이거나 감추는 데 쓴다.
// 감추는 것은 편의일 뿐이다: 권한은 서버가 확인한다 (D7 2절).
export const SessionContext = createContext<Session | null>(null);
