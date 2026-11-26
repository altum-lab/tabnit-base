import { EntityName } from "./EntityName";
import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, type ChangeProcessing, type EditionChange, type EditionSubmission, type PackSummary } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { ConstraintViolations } from "./ConstraintViolations";
import { PastEditions } from "./PastEditions";
import { UnprocessedChanges } from "./UnprocessedChanges";

// 변동 감지 화면. 파일 하나를 입력 자료의 새 판으로 올리고, 바뀐 항목과 그 처리 결과를 본다.
// 서버의 listPacks 와 submitEdition (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 무엇이 바뀌었는지, 조치가 몇 건인지는 서버가 정한다. 화면은 다시 세거나 정렬하지 않는다.
// - 목록은 서버가 준 순서 그대로 그린다.
// - 적용일을 정하지 않는다. 사용자가 적는다.

type Packs =
  | { kind: "loading" }
  | { kind: "loaded"; packs: PackSummary[] }
  | { kind: "failed"; message: string };

type Upload =
  | { kind: "idle" }
  | { kind: "uploading" }
  | { kind: "done"; result: EditionSubmission }
  | { kind: "failed"; message: string };

const KIND_LABEL: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

export function ChangesPage() {
  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  // 고른 Pack 은 주소에도 둔다: 지난 판을 고른 채로 다른 화면에 다녀와도 같은 Pack 이 골라져 있다 (D-16).
  const [params, setParams] = useSearchParams();
  const [packId, setPackId] = useState(params.get("packId") ?? "");
  // 뒤로 가기로 주소의 Pack 이 바뀌면 고른 Pack 도 따라간다: 주소의 판과 고른 Pack 이 어긋나지 않게 한다.
  const packInAddress = params.get("packId");
  useEffect(() => {
    if (packInAddress && packInAddress !== packId) {
      setPackId(packInAddress);
      setInputSourceId("");
      setUpload({ kind: "idle" });
    }
    // 주소가 바뀔 때만 본다. 고르는 쪽은 주소를 함께 바꾼다.
  }, [packInAddress]);
  const [inputSourceId, setInputSourceId] = useState("");
  const [effectiveDate, setEffectiveDate] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [upload, setUpload] = useState<Upload>({ kind: "idle" });
  // 목록을 받지 못했을 때의 문구. 이미 받은 목록이 있으면 화면의 "지금의 판"이 옛 값일 수 있다고 알리는 데 쓴다.
  const [refreshFailed, setRefreshFailed] = useState<string | null>(null);
  const latestRequest = useRef(0);
  // 판을 올린 횟수. 처리되지 않은 변동의 목록을 다시 받게 하는 데만 쓴다.
  const [uploadCount, setUploadCount] = useState(0);

  // 목록을 받아 온다. 이미 받은 목록이 있을 때 다시 받다가 실패하면 그 목록을 그대로 두고 알리기만 한다:
  // 폼과 방금 올린 결과가 사라지지 않게 한다.
  const loadPacks = useCallback(async () => {
    // 가장 나중에 보낸 요청의 답만 쓴다. 늦게 온 앞 요청의 답이 새 목록을 옛 목록으로 덮지 않게 한다.
    const request = ++latestRequest.current;
    let failure: string;
    try {
      const { data, error, response } = await api.GET("/api/v1/packs");
      if (request !== latestRequest.current) {
        return;
      }
      if (data) {
        setPacks({ kind: "loaded", packs: data.packs });
        setRefreshFailed(null);
        return;
      }
      failure = messageOf(error, response.status);
    } catch {
      if (request !== latestRequest.current) {
        return;
      }
      failure = CONNECTION_FAILED;
    }
    setPacks((current) => (current.kind === "loaded" ? current : { kind: "failed", message: failure }));
    setRefreshFailed(failure);
  }, []);

  useEffect(() => {
    void loadPacks();
  }, [loadPacks]);

  const pack = packs.kind === "loaded" ? packs.packs.find((candidate) => candidate.packId === packId) : undefined;
  const inputSource = pack?.inputSources.find((candidate) => candidate.inputSourceId === inputSourceId);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) {
      return;
    }
    setUpload({ kind: "uploading" });
    try {
      const { data, error, response } = await api.POST("/api/v1/editions", {
        // 규격은 파일을 글자로 적는다 (형식: binary). 실제로 보내는 것은 아래에서 만드는 폼이다.
        body: { packId, inputSourceId, effectiveDate, file: file.name },
        bodySerializer() {
          const form = new FormData();
          form.append("packId", packId);
          form.append("inputSourceId", inputSourceId);
          form.append("effectiveDate", effectiveDate);
          form.append("file", file);
          return form;
        },
      });
      if (data) {
        setUpload({ kind: "done", result: data });
        setUploadCount((count) => count + 1);
        // 입력 자료의 가장 새 판이 바뀌었을 수 있다. 다시 받아 온다.
        void loadPacks();
      } else {
        setUpload({ kind: "failed", message: messageOf(error, response.status) });
      }
    } catch {
      setUpload({ kind: "failed", message: CONNECTION_FAILED });
    }
  }

  return (
    <section aria-labelledby="changes-title">
      <h1 id="changes-title">변동 감지</h1>
      <p>파일을 입력 자료의 새 판으로 올리면, 앞 판과 견주어 바뀐 항목을 찾고 조치까지 만듭니다.</p>

      {packs.kind === "loading" && <p role="status">Pack 의 목록을 불러오는 중입니다…</p>}

      {packs.kind === "failed" && (
        <p role="alert">
          {packs.message}{" "}
          <button type="button" onClick={() => void loadPacks()}>
            다시 불러오기
          </button>
        </p>
      )}

      {packs.kind === "loaded" && packs.packs.length === 0 && (
        <p role="status">적재된 Pack 이 없습니다. Pack 을 먼저 적재한 뒤 다시 여세요.</p>
      )}

      {packs.kind === "loaded" && packs.packs.length > 0 && (
        <form onSubmit={submit}>
          <p>
            <label htmlFor="pack">Pack</label>{" "}
            <select
              id="pack"
              value={packId}
              onChange={(e) => {
                setPackId(e.target.value);
                // 주소의 Pack 을 맞추고, 앞에 고른 Pack 의 지난 판은 주소에서 지운다.
                setParams(e.target.value ? { packId: e.target.value } : {});
                setInputSourceId("");
                // 앞에 고른 것의 결과가 새로 고른 것의 결과처럼 보이지 않게 지운다.
                setUpload({ kind: "idle" });
              }}
              // 올리는 동안에는 바꾸지 못한다. 답이 오면 그 결과가 새로 고른 것의 결과처럼 보이게 된다.
              disabled={upload.kind === "uploading"}
              required
            >
              <option value="">고르세요</option>
              {packs.packs.map((candidate) => (
                <option key={candidate.packId} value={candidate.packId}>
                  {candidate.label} ({candidate.packId})
                </option>
              ))}
            </select>
          </p>
          <p>
            <label htmlFor="input-source">입력 자료</label>{" "}
            <select
              id="input-source"
              value={inputSourceId}
              onChange={(e) => {
                setInputSourceId(e.target.value);
                setUpload({ kind: "idle" });
              }}
              disabled={!pack || upload.kind === "uploading"}
              required
            >
              <option value="">고르세요</option>
              {pack?.inputSources.map((candidate) => (
                <option key={candidate.inputSourceId} value={candidate.inputSourceId}>
                  {/* 이름표와 식별자를 함께 적는다. 이름표가 없으면 서버가 식별자를 이름표로 준다. */}
                  {candidate.inputSourceLabel === candidate.inputSourceId
                    ? `${candidate.inputSourceId} (${candidate.fileFormat})`
                    : `${candidate.inputSourceLabel} (${candidate.inputSourceId}, ${candidate.fileFormat})`}
                </option>
              ))}
            </select>
          </p>
          {inputSource && (
            <p data-testid="latest-edition">
              {inputSource.latestEdition ? (
                <>
                  지금의 판: {inputSource.latestEdition.editionNo}판, 적용일{" "}
                  <time data-testid="latest-effective-date">{inputSource.latestEdition.effectiveDate}</time>. 새 판의
                  적용일은 이 날짜보다 늦어야 합니다.
                </>
              ) : (
                <>아직 판이 없습니다. 올리는 파일이 첫 판이 됩니다.</>
              )}
            </p>
          )}
          <p>
            <label htmlFor="effective-date">적용일</label>{" "}
            <input
              id="effective-date"
              type="date"
              // 서버가 받는 날짜의 범위다 (D7 2절). 범위를 주지 않으면 브라우저가 연도를 여섯 자리까지 받는다.
              min="0001-01-01"
              max="9999-12-31"
              value={effectiveDate}
              onChange={(e) => setEffectiveDate(e.target.value)}
              required
            />{" "}
            <small>이 날부터 새 판의 내용이 유효합니다.</small>
          </p>
          <p>
            <label htmlFor="file">파일</label>{" "}
            <input id="file" type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required />
          </p>
          <button type="submit" disabled={upload.kind === "uploading"}>
            올리기
          </button>
        </form>
      )}

      {packs.kind === "loaded" && refreshFailed && (
        <p role="alert">
          목록을 다시 받지 못해 위의 "지금의 판"이 옛 값일 수 있습니다. {refreshFailed}{" "}
          <button type="button" onClick={() => void loadPacks()}>
            다시 불러오기
          </button>
        </p>
      )}

      {upload.kind === "uploading" && <p role="status">올리는 중입니다. 바뀐 항목을 찾고 조치를 만듭니다…</p>}

      {upload.kind === "failed" && <p role="alert">{upload.message}</p>}

      {upload.kind === "done" && <Result result={upload.result} label="올린 결과" />}

      {/* 고른 Pack 의 처리되지 않은 변동. 판을 올릴 때마다 다시 받는다. Pack 을 바꾸면 새로 그린다:
          앞에 고른 Pack 에서 아직 돌고 있던 다시 처리의 결과와 목록이 새로 고른 Pack 의 것처럼 보이지 않게 한다. */}
      {pack && <UnprocessedChanges key={pack.packId} packId={pack.packId} refreshKey={uploadCount} />}

      {/* 고른 Pack 의 자료가 제약을 어긴 곳. 판을 올릴 때마다 다시 받는다. Pack 을 바꾸면 조회 시점도 새로 시작한다. */}
      {pack && <ConstraintViolations key={`violations-${pack.packId}`} packId={pack.packId} refreshKey={uploadCount} />}

      {/* 고른 Pack 의 지난 판. 판을 올릴 때마다 다시 받는다. 고른 판의 결과는 올린 결과와 같은 방식으로 그린다. */}
      {pack && (
        <PastEditions
          key={`past-${pack.packId}`}
          packId={pack.packId}
          refreshKey={uploadCount}
          renderResult={(result) => <Result result={result} label="지난 판의 결과" />}
        />
      )}
    </section>
  );
}

// 판 하나의 결과. 올린 직후의 답과 지난 판을 다시 읽은 답이 같은 모양이라 함께 쓴다 (D-16).
function Result({ result, label }: { result: EditionSubmission; label: "올린 결과" | "지난 판의 결과" }) {
  const past = label === "지난 판의 결과";
  return (
    <section aria-label={label}>
      {past ? (
        <p role="status" data-testid="past-outcome">
          지난 판입니다: {result.packId} 의 {result.inputSourceLabel} {result.editionNo}판, 적용일 {result.effectiveDate}, 받아들인 항목{" "}
          {result.itemCount}개. 처리 상태와 조치의 수는 지금 저장된 값입니다.
        </p>
      ) : result.outcome === "ACCEPTED" ? (
        <p role="status" data-testid="outcome">
          새 판을 받아들였습니다: {result.packId} 의 {result.inputSourceLabel} {result.editionNo}판, 적용일 {result.effectiveDate}, 받아들인 항목{" "}
          {result.itemCount}개.
        </p>
      ) : (
        <p role="status" data-testid="outcome">
          내용이 바로 앞 판과 같아 새 판을 만들지 않았습니다. 지금의 판: {result.packId} 의 {result.inputSourceLabel}{" "}
          {result.editionNo}판, 적용일{" "}
          {result.effectiveDate}.
        </p>
      )}

      {result.processingNotStarted && (
        <p role="alert">
          판은 받아들였지만 바뀐 항목의 처리를 시작하지 못했습니다. 조치는 아직 만들어지지 않았습니다. 다음에 판을 올릴 때
          함께 처리됩니다.
        </p>
      )}

      {result.outcome === "ACCEPTED" &&
        (result.changes.length === 0 ? (
          // 왜 비었는지는 화면이 짐작하지 않는다. 서버가 준 것은 "바뀐 항목이 없다"까지다.
          <p role="status">바뀐 항목이 없습니다. 첫 판이면 견줄 앞 판이 없어 비어 있습니다.</p>
        ) : (
          <table>
            <caption>바뀐 항목</caption>
            <thead>
              <tr>
                <th scope="col">항목의 키</th>
                <th scope="col">변동</th>
                <th scope="col">주 개체</th>
                <th scope="col">변동 대상</th>
                <th scope="col">바뀐 값</th>
                <th scope="col">처리</th>
                <th scope="col">조치</th>
              </tr>
            </thead>
            <tbody>
              {result.changes.map((change) => (
                <tr key={change.changeEventId} data-change-event-id={change.changeEventId} data-item-key={change.itemKey}>
                  <td>{change.itemKey}</td>
                  <td>{KIND_LABEL[change.kind] ?? change.kind}</td>
                  {/* 개체는 서버가 준 표시 이름, 유형의 이름표, 키로 적는다 (D-15). 차례는 서버가 준 그대로다. */}
                  <td data-testid="primary-entity">
                    <EntityName entity={change.primaryEntity} />
                  </td>
                  <td data-testid="target-entities">
                    <ul>
                      {change.targetEntities.map((entity) => (
                        <li key={entity.entityId}>
                          <EntityName entity={entity} />
                        </li>
                      ))}
                    </ul>
                  </td>
                  <td data-testid="changed-values">
                    <ChangedValues change={change} />
                  </td>
                  <td>
                    {processingLabel(change.processing)}
                    {change.processing.status === "PROCESSED" && (
                      // 처리된 변동에는 저장된 영향 범위가 있다. 그 화면으로 가는 길 (D-03).
                      <>
                        {" "}
                        <Link to={`/impact?${new URLSearchParams({ changeEventId: change.changeEventId })}`}>영향 범위 보기</Link>
                      </>
                    )}
                  </td>
                  <td>
                    {change.processing.status === "PROCESSED" ? (
                      // 그 변동의 조치만 걸러서 여는 길. 수는 서버가 준 값이다.
                      <Link
                        to={`/actions?${new URLSearchParams({ packId: result.packId, status: "ALL", changeEventId: change.changeEventId })}`}
                      >
                        {actionCountLabel(change.processing)}
                      </Link>
                    ) : (
                      actionCountLabel(change.processing)
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}

      {result.notices.length > 0 && (
        <table>
          <caption>투입의 알림</caption>
          <thead>
            <tr>
              <th scope="col">행</th>
              <th scope="col">알림</th>
              <th scope="col">코드</th>
            </tr>
          </thead>
          <tbody>
            {result.notices.map((notice, index) => (
              // 알림에는 식별자가 없다. 서버가 준 차례가 곧 자리다.
              <tr key={index}>
                <td>{notice.rowNo}</td>
                {/* 문구는 서버가 풀어 쓴 것이다. 어느 행의 무엇이 왜 걸렸고 무엇을 하면 되는지가 들어 있다. */}
                <td>{notice.message}</td>
                <td>
                  <small>
                    {notice.kind}, {notice.code}
                  </small>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {result.retriedChanges.length > 0 && (
        <table>
          <caption>함께 다시 처리한 앞선 판의 변동</caption>
          <thead>
            <tr>
              <th scope="col">판</th>
              <th scope="col">항목의 키</th>
              <th scope="col">처리</th>
              <th scope="col">조치</th>
            </tr>
          </thead>
          <tbody>
            {result.retriedChanges.map((change) => (
              <tr key={change.changeEventId}>
                <td>{change.editionId}</td>
                <td>{change.itemKey}</td>
                <td>{processingLabel(change.processing)}</td>
                <td>{actionCountLabel(change.processing)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// 처리 상태를 사람이 읽는 말로 적는다. 실패의 단계와 이유는 서버가 준 코드를 그대로 붙인다.
// 그 행에서 바뀐 속성의 이전 값과 새 값 (D7 5절의 changedValues, D2 AD-367).
// 값과 차례는 서버가 준 그대로다. 화면은 두 값을 견주거나 고르지 않는다.
function ChangedValues({ change }: { change: EditionChange }) {
  if (change.changedValues === null) {
    // 빈 목록과 다르다: 바뀐 값이 없는 것이 아니라 서버가 이전 값을 모른다.
    return <small>이전 값을 알 수 없습니다. 바뀐 값을 남기기 전에 들어간 판과 견준 변동입니다.</small>;
  }
  if (change.changedValues.length === 0) {
    return <small>속성 값은 바뀌지 않았습니다.</small>;
  }
  return (
    <ul>
      {change.changedValues.map((value, index) => (
        // 같은 속성이 두 번 올 수 있어 차례를 열쇠에 넣는다. 목록은 서버가 준 차례 그대로이고 바뀌지 않는다.
        <li key={`${value.entityId}|${value.attributeName}|${index}`} data-attribute-name={value.attributeName}>
          {value.attributeLabel}{" "}
          {/* 개체가 여럿일 수 있어 어느 개체의 속성인지 함께 적는다. 표시 이름이 없으면 키다. */}
          <small data-testid="value-entity" data-entity-id={value.entityId}>
            ({value.entity.displayName ?? value.entity.entityKey})
          </small>
          {/* 보이는 글자는 서버가 준 것이다: 참·거짓은 예, 아니오로 온다. 화면은 값을 바꿔 적지 않는다. */}
          : <ChangedValue value={value.beforeDisplay} testId="value-before" /> →{" "}
          <ChangedValue value={value.afterDisplay} testId="value-after" />
        </li>
      ))}
    </ul>
  );
}

// 값 하나. 없던 값과 없어진 값은 빈 글자가 아니라 없다고 적는다. 긴 글의 줄바꿈은 그대로 보인다.
function ChangedValue({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) {
    return <small data-testid={testId}>(없음)</small>;
  }
  return (
    <span data-testid={testId} className="changed-value">
      {value}
    </span>
  );
}

function processingLabel(processing: ChangeProcessing): string {
  if (processing.status === "PROCESSED") {
    return "처리됨";
  }
  if (processing.status === "FAILED") {
    // 단계와 이유는 서버가 준 것만 적는다. 없으면 적지 않는다.
    const stage =
      processing.failedStage === "IMPACT" ? "영향 범위 계산" : processing.failedStage === "RULES" ? "규칙 실행" : null;
    const reason = [stage, processing.failureCode].filter((part) => part != null).join(", ");
    return `실패${reason ? ` (${reason})` : ""}. 다음에 판을 올릴 때 다시 시도됩니다`;
  }
  return "대기 (아직 처리하지 못했습니다)";
}

// 조치의 수는 서버가 준 값이다. 처리되지 않은 변동은 조치가 없는 것이 아니라 아직 만들지 못한 것이다.
function actionCountLabel(processing: ChangeProcessing): string {
  return processing.status === "PROCESSED" ? `${processing.actionCount}건` : "아직 만들지 못했습니다";
}
