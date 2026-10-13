package burp.decoder;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * Entry point. Burp instantiates this class (it implements BurpExtension) on load.
 *
 * Adds an editable "Aura (decoded)" tab to the HTTP request editor used by
 * Repeater, Proxy, Intruder and the HTTP history. It decodes Salesforce Aura/Lightning
 * request bodies (URL-encoded form values + pretty-printed JSON) for editing, and
 * re-encodes any edits back onto the wire when the request is sent.
 */
public final class DecoderExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Aura Request Decoder");
        api.userInterface().registerHttpRequestEditorProvider(new DecodedRequestEditorProvider(api));
        api.logging().logToOutput("Aura Request Decoder loaded. Editable 'Aura (decoded)' tab added to request editors.");
    }
}
