package burp.decoder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;

import java.awt.Component;

/**
 * Editable, syntax-highlighted "Aura (decoded)" tab. Shows the Aura body decoded and
 * pretty-printed; when the user edits it and the request is sent, {@link #getRequest()}
 * re-encodes the edits back into the x-www-form-urlencoded wire body.
 */
final class DecodedRequestEditor implements ExtensionProvidedHttpRequestEditor {

    private final AuraDecoder decoder = new AuraDecoder();
    private final ColoredJsonEditor editor;

    private HttpRequestResponse current;

    DecodedRequestEditor(MontoyaApi api, EditorCreationContext ctx) {
        boolean dark = api.userInterface().currentTheme() == Theme.DARK;
        this.editor = new ColoredJsonEditor(decoder, dark);
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) return false;
        try {
            return decoder.isEditableForm(requestResponse.request().bodyToString());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.current = requestResponse;
        String decoded;
        try {
            decoded = decoder.decodeToEditor(requestResponse.request().bodyToString());
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

        String wireBody = decoder.encodeFromEditor(editor.getText());
        return current.request().withBody(wireBody);
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
