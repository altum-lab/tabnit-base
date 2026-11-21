import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

// 서버 호출은 규격(docs/api/openapi.yaml)에서 만든 타입으로만 한다.
// schema.d.ts 는 `npm run gen:api` 가 만든다. 손으로 고치지 않는다.
export const api = createClient<paths>({ baseUrl: "/" });

export type RelationsAsOf = components["schemas"]["RelationsAsOf"];
export type Relation = components["schemas"]["Relation"];
export type Problem = components["schemas"]["Problem"];
export type PackList = components["schemas"]["PackList"];
export type PackSummary = components["schemas"]["PackSummary"];
export type InputSourceSummary = components["schemas"]["InputSourceSummary"];
export type EditionSubmission = components["schemas"]["EditionSubmission"];
export type EditionChange = components["schemas"]["EditionChange"];
export type ChangeProcessing = components["schemas"]["ChangeProcessing"];
