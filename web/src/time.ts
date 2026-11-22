// 시각을 화면에 적는 법 (D7 9절 "내 정보", D2 AD-326, AD-348).
//
// API 의 시각은 모두 협정 세계시다. 화면은 설치 설정의 시간대(내 정보의 timeZone)로 바꿔 보여 준다.
// 보는 사람의 브라우저의 시간대를 따르지 않는다: 같은 시각이 누구에게나 같게 보인다.

// 2026-06-03 18:10:11 의 모양으로 적는다. 날짜의 모양은 API 의 날짜(2026-06-03)와 같다.
export function formatInstant(instant: string, timeZone: string | undefined): string {
  const date = new Date(instant);
  if (!timeZone || Number.isNaN(date.getTime())) {
    // 시간대를 모르거나 읽을 수 없는 값이면 서버가 준 글자를 그대로 보인다. 지어내지 않는다.
    return instant;
  }
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    }).formatToParts(date);
  } catch {
    // 브라우저가 모르는 시간대다.
    return instant;
  }
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((candidate) => candidate.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")} ${part("hour")}:${part("minute")}:${part("second")}`;
}
