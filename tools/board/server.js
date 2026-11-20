// Tabnit Base 개발 보드 서버. 외부 패키지 없이 Node.js 18 이상에서 실행합니다.
//   node server.js            → http://localhost:8080
//   PORT=9000 BOARD_USER=tabnit BOARD_PASS=비밀번호 node server.js
const http = require("http"), fs = require("fs"), path = require("path");
const PORT = Number(process.env.PORT || 8080);
const DIR = __dirname, DATA_DIR = process.env.DATA_DIR || path.join(DIR, "data"), FILE = path.join(DATA_DIR, "board.json");
const USER = process.env.BOARD_USER || "", PASS = process.env.BOARD_PASS || "";
const SEG = /^[\w.~:@+-]{1,200}$/;

fs.mkdirSync(DATA_DIR, { recursive: true });
let state = { rev: 0, docs: {} };
try { state = JSON.parse(fs.readFileSync(FILE, "utf8")); } catch (e) {}
let saving = false, dirty = false;
function save() {
  if (saving) { dirty = true; return; }
  saving = true;
  const tmp = FILE + ".tmp";
  fs.writeFile(tmp, JSON.stringify(state), err => {
    const done = () => { saving = false; if (dirty) { dirty = false; save(); } };
    if (err) { console.error("저장 실패:", err.message); return done(); }
    fs.rename(tmp, FILE, done);
  });
}
function send(res, code, body, type) {
  res.writeHead(code, { "Content-Type": type || "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body == null ? "" : typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}
function readBody(req) {
  return new Promise((ok, no) => { let n = 0; const parts = [];
    req.on("data", c => { n += c.length; if (n > 300 * 1024) { no(new Error("too large")); req.destroy(); } else parts.push(c); });
    req.on("end", () => { try { const j = JSON.parse(Buffer.concat(parts).toString("utf8")); if (!j || typeof j !== "object" || Array.isArray(j)) throw 0; ok(j); } catch (e) { no(new Error("bad json")); } });
    req.on("error", no); });
}
http.createServer(async (req, res) => {
  if (USER) {
    const got = Buffer.from((req.headers.authorization || "").replace(/^Basic /, ""), "base64").toString("utf8");
    if (got !== USER + ":" + PASS) { res.writeHead(401, { "WWW-Authenticate": 'Basic realm="Tabnit Board", charset="UTF-8"' }); return res.end("로그인이 필요합니다."); }
  }
  const u = new URL(req.url, "http://x");
  try {
    if (u.pathname === "/api/state" && req.method === "GET") {
      if (u.searchParams.get("since") === String(state.rev)) return send(res, 204);
      return send(res, 200, state);
    }
    if (u.pathname === "/api/doc" && req.method === "PUT") {
      const p = u.searchParams.get("path") || "", seg = p.split("/");
      if (seg.length !== 2 || !seg.every(s => SEG.test(s) && s !== '.' && s !== '..')) return send(res, 400, { error: "bad path" });
      state.docs[p] = await readBody(req); state.rev++; save();
      return send(res, 200, { rev: state.rev });
    }
    if ((u.pathname === "/" || u.pathname === "/index.html") && req.method === "GET")
      return send(res, 200, fs.readFileSync(path.join(DIR, "index.html")), "text/html; charset=utf-8");
    send(res, 404, { error: "not found" });
  } catch (e) { send(res, 400, { error: e.message }); }
}).listen(PORT, () => console.log(`Tabnit Base 개발 보드: http://localhost:${PORT}  (자료: ${FILE})${USER ? "  로그인 사용" : ""}`));
