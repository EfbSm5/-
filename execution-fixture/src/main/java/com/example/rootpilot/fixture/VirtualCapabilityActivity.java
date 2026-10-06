package com.example.rootpilot.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.UUID;

/** Fixed, offline content for display-targeted input acceptance. */
public final class VirtualCapabilityActivity extends Activity {
    static VirtualCapabilityActivity current;
    private final String instance = UUID.randomUUID().toString();
    private ScrollView scroll;
    private EditText editor;
    private boolean resumed;
    private int backInvoked, enterDown, enterUp;
    private TextView receipt;
    private final OnBackInvokedCallback backCallback = () -> { backInvoked++; renderReceipt(); };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(null);
        current = this;
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
        editor = new EditText(this);
        editor.setId(android.R.id.edit);
        editor.setHint("仅固定 ASCII 测试文本");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setSaveEnabled(false);
        editor.setLongClickable(false);
        content.addView(editor, new LinearLayout.LayoutParams(-1, 180));
        for (int row = 0; row < 30; row++) {
            TextView label = new TextView(this);
            label.setText("固定测试行 " + row);
            label.setTextColor(Color.BLACK);
            label.setTextSize(24);
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
        page.addView(receipt);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        editor.requestFocus();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
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
        receipt.setText("BACK=" + backInvoked + " ENTER=" + enterDown + "/" + enterUp);
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
        result.putBoolean("editorFocused", editor.isFocused());
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
        }
        return result;
    }
}
