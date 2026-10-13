# Aura Request Decoder

> Burp Suite extension để **đọc – sửa – gửi lại** request của Salesforce **Aura / Lightning**
> ngay trong Repeater & Proxy, với giao diện JSON tô màu.

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-21%2B-orange">
  <img alt="Burp" src="https://img.shields.io/badge/Burp-Montoya%20API-ff6633">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
</p>

Request Aura đi trên dây ở dạng `x-www-form-urlencoded`, các value lại là JSON đã URL-encode —
rất khó đọc và gần như không thể sửa tay:

```
message=%7B%22actions%22%3A%5B%7B%22id%22%3A%22285%3Ba%22%2C%22descriptor%22%3A%22serviceComponent%3A%2F%2F...
```

Extension này thêm một tab **`Aura (decoded)`** vào trình soạn request của Burp. Tab đó decode
body ra dạng dễ đọc, cho bạn **chỉnh sửa trực tiếp**, rồi **tự encode lại chuẩn** khi gửi.

---

## Tính năng

- 🔓 **Decode 2 chiều**: tách form + URL-decode + pretty-print JSON; khi gửi tự compact JSON và
  URL-encode lại thành body Aura hợp lệ.
- 🎨 **Tô màu cú pháp** kiểu VS Code, tự đổi palette theo theme **sáng/tối** của Burp.
- ✍️ **Sửa trực tiếp** trong tab; không sửa thì request gốc đi **nguyên văn** (byte-for-byte).
- ↩️ **Undo / Redo** (`Ctrl+Z` / `Ctrl+Y`) — thao tác tô màu không lọt vào lịch sử undo.
- 🧹 **Format**: beautify + **validate** JSON, báo ngay field nào sai cú pháp.
- 📋 **Copy encoded**: copy body đã URL-encode (bản sẽ gửi lên dây) ra clipboard.
- 🔎 **Find**: live-search, **tô vàng tất cả match**, đếm vị trí `index/tổng` (vd `3/12`),
  `Enter`/`Shift+Enter` nhảy match kế/trước (wrap-around); **Wrap** bật/tắt xuống dòng.
- 🖋️ **Font & màu** kiểu editor của Burp (Consolas), nhẹ mắt ở cả theme sáng/tối.
- 🌐 **POST & GET**: decode cả `message` ở **body** (POST) lẫn **query string** (GET
  `/aura?message=...`); sửa xong gửi lại đúng chỗ.
- 🎯 **Active scan** (chuột phải trong tab): **selected value (insertion point)** — bôi đen
  một giá trị rồi quét đúng insertion point đó (map về Range trong request thật); hoặc
  **whole request**. Dồn vào một audit dùng chung.
- 📦 **Zero dependency** ngoài Montoya API — JSON parser/printer và editor đều tự viết.

## Hoạt động trong

Repeater · Proxy (intercept & history) · Intruder · bất kỳ chỗ nào Burp dùng HTTP request editor.

---

## Giao diện tab

Mỗi field của body hiện dưới một header `# <tên field>`:

```
# message
{
  "actions": [
    {
      "id": "285;a",
      "descriptor": "serviceComponent://ui.global.components.one.header.HeaderController/ACTION$getSettings",
      "params": {}
    }
  ]
}

# aura.context
{ "mode": "PROD", "app": "one:one" }

# aura.pageURI
/lightning/n/sf_GsPilotV1__Getting_Started

# aura.token
null
```

> ⚠️ **Khi sửa, giữ nguyên các dòng header `# <tên field>`** — đó là mốc để extension ghép
> lại body lúc gửi. Nội dung dưới mỗi header cứ sửa thoải mái. JSON hợp lệ sẽ được nén lại
> khi gửi; JSON lỗi cú pháp được giữ nguyên văn bản để không chặn việc gửi.

## Phím tắt

| Phím | Chức năng |
|------|-----------|
| `Ctrl+Z` / `Ctrl+Y` | Undo / Redo (`Ctrl+Shift+Z` cũng là Redo) |
| `Ctrl+Shift+F` | Format (beautify + validate) |
| `Ctrl+F` | Nhảy vào ô Find |
| `Enter` / `Shift+Enter` | Find match kế / trước |
| `Esc` | Từ ô Find quay lại editor |

---

## Yêu cầu

- **Burp Suite** (Community hoặc Professional) có hỗ trợ Montoya API.
- **JDK 17+** để build (đã test với JDK 21 và 26). Không cần Maven/Gradle.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Kết quả: `dist/aura-decoder.jar`.

Script tự dò JDK, tải `montoya-api` từ Maven Central vào `lib/` (chỉ dùng để compile; Burp
cấp API này lúc chạy) rồi compile với `--release 21` để tương thích JRE Java 25 mà Burp đóng
gói. Nếu máy không có mạng, tải thủ công `montoya-api-2026.7.jar` và đặt vào `lib/montoya-api.jar`.

> Repo đã kèm sẵn `dist/aura-decoder.jar` build sẵn — có thể dùng ngay không cần build.

## Cài vào Burp

1. **Extensions → Installed → Add**
2. Extension type: **Java**
3. Chọn `dist/aura-decoder.jar` → **Next**
4. Thấy log `Aura Request Decoder loaded...` ở tab **Output** là xong.
5. Mở một request Aura trong Repeater → chọn tab **`Aura (decoded)`**.

---

## Kiến trúc

| File | Vai trò |
|------|---------|
| `DecoderExtension.java` | Entry point (`BurpExtension`), đăng ký editor provider |
| `DecodedRequestEditorProvider.java` | Cấp editor cho mỗi host (Repeater, Proxy…) |
| `DecodedRequestEditor.java` | Tab editable; decode khi hiển thị, re-encode khi gửi |
| `ColoredJsonEditor.java` | Editor Swing: tô màu, undo/redo, toolbar, find, status |
| `AuraDecoder.java` | Codec 2 chiều: form ⇄ text (URL-decode/encode + JSON) |
| `Json.java` | JSON parser + pretty/compact printer tự viết (0 dependency) |

## Giới hạn & hướng phát triển

- **Nested JSON escaped** (ví dụ field `payload` trong telemetry `ailtn`) được giữ nguyên dạng
  chuỗi JSON đã escape để đảm bảo gửi lại **chính xác 1-1**. Có thể bổ sung chế độ “expand
  payload” dùng marker nếu cần sửa trực tiếp phần lồng này.
- Chưa decode **response** — có thể thêm `HttpResponseEditorProvider` tương tự.
- Có thể mở rộng thêm kiểu mã hóa khác (Base64, AES/XOR…) trong `AuraDecoder`.

## License

[MIT](LICENSE)
