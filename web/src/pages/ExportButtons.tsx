import { useState } from "react";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// 목록을 파일로 받는 단추 (카드 D-21, D7 "목록 내보내기", D2 AD-412).
//
// 이 칸은 계산하지 않는다. 무엇을 어떤 순서로 담을지, 파일 이름, 조회 시점은 서버가 정한다. 화면은 지금 보고 있는
// 목록과 같은 조건을 보내고, 받은 파일을 그대로 저장하게 넘긴다. 받지 못하면 서버가 알린 까닭을 보인다.

export type ExportFormat = "csv" | "json";

type Answer = { data?: Blob; error?: unknown; response: Response };

type Props = {
  // 묶음의 이름. "관계 목록 내보내기" 처럼 적는다.
  label: string;
  // 그 형식으로 서버에 청한다. api (src/api/client.ts) 로 부르고 본문을 파일(blob)로 받는다.
  request: (format: ExportFormat) => Promise<Answer>;
  disabled?: boolean;
};

const FORMATS: { format: ExportFormat; name: string }[] = [
  { format: "csv", name: "CSV" },
  { format: "json", name: "JSON" },
];

// 서버가 준 파일 이름. 없으면 형식만으로 짓는다.
function fileName(response: Response, format: ExportFormat) {
  const found = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") ?? "");
  return found?.[1] ?? `export.${format}`;
}

export function ExportButtons({ label, request, disabled = false }: Props) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);

  async function save(format: ExportFormat) {
    setBusy(true);
    setFailed(null);
    try {
      const { data, error, response } = await request(format);
      if (!data) {
        setFailed(messageOf(error, response.status));
        return;
      }
      const url = URL.createObjectURL(data);
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName(response, format);
      document.body.append(link);
      link.click();
      link.remove();
      // 주소는 조금 뒤에 지운다. 누르자마자 지우면 브라우저에 따라 내려받기를 시작하기 전에 주소가 사라져
      // 파일이 저장되지 않는다 (알림도 없다).
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      setFailed(CONNECTION_FAILED);
    } finally {
      setBusy(false);
    }
  }

  return (
    <p role="group" aria-label={label}>
      <span>이 목록 전체를 파일로 받기</span>{" "}
      {FORMATS.map(({ format, name }) => (
        <button key={format} type="button" disabled={disabled || busy} onClick={() => void save(format)}>
          {name}
        </button>
      ))}{" "}
      <small>지금 보는 조건의 전체를 담습니다. 쪽을 넘겨도 같은 파일입니다.</small>
      {failed !== null && (
        <>
          {" "}
          <span role="alert">{failed}</span>
        </>
      )}
    </p>
  );
}
