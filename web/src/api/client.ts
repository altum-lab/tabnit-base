import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

// 서버 호출은 규격(docs/api/openapi.yaml)에서 만든 타입으로만 한다.
// schema.d.ts 는 `npm run gen:api` 가 만든다. 손으로 고치지 않는다.
export const api = createClient<paths>({ baseUrl: "/" });

// 세션이 끝났다고 서버가 답했을 때 화면의 뼈대(App)에 알리는 신호.
export const SESSION_ENDED = "tabnit:session-ended";

const SESSION_PATH = "/api/v1/session";

function cookie(name: string) {
  const found = document.cookie.split("; ").find((pair) => pair.startsWith(`${name}=`));
  return found ? decodeURIComponent(found.slice(name.length + 1)) : null;
}

api.use({
  // 위조 요청 방어: 바꾸는 요청에는 서버가 쿠키로 준 토큰을 헤더에 싣는다 (D7 9절).
  onRequest({ request }) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      const token = cookie("XSRF-TOKEN");
      if (token) {
        request.headers.set("X-XSRF-TOKEN", token);
      }
    }
    return request;
  },
  // 로그인과 내 정보 말고 다른 요청이 401 이면 쓰는 도중에 세션이 끝난 것이다.
  onResponse({ request, response }) {
    if (response.status === 401 && new URL(request.url).pathname !== SESSION_PATH) {
      window.dispatchEvent(new Event(SESSION_ENDED));
    }
    return response;
  },
});

export type Session = components["schemas"]["Session"];
export type EntityRef = components["schemas"]["EntityRef"];

export type RelationsAsOf = components["schemas"]["RelationsAsOf"];
export type Relation = components["schemas"]["Relation"];
export type RelationMap = components["schemas"]["RelationMap"];
export type RelationMapNode = components["schemas"]["RelationMapNode"];
export type Problem = components["schemas"]["Problem"];
export type PackList = components["schemas"]["PackList"];
export type PackSummary = components["schemas"]["PackSummary"];
export type InputSourceSummary = components["schemas"]["InputSourceSummary"];
export type EditionSubmission = components["schemas"]["EditionSubmission"];
export type EditionList = components["schemas"]["EditionList"];
export type EditionChange = components["schemas"]["EditionChange"];
export type ChangeProcessing = components["schemas"]["ChangeProcessing"];
export type UnprocessedChange = components["schemas"]["UnprocessedChange"];
export type ChangeReprocessing = components["schemas"]["ChangeReprocessing"];
export type Action = components["schemas"]["Action"];
export type ActionDetail = components["schemas"]["ActionDetail"];
export type ActionDecision = components["schemas"]["ActionDecision"];
export type ChangeImpactScope = components["schemas"]["ChangeImpactScope"];
export type ImpactTarget = components["schemas"]["ImpactTarget"];
export type ConstraintViolationList = components["schemas"]["ConstraintViolationList"];
export type EntityList = components["schemas"]["EntityList"];
