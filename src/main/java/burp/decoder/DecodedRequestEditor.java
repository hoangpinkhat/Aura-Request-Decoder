package burp.decoder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Editable, syntax-highlighted "Aura (decoded)" tab. Shows the Aura body decoded and
 * pretty-printed; when the user edits it and the request is sent, {@link #getRequest()}
 * re-encodes the edits back into the x-www-form-urlencoded wire body.
 */
final class DecodedRequestEditor implements ExtensionProvidedHttpRequestEditor {

    private final MontoyaApi api;
    private final AuraDecoder decoder = new AuraDecoder();
    private final ColoredJsonEditor editor;

    private HttpRequestResponse current;
    /** true nếu Aura data nằm ở query string (GET) thay vì body (POST). */
    private boolean sourceIsQuery;
    /** Audit dùng chung cho "Active scan" từ context menu. */
    private Audit scanAudit;

    DecodedRequestEditor(MontoyaApi api, EditorCreationContext ctx) {
        this.api = api;
        boolean dark = api.userInterface().currentTheme() == Theme.DARK;
        this.editor = new ColoredJsonEditor(decoder, dark);
        this.editor.setScanHandler(this::onScan);
    }

    /** Active scan: nếu selectedValue != null, map nó về Range trong request và scan đúng insertion point đó. */
    private void onScan(String selectedValue) {
        if (current == null) return;
        HttpRequest req = current.request();
        try {
            if (scanAudit == null) {
                scanAudit = api.scanner().startAudit(
                        AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS));
            }
            if (selectedValue == null) {
                scanAudit.addRequest(req); // cả request, Burp tự chọn insertion point
                JOptionPane.showMessageDialog(null, "Đã gửi cả request vào active scan (xem Dashboard).");
                return;
            }
            Range range = locate(req, selectedValue);
            if (range == null) {
                JOptionPane.showMessageDialog(null,
                        "Không tìm thấy giá trị đã chọn trong request thô.\n"
                        + "Có thể nó bị double-encode (JSON-trong-string). Thử chọn giá trị ở tầng ngoài, "
                        + "hoặc dùng 'Active scan: whole request'.");
                return;
            }
            scanAudit.addRequest(req, List.of(range));
            JOptionPane.showMessageDialog(null,
                    "Đã gửi 1 insertion point vào active scan (offset "
                    + range.startIndexInclusive() + "–" + range.endIndexExclusive() + "). Xem Dashboard.");
        } catch (RuntimeException ex) {
            api.logging().logToError("Active scan failed: " + ex.getMessage());
        }
    }

    /** Tìm vị trí (Range theo byte) của giá trị trên wire; thử bản URL-encoded trước, rồi raw. */
    private Range locate(HttpRequest req, String value) {
        ByteArray ba = req.toByteArray();
        byte[] bytes = new byte[ba.length()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = ba.getByte(i);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);

        String enc = URLEncoder.encode(value, StandardCharsets.UTF_8);
        int idx = raw.indexOf(enc);
        if (idx >= 0) return Range.range(idx, idx + enc.length());
        idx = raw.indexOf(value);
        if (idx >= 0) return Range.range(idx, idx + value.length());
        return null;
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) return false;
        try {
            HttpRequest r = requestResponse.request();
            return decoder.isEditableForm(r.bodyToString()) || decoder.isEditableForm(r.query());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.current = requestResponse;
        String decoded = null;
        try {
            HttpRequest r = requestResponse.request();
            String body = r.bodyToString();
            // POST: Aura data trong body; GET: trong query string.
            if (decoder.isEditableForm(body)) {
                sourceIsQuery = false;
                decoded = decoder.decodeToEditor(body);
            } else {
                sourceIsQuery = true;
                decoded = decoder.decodeToEditor(r.query());
            }
        } catch (RuntimeException ex) {
            decoded = null;
        }
        editor.setContent(decoded != null ? decoded : "(nothing to decode)");
    }

    @Override
    public HttpRequest getRequest() {
        if (current == null) return HttpRequest.httpRequest();
        // Untouched: pass the original request through byte-for-byte.
        if (!editor.isModified()) return current.request();

        String wire = decoder.encodeFromEditor(editor.getText());
        if (sourceIsQuery) {
            // GET: ghi lại vào query string trên URL.
            return current.request().withPath(current.request().pathWithoutQuery() + "?" + wire);
        }
        return current.request().withBody(wire);
    }

    @Override
    public boolean isModified() {
        return editor.isModified();
    }

    @Override
    public String caption() {
        return "Aura (decoded)";
    }

    @Override
    public Component uiComponent() {
        return editor.getComponent();
    }

    @Override
    public Selection selectedData() {
        return null; // selection is handled inside the custom editor
    }
}
