package burp.decoder;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * Editable, syntax-highlighted editor for the Aura "decoded" view, with a toolbar
 * of test conveniences: Undo/Redo, Format (beautify + validate), Find, Copy-encoded
 * (the re-encoded wire body), word-wrap toggle, and a status line.
 *
 * Pure Swing - no external dependencies.
 */
final class ColoredJsonEditor {

    private final AuraDecoder decoder;
    private final JTextPane textPane = new JTextPane();
    private final JScrollPane scrollPane;
    private final JPanel noWrapPanel;
    private final JPanel root = new JPanel(new BorderLayout());
    private final Styles styles;
    private final UndoManager undo = new UndoManager();

    private final JTextField findField = new JTextField(16);
    private final JLabel status = new JLabel(" ");

    private boolean loading;     // content set programmatically
    private boolean applying;    // re-applying colours (guards reentrancy)
    private boolean inUndoRedo;  // performing undo/redo
    private boolean modified;
    private boolean wrap;

    ColoredJsonEditor(AuraDecoder decoder, boolean darkTheme) {
        this.decoder = decoder;
        this.styles = new Styles(darkTheme);

        textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textPane.setBackground(styles.background);
        textPane.setForeground(styles.defaultFg);
        textPane.setCaretColor(styles.defaultFg);

        noWrapPanel = new JPanel(new BorderLayout());
        noWrapPanel.add(textPane, BorderLayout.CENTER);
        scrollPane = new JScrollPane(noWrapPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        status.setForeground(styles.defaultFg);

        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);

        wireDocument();
        wireKeyBindings();
    }

    // --- public surface used by the Burp editor tab ----------------------

    Component getComponent() { return root; }

    void setContent(String text) {
        loading = true;
        textPane.setText(text);
        textPane.setCaretPosition(0);
        loading = false;
        undo.discardAllEdits();
        modified = false;
        highlightNow();
        showStatus(validateMessage());
    }

    String getText() {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return textPane.getText();
        }
    }

    boolean isModified() { return modified; }

    // --- toolbar ---------------------------------------------------------

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(button("Undo", "Undo (Ctrl+Z)", e -> undo()));
        bar.add(button("Redo", "Redo (Ctrl+Y)", e -> redo()));
        bar.addSeparator();
        bar.add(button("Format", "Beautify + validate JSON (Ctrl+Shift+F)", e -> format()));
        bar.add(button("Copy encoded", "Copy the re-encoded wire body to clipboard", e -> copyEncoded()));
        bar.addSeparator();

        bar.add(new JLabel(" Find: "));
        findField.setMaximumSize(new Dimension(220, 26));
        bar.add(findField);
        bar.add(button("◀", "Previous match (Shift+Enter)", e -> find(false)));
        bar.add(button("▶", "Next match (Enter)", e -> find(true)));
        bar.addSeparator();

        JCheckBox wrapBox = new JCheckBox("Wrap");
        wrapBox.setToolTipText("Toggle line wrapping");
        wrapBox.addActionListener(e -> setWrap(wrapBox.isSelected()));
        bar.add(wrapBox);

        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private JButton button(String text, String tip, java.awt.event.ActionListener onClick) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(onClick);
        return b;
    }

    // --- actions ---------------------------------------------------------

    private void format() {
        String formatted = decoder.reformat(getText());
        int caret = textPane.getCaretPosition();
        loading = true;
        textPane.setText(formatted);
        loading = false;
        modified = true;
        try { textPane.setCaretPosition(Math.min(caret, formatted.length())); } catch (RuntimeException ignored) {}
        highlightNow();
        showStatus(validateMessage());
    }

    private void copyEncoded() {
        String wire = decoder.encodeFromEditor(getText());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(wire), null);
        showStatus("Copied " + wire.length() + " bytes (URL-encoded body) to clipboard");
    }

    private void find(boolean forward) {
        String q = findField.getText();
        if (q.isEmpty()) return;
        String hay = getText().toLowerCase();
        String needle = q.toLowerCase();
        int caret = textPane.getCaretPosition();

        int idx;
        if (forward) {
            idx = hay.indexOf(needle, caret);
            if (idx < 0) idx = hay.indexOf(needle); // wrap to top
        } else {
            int from = Math.max(0, caret - needle.length() - 1);
            idx = hay.lastIndexOf(needle, from);
            if (idx < 0) idx = hay.lastIndexOf(needle); // wrap to bottom
        }

        if (idx >= 0) {
            textPane.requestFocusInWindow();
            textPane.setCaretPosition(idx);
            textPane.moveCaretPosition(idx + needle.length());
            showStatus("Found at offset " + idx);
        } else {
            showStatus("Not found: " + q);
        }
    }

    private void setWrap(boolean on) {
        if (wrap == on) return;
        wrap = on;
        if (on) {
            noWrapPanel.remove(textPane);
            scrollPane.setViewportView(textPane); // JTextPane wraps by default
        } else {
            scrollPane.setViewportView(noWrapPanel);
            noWrapPanel.add(textPane, BorderLayout.CENTER);
        }
        root.revalidate();
        root.repaint();
    }

    private void undo() {
        try {
            inUndoRedo = true;
            if (undo.canUndo()) undo.undo();
        } catch (CannotUndoException ignored) {
        } finally {
            inUndoRedo = false;
        }
        SwingUtilities.invokeLater(this::highlightNow);
    }

    private void redo() {
        try {
            inUndoRedo = true;
            if (undo.canRedo()) undo.redo();
        } catch (CannotRedoException ignored) {
        } finally {
            inUndoRedo = false;
        }
        SwingUtilities.invokeLater(this::highlightNow);
    }

    // --- wiring ----------------------------------------------------------

    private void wireDocument() {
        textPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { /* attribute-only: ignore */ }
        });
        // Record only genuine text edits (not colour application, not initial load).
        textPane.getDocument().addUndoableEditListener(e -> {
            if (applying || loading || inUndoRedo) return;
            undo.addEdit(e.getEdit());
        });
    }

    private void wireKeyBindings() {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(textPane, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "undo", e -> undo());
        bind(textPane, KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "redo", e -> redo());
        bind(textPane, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | KeyEvent.SHIFT_DOWN_MASK), "redo2", e -> redo());
        bind(textPane, KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), "focusFind", e -> findField.requestFocusInWindow());
        bind(textPane, KeyStroke.getKeyStroke(KeyEvent.VK_F, menu | KeyEvent.SHIFT_DOWN_MASK), "format", e -> format());

        // Enter / Shift+Enter inside the find field jumps to next / previous match.
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "findNext", e -> find(true));
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findPrev", e -> find(false));
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "toEditor", e -> textPane.requestFocusInWindow());
    }

    private void bind(JComponent c, KeyStroke ks, String name, java.awt.event.ActionListener action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.actionPerformed(e); }
        });
    }

    private void onEdit() {
        if (loading || applying) return;
        modified = true;
        SwingUtilities.invokeLater(() -> { highlightNow(); showStatus(validateMessage()); });
    }

    private void showStatus(String msg) {
        status.setText(msg == null || msg.isEmpty() ? " " : msg);
    }

    private String validateMessage() {
        String err = decoder.firstInvalidField(getText());
        if (err == null) return "JSON OK";
        return "Invalid JSON in field: " + err;
    }

    // --- syntax highlighting --------------------------------------------

    private void highlightNow() {
        if (applying) return;
        applying = true;
        try {
            StyledDocument doc = textPane.getStyledDocument();
            String text = doc.getText(0, doc.getLength());
            int n = text.length();
            doc.setCharacterAttributes(0, n, styles.def, true);

            int i = 0;
            boolean lineStart = true;
            while (i < n) {
                char c = text.charAt(i);

                if (lineStart && c == '#' && i + 1 < n && text.charAt(i + 1) == ' ') {
                    int j = i;
                    while (j < n && text.charAt(j) != '\n') j++;
                    doc.setCharacterAttributes(i, j - i, styles.header, true);
                    i = j;
                    continue;
                }
                if (c == '\n') { lineStart = true; i++; continue; }
                lineStart = false;

                if (c == '"') {
                    int start = i++;
                    while (i < n) {
                        char d = text.charAt(i);
                        if (d == '\\') { i += 2; continue; }
                        if (d == '"' || d == '\n') { if (d == '"') i++; break; }
                        i++;
                    }
                    int k = i;
                    while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) k++;
                    boolean isKey = k < n && text.charAt(k) == ':';
                    doc.setCharacterAttributes(start, i - start, isKey ? styles.key : styles.str, true);
                    continue;
                }
                if (c == '-' || (c >= '0' && c <= '9')) {
                    int start = i++;
                    while (i < n) {
                        char d = text.charAt(i);
                        if ((d >= '0' && d <= '9') || d == '.' || d == 'e' || d == 'E' || d == '+' || d == '-') i++;
                        else break;
                    }
                    doc.setCharacterAttributes(start, i - start, styles.num, true);
                    continue;
                }
                if (keyword(text, i, "true") || keyword(text, i, "false") || keyword(text, i, "null")) {
                    int len = c == 'f' ? 5 : 4;
                    doc.setCharacterAttributes(i, len, styles.kw, true);
                    i += len;
                    continue;
                }
                if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
                    doc.setCharacterAttributes(i, 1, styles.punct, true);
                    i++;
                    continue;
                }
                i++;
            }
        } catch (BadLocationException ignored) {
            // transient during edits
        } finally {
            applying = false;
        }
    }

    private static boolean keyword(String text, int i, String kw) {
        if (!text.startsWith(kw, i)) return false;
        int after = i + kw.length();
        if (after < text.length()) {
            char c = text.charAt(after);
            if (Character.isLetterOrDigit(c) || c == '_') return false; // word boundary
        }
        return true;
    }

    /** Colour set + text styles for one theme. */
    private static final class Styles {
        final Color background;
        final Color defaultFg;
        final SimpleAttributeSet def = new SimpleAttributeSet();
        final SimpleAttributeSet header = new SimpleAttributeSet();
        final SimpleAttributeSet key = new SimpleAttributeSet();
        final SimpleAttributeSet str = new SimpleAttributeSet();
        final SimpleAttributeSet num = new SimpleAttributeSet();
        final SimpleAttributeSet kw = new SimpleAttributeSet();
        final SimpleAttributeSet punct = new SimpleAttributeSet();

        Styles(boolean dark) {
            if (dark) {
                background = new Color(0x1E1E1E);
                defaultFg  = new Color(0xD4D4D4);
                fg(def,    0xD4D4D4);
                fg(key,    0x9CDCFE);
                fg(str,    0xCE9178);
                fg(num,    0xB5CEA8);
                fg(kw,     0x569CD6);
                fg(punct,  0xD4D4D4);
                fg(header, 0x6A9955); StyleConstants.setBold(header, true);
            } else {
                background = Color.WHITE;
                defaultFg  = new Color(0x1E1E1E);
                fg(def,    0x1E1E1E);
                fg(key,    0x0451A5);
                fg(str,    0xA31515);
                fg(num,    0x098658);
                fg(kw,     0x0000FF);
                fg(punct,  0x000000);
                fg(header, 0x008000); StyleConstants.setBold(header, true);
            }
        }

        private static void fg(SimpleAttributeSet a, int rgb) {
            StyleConstants.setForeground(a, new Color(rgb));
        }
    }
}
