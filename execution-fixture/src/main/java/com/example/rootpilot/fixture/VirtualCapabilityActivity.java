package com.example.rootpilot.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.UUID;

/** Fixed, offline content for display-targeted input acceptance. */
public final class VirtualCapabilityActivity extends Activity {
    private static final String UNICODE_SAMPLE = "中文🙂\n第二行";
    static VirtualCapabilityActivity current;
    static Bundle lastReceipt;
    private final String instance = UUID.randomUUID().toString();
    private ScrollView scroll;
    private EditText editor;
    private boolean resumed;
    private int backInvoked, enterDown, enterUp;
    private int probeFrameTick;
    private String preparedInputMode;
    private TextView receipt;
    private TextView firstRow;
    private boolean receiptReady;
    private final class ReceiptEditText extends EditText {
        ReceiptEditText(Activity context) { super(context); }
        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            // SET_SELECTION does not touch the text watcher, so the retained receipt follows the caret here.
            if (receiptReady) recordReceipt();
        }
    }
    private final OnBackInvokedCallback backCallback = () -> { backInvoked++; renderReceipt(); };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(null);
        current = this;
        lastReceipt = null;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setSaveEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 100, 32, 32);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        TextView title = new TextView(this);
        title.setText("RootPilot 副屏能力测试（离线）");
        title.setTextColor(Color.BLACK);
        title.setTextSize(22);
        content.addView(title);
        editor = new ReceiptEditText(this);
        editor.setId(android.R.id.edit);
        editor.setHint("");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setSaveEnabled(false);
        editor.setLongClickable(false);
        editor.setSelection(0);
        for (int row = 0; row < 30; row++) {
            TextView label = new TextView(this);
            label.setText("固定测试行 " + row);
            label.setTextColor(Color.BLACK);
            label.setTextSize(24);
            if (row == 0) firstRow = label;
            content.addView(label, new LinearLayout.LayoutParams(-1, 180));
        }
        scroll.addView(content);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        receipt = new TextView(this);
        receipt.setTextColor(Color.BLACK);
        receipt.setTextSize(22);
        renderReceipt();
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { recordReceipt(); }
        });
        page.addView(receipt);
        page.addView(editor, new LinearLayout.LayoutParams(-1, 180));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> recordReceipt());
        preparedInputMode = PreparedVirtualInput.consume(getDisplay());
        if ("CURSOR".equals(preparedInputMode)) {
            editor.setText("甲乙");
            editor.setSelection(1);
        } else if ("SELECTION".equals(preparedInputMode)) {
            editor.setText("甲乙丙");
            editor.setSelection(1, 2);
        }
        editor.requestFocus();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        // The seed text and selection above happen before the guard opens, so the receipt
        // captured by the text watcher still shows the transient (0,0) selection. Record once
        // more so the retained receipt describes the settled seeded state.
        receiptReady = true;
        recordReceipt();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Count delivery without triggering navigation or editing side effects.
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                enterDown++;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                enterUp++;
            }
            renderReceipt();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void renderReceipt() {
        receipt.setText("BACK=" + backInvoked + " ENTER=" + enterDown + "/" + enterUp
                + (probeFrameTick == 0 ? "" : " FRAME=" + probeFrameTick));
        recordReceipt();
    }

    // Retain only fixed outcomes from this instance, so release cannot race test readback.
    private void recordReceipt() {
        Bundle result = new Bundle();
        result.putString("instance", instance);
        result.putInt("displayId", getDisplay() == null ? -1 : getDisplay().getDisplayId());
        result.putInt("scrollY", scroll.getScrollY());
        result.putBoolean("empty", editor.getText().length() == 0);
        result.putBoolean("asciiMatches", "RootPilot42".contentEquals(editor.getText()));
        result.putBoolean("unicodeMatches", UNICODE_SAMPLE.contentEquals(editor.getText()));
        result.putBoolean("cursorSampleMatches", "甲🙂乙".contentEquals(editor.getText()));
        result.putBoolean("selectionSampleMatches", ("甲" + UNICODE_SAMPLE + "丙").contentEquals(editor.getText()));
        result.putBoolean("cursorSeedMatches", "甲乙".contentEquals(editor.getText()));
        result.putBoolean("changedCursorSeedMatches", "甲丁".contentEquals(editor.getText()));
        result.putBoolean("selectionSeedMatches", "甲乙丙".contentEquals(editor.getText()));
        result.putInt("selectionStart", editor.getSelectionStart());
        result.putInt("selectionEnd", editor.getSelectionEnd());
        result.putString("preparedInputMode", preparedInputMode);
        result.putInt("backInvoked", backInvoked);
        result.putInt("enterDown", enterDown);
        result.putInt("enterUp", enterUp);
        lastReceipt = result;
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (current == this) current = null;
        super.onDestroy();
    }

    Bundle state() {
        Bundle result = new Bundle();
        result.putString("instance", instance);
        result.putInt("displayId", getDisplay() == null ? -1 : getDisplay().getDisplayId());
        result.putBoolean("ready", resumed && hasWindowFocus() && scroll.isShown() && scroll.getHeight() > 0);
        result.putInt("scrollY", scroll.getScrollY());
        result.putBoolean("empty", editor.getText().length() == 0);
        result.putBoolean("asciiMatches", "RootPilot42".contentEquals(editor.getText()));
        result.putBoolean("unicodeMatches", UNICODE_SAMPLE.contentEquals(editor.getText()));
        result.putBoolean("cursorSampleMatches", "甲🙂乙".contentEquals(editor.getText()));
        result.putBoolean("selectionSampleMatches", ("甲" + UNICODE_SAMPLE + "丙").contentEquals(editor.getText()));
        result.putBoolean("cursorSeedMatches", "甲乙".contentEquals(editor.getText()));
        result.putBoolean("changedCursorSeedMatches", "甲丁".contentEquals(editor.getText()));
        result.putBoolean("selectionSeedMatches", "甲乙丙".contentEquals(editor.getText()));
        result.putInt("selectionStart", editor.getSelectionStart());
        result.putInt("selectionEnd", editor.getSelectionEnd());
        result.putInt("viewWidth", getWindow().getDecorView().getWidth());
        result.putInt("viewHeight", getWindow().getDecorView().getHeight());
        result.putInt("rotation", getDisplay() == null ? -1 : getDisplay().getRotation());
        result.putInt("probeFrameTick", probeFrameTick);
        result.putBoolean("editorFocused", editor.isFocused());
        result.putString("preparedInputMode", preparedInputMode);
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        result.putBoolean("imeInsetsAvailable", insets != null);
        if (insets != null) result.putBoolean("imeInsetsVisible", insets.isVisible(WindowInsets.Type.ime()));
        result.putInt("backInvoked", backInvoked);
        result.putInt("enterDown", enterDown); result.putInt("enterUp", enterUp);
        Rect bounds = new Rect();
        int[] location = new int[2];
        if (scroll.getLocalVisibleRect(bounds)) {
            scroll.getLocationOnScreen(location);
            bounds.offset(location[0], location[1]);
            result.putInt("x", bounds.centerX());
            result.putInt("fromY", bounds.top + bounds.height() * 3 / 4);
            result.putInt("toY", bounds.top + bounds.height() / 4);
            Rect rowBounds = new Rect();
            if (firstRow.getLocalVisibleRect(rowBounds)) {
                firstRow.getLocationOnScreen(location);
                rowBounds.offset(location[0], location[1]);
                result.putInt("rowsTop", Math.max(bounds.top, rowBounds.top));
                result.putInt("rowsBottom", bounds.bottom);
            }
        }
        return result;
    }

    Bundle probe(String command, String expectedInstance, int expectedDisplay) {
        if (!instance.equals(expectedInstance) || expectedDisplay <= 0 || getDisplay() == null
                || getDisplay().getDisplayId() != expectedDisplay || !resumed || !hasWindowFocus()
                || !editor.isShown() || !editor.isFocused()) {
            throw new IllegalStateException("virtual_probe_target_unavailable");
        }
        if ("virtual_redraw".equals(command)) {
            if (probeFrameTick >= 3) throw new IllegalStateException("virtual_probe_redraw_budget");
            probeFrameTick++;
            renderReceipt();
            return state();
        }
        if ("virtual_change_cursor_source".equals(command)) {
            if (!"甲乙".contentEquals(editor.getText()) || editor.getSelectionStart() != 1 || editor.getSelectionEnd() != 1) {
                throw new IllegalStateException("virtual_probe_source_changed");
            }
            editor.setText("甲丁");
            editor.setSelection(1);
            return state();
        }
        if ("virtual_seed_cursor".equals(command) || "virtual_seed_selection".equals(command)) {
            if (editor.getText().length() != 0) throw new IllegalStateException("virtual_probe_editor_not_empty");
            if ("virtual_seed_cursor".equals(command)) {
                editor.setText("甲乙");
                editor.setSelection(1);
            } else {
                editor.setText("甲乙丙");
                editor.setSelection(1, 2);
            }
            return state();
        }
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        Bundle result = new Bundle();
        if ("virtual_show_keyboard".equals(command)) {
            result.putBoolean("requestAccepted", manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
        } else if ("virtual_hide_keyboard".equals(command)) {
            result.putBoolean("requestAccepted", manager.hideSoftInputFromWindow(editor.getWindowToken(), 0));
        } else {
            throw new IllegalArgumentException("virtual_probe_command_not_allowed");
        }
        result.putString("instance", instance);
        result.putInt("displayId", getDisplay().getDisplayId());
        return result;
    }
}
