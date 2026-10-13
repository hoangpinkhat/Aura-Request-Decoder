package burp.decoder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;

/** Supplies a {@link DecodedRequestEditor} to every editor host (Repeater, Proxy, ...). */
final class DecodedRequestEditorProvider implements HttpRequestEditorProvider {

    private final MontoyaApi api;

    DecodedRequestEditorProvider(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        return new DecodedRequestEditor(api, creationContext);
    }
}
