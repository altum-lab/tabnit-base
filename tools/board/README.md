# Tabnit Base 개발 보드 (단독 실행판, v2 · 2026-11-19)

대외비. 사내에서만 쓰세요.

## 1. 혼자 쓸 때: 파일로 바로 열기
`index.html`을 브라우저로 엽니다. 고친 내용은 그 브라우저에만 저장됩니다.
다른 PC로 옮기려면 "작업 방식" 화면의 "백업 내려받기 / 백업 불러오기"를 씁니다.

## 2. 여럿이 쓸 때: 서버로 띄우기
Node.js 18 이상이 필요합니다. 설치할 패키지는 없습니다.

    node server.js

브라우저에서 `http://<서버 주소>:8080`을 엽니다. 고친 내용은 `data/board.json`에 저장되고
다른 사람 화면에 3초 안에 반영됩니다.

설정(환경 변수)
- `PORT` 포트 (기본 8080)
- `BOARD_USER`, `BOARD_PASS` 둘 다 주면 로그인 창이 뜹니다. 사내망 밖에서 접근할 수 있는 서버라면 반드시 설정하고 HTTPS 뒤에 두세요.
- `DATA_DIR` 자료 폴더 (기본 `./data`)

예)

    PORT=9000 BOARD_USER=tabnit BOARD_PASS='바꿔주세요' node server.js

계속 띄워 두기: `nohup node server.js &` 또는 pm2, systemd, Docker 중 편한 것.

    docker run -d --name tabnit-board -p 8080:8080 -v "$PWD":/app -w /app node:20-alpine node server.js

## 백업
`data/board.json` 파일 하나가 전부입니다. 이 파일을 복사해 두면 됩니다.

## 알아 둘 것
- 처음 계획(작업 84건, 스프린트, 설계 문서, Claude 활용)은 `index.html` 안에 들어 있고, 서버에는 바뀐 내용만 저장됩니다. v1 의 `data/board.json` 은 그대로 쓸 수 있습니다.
- 같은 작업을 두 사람이 동시에 고치면 나중에 저장한 쪽이 남습니다.
- 글꼴은 인터넷이 되면 IBM Plex를 받고, 안 되면 시스템 글꼴로 보입니다.

## v2 에서 바뀐 것
- **Claude 활용** 화면: 쓰는 도구 한 장, 모듈별로 Claude Code 에 넣는 문장, 결정표, 쇼츠 장면과 자료의 대응표.
- 작업을 열면 맨 아래에 그 작업에 맞춘 **Claude로 하는 법**이 나옵니다. 위에서부터 차례로 Claude Code 에 넣습니다.
- 작업 2건 추가: SK-10 (Claude Code 작업 환경 구성), E-17 (합류자 환경 점검과 첫 카드 한 바퀴). M1 의 필요 작업에 들어갑니다.
- 위험 R7 추가: Claude Code 나 플러그인이 바뀌어 셋업이 깨질 때의 처리.
- 쇼츠 「Tabnit을 만드는 Claude Code 세팅」, 셋업 묶음 v2 의 `GUIDE.md`, 저장소의 `docs/claude-playbook.md` 와 장면 번호로 맞물립니다.
