import { EntityName } from "./EntityName";
import { useCallback, useContext, useEffect, useRef, useState } from "react";
import { api, type ChangeProcessing, type ChangeReprocessing, type UnprocessedChange } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { SessionContext } from "../session";

// 처리되지 않은 변동(실패, 대기)의 목록과 다시 처리 (D7 5절 "처리되지 않은 변동과 다시 처리", D2 AD-321, AD-345).
// 서버의 listUnprocessedChanges 와 reprocessChanges (docs/api/openapi.yaml) 의 값을 보여 준다.
//
// 이 화면은 계산하지 않는다. 목록은 서버가 준 순서 그대로 그린다.
// 실패의 까닭은 서버가 코드로 준다. 화면은 코드마다 정해 둔 안내문을 보여 준다. 모르는 코드는 코드를 그대로 적는다.

type List =
  | { kind: "loading" }
  | { kind: "loaded"; changes: UnprocessedChange[] }
  | { kind: "failed"; message: string };

type Reprocess =
  | { kind: "idle" }
  | { kind: "running" }
  | { kind: "done"; result: ChangeReprocessing }
  | { kind: "failed"; message: string };

const KIND_LABEL: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

const STAGE_LABEL: Record<string, string> = { IMPACT: "영향 범위 계산", RULES: "규칙 실행" };

// 변동의 처리가 실패한 이유의 코드마다의 안내문. 무엇을 하면 되는지까지 적는다.
const FAILURE_GUIDE: Record<string, string> = {
  RULE_FAILED: "평가에 실패한 규칙이 있습니다. 아래의 규칙과 까닭을 보고 Pack 이나 자료를 고친 뒤 다시 처리하세요.",
  STORAGE_FAILURE: "저장소에서 읽거나 쓰지 못했습니다. 저장소의 상태를 확인한 뒤 다시 처리하세요.",
  UNKNOWN_IMPACT_DIRECTION:
    "자료에 Pack 의 정의에 없는 관계 유형의 관계가 있습니다. Pack 의 온톨로지를 확인해 다시 적재한 뒤 다시 처리하세요.",
  EDITION_NOT_FOUND:
    "이 변동이 나온 판을 찾지 못했습니다. 저장소가 어긋나 있습니다. 다시 처리해도 같으면 서버의 기록과 저장소의 상태를 확인하세요.",
  IMPACT_REJECTED: "영향 범위를 계산하지 못했습니다. 서버의 기록에서 까닭을 확인한 뒤 다시 처리하세요.",
  RULES_REJECTED: "규칙을 실행하지 못했습니다. 서버의 기록에서 까닭을 확인한 뒤 다시 처리하세요.",
};

// 규칙이 평가에 실패한 까닭의 코드마다의 안내문.
const RULE_FAILURE_GUIDE: Record<string, string> = {
  VALUE_FORMAT_MISMATCH: "저장된 값이 속성의 형식과 맞지 않습니다. 그 개체의 값을 바로잡은 판을 올리세요.",
  DUE_DATE_OUT_OF_RANGE: "조치의 기한을 날짜로 담을 수 없습니다. 규칙의 기한 일수를 고쳐 Pack 을 다시 적재하세요.",
  INPUT_SOURCE_UNKNOWN:
    "규칙이 입력 자료를 가리는데 이 변동이 어느 입력 자료에서 나왔는지 알 수 없습니다. 다시 처리해 보고, 같으면 서버의 기록에서 까닭을 확인하세요.",
  CHANGED_ITEMS_UNKNOWN:
    "규칙이 바뀐 것을 가리는데 이 변동에는 무엇이 바뀌었는지가 남아 있지 않습니다 (옛 변동입니다). 그 입력 자료의 새 판을 올리세요.",
  UNKNOWN_ATTRIBUTE: "규칙이 보는 속성이 Pack 의 정의에 없습니다. Pack 을 고쳐 다시 적재하세요.",
  RULE_VALUE_FORMAT_MISMATCH: "규칙에 적힌 값이 속성의 형식과 맞지 않습니다. Pack 을 고쳐 다시 적재하세요.",
  INVALID_RULE: "규칙의 정의가 받는 모양이 아닙니다. Pack 을 고쳐 다시 적재하세요.",
};

function guide(table: Record<string, string>, code: string | null): string {
  if (code == null) {
    return "";
  }
  return table[code] ?? `까닭의 코드: ${code}. 서버의 기록에서 까닭을 확인하세요.`;
}

export function UnprocessedChanges({ packId, refreshKey }: { packId: string; refreshKey: number }) {
  const session = useContext(SessionContext);
  const [list, setList] = useState<List>({ kind: "loading" });
  const [reprocess, setReprocess] = useState<Reprocess>({ kind: "idle" });
  const latestRequest = useRef(0);

  const load = useCallback(async () => {
    // 가장 나중에 보낸 요청의 답만 쓴다.
    const request = ++latestRequest.current;
    setList({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/unprocessed-changes", {
        params: { query: { packId } },
      });
      if (request !== latestRequest.current) {
        return;
      }
      setList(
        data ? { kind: "loaded", changes: data.changes } : { kind: "failed", message: messageOf(error, response.status) },
      );
    } catch {
      if (request === latestRequest.current) {
        setList({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }, [packId]);

  useEffect(() => {
    // 다른 Pack 의 다시 처리 결과가 이 Pack 의 것처럼 보이지 않게 지운다.
    setReprocess({ kind: "idle" });
    void load();
  }, [load, refreshKey]);

  async function run() {
    setReprocess({ kind: "running" });
    try {
      const { data, error, response } = await api.POST("/api/v1/change-reprocessings", { body: { packId } });
      setReprocess(data ? { kind: "done", result: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      setReprocess({ kind: "failed", message: CONNECTION_FAILED });
    }
    // 되었든 안 되었든 지금의 목록을 다시 받는다.
    void load();
  }

  return (
    <section aria-labelledby="unprocessed-title" data-testid="unprocessed">
      <h2 id="unprocessed-title">처리되지 않은 변동</h2>
      <p>바뀐 항목 가운데 영향 범위 계산이나 규칙 실행에 실패했거나 아직 처리하지 못한 것입니다. 조치가 만들어지지 않았습니다.</p>

      {list.kind === "loading" && <p role="status">처리되지 않은 변동을 불러오는 중입니다…</p>}

      {list.kind === "failed" && (
        <p role="alert">
          {list.message}{" "}
          <button type="button" onClick={() => void load()}>
            다시 불러오기
          </button>
        </p>
      )}

      {list.kind === "loaded" && list.changes.length === 0 && (
        <p role="status" data-testid="unprocessed-empty">
          처리되지 않은 변동이 없습니다.
        </p>
      )}

      {list.kind === "loaded" && list.changes.length > 0 && (
        <>
          <table>
            <caption>처리되지 않은 변동</caption>
            <thead>
              <tr>
                <th scope="col">판</th>
                <th scope="col">항목의 키</th>
                <th scope="col">변동</th>
                <th scope="col">주 개체</th>
                <th scope="col">상태와 까닭</th>
              </tr>
            </thead>
            <tbody>
              {list.changes.map((change) => (
                <tr key={change.changeEventId} data-change-event-id={change.changeEventId}>
                  <td>
                    {change.inputSourceLabel} {change.editionNo}판, 적용일 {change.effectiveDate}
                  </td>
                  <td>{change.itemKey}</td>
                  <td>{KIND_LABEL[change.kind] ?? change.kind}</td>
                  <td>
                    <EntityName entity={change.primaryEntity} />
                  </td>
                  <td>
                    <Reason processing={change.processing} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {session?.role === "ADMIN" ? (
            <p>
              <button type="button" onClick={() => void run()} disabled={reprocess.kind === "running"}>
                다시 처리
              </button>{" "}
              이 Pack 의 처리되지 않은 변동을 모두, 지금의 자료와 지금의 규칙으로 다시 처리합니다.
            </p>
          ) : (
            <p data-testid="reprocess-admin-only">Pack 이나 자료를 고친 뒤 관리자가 다시 처리할 수 있습니다.</p>
          )}
        </>
      )}

      {reprocess.kind === "running" && <p role="status">다시 처리하는 중입니다…</p>}

      {reprocess.kind === "failed" && <p role="alert">{reprocess.message}</p>}

      {reprocess.kind === "done" && (
        <p role="status" data-testid="reprocess-result">
          {reprocessSummary(reprocess.result)}
        </p>
      )}
    </section>
  );
}

function Reason({ processing }: { processing: ChangeProcessing }) {
  if (processing.status !== "FAILED") {
    return <>대기 (아직 처리하지 못했습니다)</>;
  }
  const stage = processing.failedStage ? (STAGE_LABEL[processing.failedStage] ?? processing.failedStage) : null;
  return (
    <>
      <p>
        실패{stage ? ` (${stage})` : ""}. {guide(FAILURE_GUIDE, processing.failureCode)}
      </p>
      {processing.failures.length > 0 && (
        <ul>
          {processing.failures.map((failure, index) => (
            // 까닭에는 식별자가 없다. 서버가 준 차례가 곧 자리다.
            <li key={index} data-rule-no={failure.ruleNo} data-failure-code={failure.code}>
              규칙 {failure.ruleNo}: {guide(RULE_FAILURE_GUIDE, failure.code)}
              {failureSubject(failure)}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

// 걸린 개체, 속성, 값. 서버가 준 것만 적는다.
function failureSubject(failure: ChangeProcessing["failures"][number]): string {
  const parts = [
    failure.entityId != null ? `개체 ${failure.entityId}` : null,
    failure.attributeName != null ? `속성 ${failure.attributeName}` : null,
    failure.value != null ? `값 ${failure.value}` : null,
  ].filter((part) => part != null);
  return parts.length > 0 ? ` (${parts.join(", ")})` : "";
}

// 수는 서버가 준 값이다. 화면은 세지 않는다.
function reprocessSummary(result: ChangeReprocessing): string {
  if (result.changes.length === 0) {
    return "다시 처리할 변동이 없었습니다.";
  }
  return `다시 처리했습니다. 처리됨 ${result.processedCount}건, 다시 실패 ${result.failedCount}건.`;
}
