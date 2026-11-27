import { useEffect, useRef, useState } from "react";
import { api, type EditionComparison, type EditionList } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";

// 같은 입력 자료의 두 판을 골라 견준다 (C-16, D7 5절 "두 판의 비교", D2 AD-418).
// 서버의 compareEditions (docs/api/openapi.yaml) 의 답을 그대로 보여 준다.
//
// 이 화면은 견주지 않는다. 바뀐 항목, 종류, 값의 앞뒤, 차례는 서버가 준 그대로다.
// 고를 수 있는 판은 지난 판의 목록에 지금 보이는 판이다. 뒤의 판은 앞의 판과 같은 입력 자료의 것만 고른다.

type Edition = EditionList["editions"][number];

type Compared =
  | { kind: "none" }
  | { kind: "loading" }
  | { kind: "loaded"; result: EditionComparison }
  | { kind: "failed"; message: string };

const KIND_LABEL: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

function editionLabel(edition: Edition): string {
  // 날짜는 그 판의 적용일이다. 낱말을 적지 않는다: 고르는 칸의 이름에 선택지의 글자가 들어가, 다른 칸의 이름과 겹친다.
  return `${edition.inputSourceLabel} ${edition.editionNo}판 (${edition.effectiveDate})`;
}

export function EditionComparer({ packId, editions }: { packId: string; editions: Edition[] }) {
  const [fromId, setFromId] = useState("");
  const [toId, setToId] = useState("");
  const [compared, setCompared] = useState<Compared>({ kind: "none" });
  const latest = useRef(0);

  // Pack 을 바꾸면 고른 것과 결과를 비운다: 다른 Pack 의 결과를 이 Pack 의 것처럼 보이지 않는다.
  useEffect(() => {
    latest.current++;
    setFromId("");
    setToId("");
    setCompared({ kind: "none" });
  }, [packId]);

  // 목록이 바뀌어 (다른 쪽으로 넘겼거나 새로 받았거나) 고른 판이 보이지 않게 되면 고른 것과 결과를 비운다:
  // 칸에는 "고르세요"가 보이는데 보이지 않는 판을 견주게 두지 않는다.
  useEffect(() => {
    const listed = new Set(editions.map((edition) => edition.editionId));
    if (fromId && !listed.has(fromId)) {
      latest.current++;
      setFromId("");
      setToId("");
      setCompared({ kind: "none" });
    } else if (toId && !listed.has(toId)) {
      latest.current++;
      setToId("");
      setCompared({ kind: "none" });
    }
  }, [editions, fromId, toId]);

  const from = editions.find((edition) => edition.editionId === fromId);
  const to = editions.find((edition) => edition.editionId === toId);
  // 뒤의 판으로 고를 수 있는 것: 앞의 판과 같은 입력 자료의 판. 차례는 목록의 차례 그대로다.
  const candidates = from ? editions.filter((edition) => edition.inputSourceId === from.inputSourceId) : [];

  // 고른 것을 바꾸면 앞의 결과를 비우고, 오고 있던 답도 버린다: 앞의 표가 새로 고른 것의 결과처럼 남지 않는다.
  function forget() {
    latest.current++;
    setCompared({ kind: "none" });
  }

  function chooseFrom(next: string) {
    forget();
    setFromId(next);
    const chosen = editions.find((edition) => edition.editionId === next);
    const kept = editions.find((edition) => edition.editionId === toId);
    if (!chosen || !kept || kept.inputSourceId !== chosen.inputSourceId) {
      setToId("");
    }
  }

  async function compare() {
    const request = ++latest.current;
    setCompared({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/edition-comparison", {
        params: { query: { fromEditionId: fromId, toEditionId: toId } },
      });
      if (request !== latest.current) {
        return;
      }
      setCompared(data ? { kind: "loaded", result: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (request === latest.current) {
        setCompared({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  return (
    <section aria-labelledby="edition-comparer-title" className="edition-comparer">
      <h3 id="edition-comparer-title">두 판의 비교</h3>
      <p>
        같은 입력 자료의 두 판을 골라 추가, 변경, 삭제된 항목과 값의 앞뒤를 봅니다. 위의 목록에 보이는 판에서 고릅니다. 견준
        것은 저장되지 않고, 조치를 만들지 않습니다.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void compare();
        }}
      >
        <label>
          앞의 판{" "}
          <select value={fromId} onChange={(event) => chooseFrom(event.target.value)}>
            <option value="">고르세요</option>
            {editions.map((edition) => (
              <option key={edition.editionId} value={edition.editionId}>
                {editionLabel(edition)}
              </option>
            ))}
          </select>
        </label>{" "}
        <label>
          뒤의 판{" "}
          <select
            value={toId}
            onChange={(event) => {
              forget();
              setToId(event.target.value);
            }}
            disabled={!from}
          >
            <option value="">{from ? "고르세요" : "기준이 될 판을 먼저 고르세요"}</option>
            {candidates.map((edition) => (
              <option key={edition.editionId} value={edition.editionId}>
                {editionLabel(edition)}
              </option>
            ))}
          </select>
        </label>{" "}
        {/* 두 판이 모두 지금 보이는 목록에 있을 때만 견준다. */}
        <button type="submit" disabled={!from || !to || compared.kind === "loading"}>
          두 판 견주기
        </button>
      </form>

      {compared.kind === "loading" && <p role="status">두 판을 견주는 중입니다…</p>}

      {compared.kind === "failed" && <p role="alert">{compared.message}</p>}

      {compared.kind === "loaded" && (
        <div data-testid="comparison-result" data-from-edition-id={compared.result.from.editionId} data-to-edition-id={compared.result.to.editionId}>
          <p role="status">
            {compared.result.from.inputSourceLabel} {compared.result.from.editionNo}판에서 {compared.result.to.editionNo}판으로:{" "}
            {compared.result.changes.length === 0 ? "바뀐 항목이 없습니다." : `바뀐 항목 ${compared.result.changes.length}건.`}
          </p>
          {compared.result.changes.length > 0 && (
            <table>
              <caption>두 판에서 바뀐 항목</caption>
              <thead>
                <tr>
                  <th scope="col">항목</th>
                  <th scope="col">종류</th>
                  <th scope="col">주 개체</th>
                  <th scope="col">바뀐 것 (값은 앞의 판 → 뒤의 판)</th>
                </tr>
              </thead>
              <tbody>
                {compared.result.changes.map((change) => (
                  <tr key={change.itemKey} data-compared-item-key={change.itemKey}>
                    <td>{change.itemKey}</td>
                    <td data-testid="compared-kind">{KIND_LABEL[change.kind] ?? change.kind}</td>
                    <td>
                      <EntityName entity={change.primaryEntity} />
                    </td>
                    <td>
                      <ComparedValues values={change.changedValues} relationsChanged={change.changedRelationTypes.length > 0} />
                      {/* 관계가 바뀐 것도 서버가 준 그대로 보인다: 값은 그대로이고 관계만 달라진 항목이 있다. */}
                      {change.changedRelationTypes.length > 0 && (
                        <div data-testid="compared-relation-types">바뀐 관계 유형: {change.changedRelationTypes.join(", ")}</div>
                      )}
                      {change.targetEntities.length > 0 && (
                        <div data-testid="compared-targets">
                          변동 대상:{" "}
                          {change.targetEntities.map((entity, index) => (
                            <span key={entity.entityId} data-entity-id={entity.entityId}>
                              {index > 0 && ", "}
                              <EntityName entity={entity} />
                            </span>
                          ))}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}

// 값과 차례는 서버가 준 그대로다. 보이는 글자도 서버가 준 것이다 (참·거짓은 예, 아니오로 온다).
function ComparedValues({
  values,
  relationsChanged,
}: {
  values: EditionComparison["changes"][number]["changedValues"];
  // 관계가 바뀐 항목인가. 그러면 값이 그대로여도 바뀐 것이 있다: "바뀌지 않았다"고만 적지 않는다.
  relationsChanged: boolean;
}) {
  if (values === null) {
    // 빈 목록과 다르다: 바뀐 값이 없는 것이 아니라 서버가 값을 모른다.
    return <small>값을 알 수 없습니다. 바뀐 값을 남기기 전에 들어간 판과 견줬습니다.</small>;
  }
  if (values.length === 0) {
    return relationsChanged ? null : <small>속성 값은 바뀌지 않았습니다.</small>;
  }
  return (
    <ul>
      {values.map((value, index) => (
        <li key={`${value.entityId}|${value.attributeName}|${index}`} data-attribute-name={value.attributeName}>
          {value.attributeLabel}{" "}
          <small>({value.entity.displayName ?? value.entity.entityKey})</small>: <Value value={value.beforeDisplay} testId="compared-before" /> →{" "}
          <Value value={value.afterDisplay} testId="compared-after" />
          {/* 앞뒤의 값이 같은 것: 서버는 유효기간만 달라진 것도 변경으로 준다 (D2 AD-418). 값이 같아 보이는 까닭을 적는다.
              왜 달라졌는지(사이의 판에서 바뀌었다가 되돌아왔는지 따위)는 서버가 주지 않으므로 적지 않는다. */}
          {value.before !== null && value.before === value.after && (
            <small data-testid="same-value-note"> 값은 같고 유효기간이 다릅니다.</small>
          )}
        </li>
      ))}
    </ul>
  );
}

function Value({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) {
    return <small data-testid={testId}>(없음)</small>;
  }
  return (
    <span data-testid={testId} className="changed-value">
      {value}
    </span>
  );
}
