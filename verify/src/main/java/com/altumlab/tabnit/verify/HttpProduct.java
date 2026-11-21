package com.altumlab.tabnit.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;

/**
 * 띄워 둔 제품에 HTTP 로 묻는다 (D2 AD-111, AD-279). 화면이 하는 대로 한다: 로그인하고, 쿠키를 들고 다니고,
 * 바꾸는 요청에 위조 요청 방어의 토큰을 싣는다 (D7 9절).
 *
 * <p>제품이 실제로 내놓는 값을 본다. 모듈의 내부나 테이블은 보지 않는다.
 */
final class HttpProduct implements Product {

    private final HttpClient client = HttpClient.newHttpClient();

    private final JsonParser json = JsonParserFactory.getJsonParser();

    private final String base;

    private final Map<String, String> cookies = new LinkedHashMap<>();

    HttpProduct(int port) {
        this.base = "http://localhost:" + port;
    }

    /** 로그인한다. 먼저 내 정보를 물어 토큰을 받는다. */
    Reply login(String loginId, String password) {
        send("GET", "/api/v1/session", null, null);
        return send("POST", "/api/v1/session", "application/json",
                ("{\"loginId\":" + quote(loginId) + ",\"password\":" + quote(password) + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** 서버의 Pack 폴더에 있는 Pack 을 적재한다. */
    Reply loadPack(String packId) {
        return send("POST", "/api/v1/pack-loads", "application/json",
                ("{\"packId\":" + quote(packId) + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** 판을 올린다. */
    Reply submitEdition(String packId, String inputSourceId, LocalDate effectiveDate, String fileName, byte[] content) {
        String boundary = "----tabnit-verify-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("packId", packId);
        fields.put("inputSourceId", inputSourceId);
        fields.put("effectiveDate", effectiveDate.toString());
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                        + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            // 파일의 이름은 제품의 결과에 닿지 않는다. 헤더를 깨지 않는 글자만 남긴다.
            String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeName
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return send("POST", "/api/v1/editions", "multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    @Override
    public Reply relations(String entityId, LocalDate asOf) {
        return send("GET", "/api/v1/relations?entityId=" + encode(entityId) + "&asOf=" + asOf, null, null);
    }

    @Override
    public Reply impactScope(String changeEventId) {
        return send("GET", "/api/v1/impact-scopes?changeEventId=" + encode(changeEventId), null, null);
    }

    @Override
    public Reply actions(String packId, String changeEventId) {
        return send("GET", "/api/v1/actions?packId=" + encode(packId) + "&changeEventId=" + encode(changeEventId), null, null);
    }

    private Reply send(String method, String path, String contentType, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.base + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (!this.cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            this.cookies.forEach((name, value) -> header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
            request.header("Cookie", header.toString());
        }
        if (!method.equals("GET") && this.cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", this.cookies.get("XSRF-TOKEN"));
        }
        HttpResponse<String> response;
        try {
            response = this.client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException("제품에 연결하지 못했습니다: " + method + " " + path, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("제품의 답을 기다리다 멈췄습니다: " + method + " " + path, e);
        }
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            String name = pair.substring(0, pair.indexOf('='));
            String value = pair.substring(pair.indexOf('=') + 1);
            if (value.isEmpty()) {
                this.cookies.remove(name);
            }
            else {
                this.cookies.put(name, value);
            }
        }
        String text = response.body();
        Object parsed = null;
        if (text != null && !text.isBlank()) {
            String trimmed = text.strip();
            parsed = trimmed.startsWith("[") ? this.json.parseList(trimmed) : trimmed.startsWith("{") ? this.json.parseMap(trimmed) : text;
        }
        return new Reply(response.statusCode(), parsed);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            }
            else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            }
            else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
