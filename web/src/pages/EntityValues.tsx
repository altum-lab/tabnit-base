import { useEffect, useState } from "react";
import { api, type AttributeValuesAsOf } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// 가운데 개체의 속성 값을 조회 시점으로 보인다 (카드 A-19, D7 4절 "속성 값의 시점 조회", D2 AD-420).
// 서버의 getAttributeValuesAsOf (docs/api/openapi.yaml) 의 답을 그대로 보여 준다.
//
// 이 칸은 계산하지 않는다.
// - 어느 값이 그 날짜에 유효한지 가리지 않는다. 값과 차례는 서버가 준 것이다.
// - 조회 시점은 지도가 답한 날짜를 받는다: 지도, 속성 값, 관계 목록이 같은 날짜를 본다.

type View =
  | { kind: "loading" }
  | { kind: "loaded"; answer: AttributeValuesAsOf }
  | { kind: "failed"; message: string };

export function EntityValues({ entityId, asOf }: { entityId: string; asOf: string }) {
  const [view, setView] = useState<View>({ kind: "loading" });
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    // 개체나 날짜가 바뀐 뒤에 늦게 온 답은 버린다.
    let current = true;
    setView({ kind: "loading" });
    void (async () => {
      try {
        const { data, error, response } = await api.GET("/api/v1/attribute-values", { params: { query: { entityId, asOf } } });
        if (current) {
          setView(data ? { kind: "loaded", answer: data } : { kind: "failed", message: messageOf(error, response.status) });
        }
      } catch {
        if (current) {
          setView({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    })();
    return () => {
      current = false;
    };
  }, [entityId, asOf, retry]);

  return (
    <section className="entity-values" aria-labelledby="entity-values-title">
      <h2 id="entity-values-title">속성 값</h2>
      {/* 이 줄에는 대기 상태의 역할(status)을 주지 않는다: 화면의 대기 상태는 관계를 받아 오는 것 하나다. */}
      {view.kind === "loading" && <p data-testid="entity-values-loading">속성 값을 불러오는 중입니다…</p>}
      {view.kind === "failed" && (
        <p role="alert">
          {view.message}{" "}
          <button type="button" onClick={() => setRetry((count) => count + 1)}>
            속성 값 다시 불러오기
          </button>
        </p>
      )}
      {view.kind === "loaded" && (
        <div data-testid="entity-values" data-entity-id={view.answer.entityId} data-as-of={view.answer.asOf}>
          <p>
            <small>
              가운데 개체의 조회 시점 <span data-testid="entity-values-as-of">{view.answer.asOf}</span> 에 유효한 값입니다.
              조회 시점을 옮기면 그 날짜의 값으로 바뀝니다.
            </small>
          </p>
          {view.answer.values.length === 0 ? (
            <p data-testid="entity-values-empty">이 조회 시점에 유효한 속성 값이 없습니다.</p>
          ) : (
            <table>
              <caption>가운데 개체의 속성 값</caption>
              <thead>
                <tr>
                  <th scope="col">속성</th>
                  <th scope="col">값</th>
                  <th scope="col">시작일</th>
                  <th scope="col">종료일</th>
                  <th scope="col">출처 (입력 자료, 판)</th>
                </tr>
              </thead>
              <tbody>
                {view.answer.values.map((value) => (
                  <tr key={`${value.attributeName}|${value.validFrom}`} data-value-attribute={value.attributeName}>
                    <td>
                      {value.attributeLabel}{" "}
                      {value.attributeLabel !== value.attributeName && (
                        <small>
                          <code>{value.attributeName}</code>
                        </small>
                      )}
                    </td>
                    {/* 보이는 글자는 서버가 준 것이다: 참·거짓은 예, 아니오로 온다. */}
                    <td data-testid="value-display" className="changed-value">
                      {value.valueDisplay}
                    </td>
                    <td data-testid="value-from">{value.validFrom}</td>
                    <td data-testid="value-to">{value.validTo ?? "종료일 없음"}</td>
                    <td>
                      {value.provenance.inputSourceLabel}, {value.provenance.editionNo}판
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
