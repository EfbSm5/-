package com.example.rootpilot.fixture;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;

/** A separate input target with no network, storage, or user supplied page content. */
public final class ExecutionFixtureActivity extends Activity {
    static final String EXPECTED = "执行模式验收通过";
    // Accessed only on the main thread; cleared when this instance is destroyed.
    static ExecutionFixtureActivity current;
    private LinearLayout page;
    private EditText editor;
    private boolean resumed;
    private Button isolationButton;
    private int isolationClicks;

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        current = this;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 100, 32, 32);
        page.setBackgroundColor(Color.WHITE);
        page.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        page.setSaveEnabled(false);
        TextView title = new TextView(this);
        title.setText("RootPilot 专用执行测试页\n不发送消息、不保存文件");
        title.setTextColor(Color.BLACK);
        title.setTextSize(22);
        page.addView(title);
        editor = new EditText(this);
        editor.setId(android.R.id.edit);
        editor.setHint("第一个输入框（已聚焦）");
        editor.setTextColor(Color.BLACK);
        editor.setHintTextColor(Color.DKGRAY);
        editor.setMinLines(3);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setSaveEnabled(false);
        editor.setLongClickable(false);
        page.addView(editor);
        if (getIntent().getBooleanExtra("rootpilotIsolation", false)) {
            // This mode is only used by the signed, opt-in multi-display acceptance.
            editor.setVisibility(View.GONE);
            View spacer = new View(this);
            page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            isolationButton = new Button(this);
            isolationButton.setText("主屏隔离测试：0");
            isolationButton.setOnClickListener(view -> {
                isolationClicks++;
                isolationButton.setText("主屏隔离测试：" + isolationClicks);
            });
            page.addView(isolationButton, new LinearLayout.LayoutParams(-1, 180));
        }
        setContentView(page);
        editor.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    Bundle snapshot(boolean includeImage) {
        Bundle result = new Bundle();
        boolean ready = resumed && hasWindowFocus() && page.isShown()
                && page.getWidth() > 0 && page.getHeight() > 0 && editor.isFocused();
        result.putBoolean("ready", ready);
        String text = editor.getText().toString();
        result.putBoolean("empty", text.isEmpty());
        result.putBoolean("matches", EXPECTED.equals(text));
        result.putInt("fieldId", editor.getId());
        if (isolationButton != null) {
            Rect bounds = new Rect();
            boolean visible = isolationButton.getLocalVisibleRect(bounds);
            int[] location = new int[2];
            isolationButton.getLocationOnScreen(location);
            bounds.offset(location[0], location[1]);
            result.putBoolean("isolationReady", resumed && visible
                    && getDisplay() != null && getDisplay().getDisplayId() == 0);
            result.putBoolean("isolationFocused", hasWindowFocus());
            result.putInt("isolationClicks", isolationClicks);
            result.putInt("buttonX", bounds.centerX());
            result.putInt("buttonY", bounds.centerY());
        }
        if (!includeImage) return result;
        // Do not transmit arbitrary text introduced by a person, autofill, or another IME.
        if (!ready || (!text.isEmpty() && !EXPECTED.equals(text))) {
            throw new IllegalStateException("fixture_content_not_allowed");
        }
        float scale = Math.min(1f, 1280f / Math.max(page.getWidth(), page.getHeight()));
        int width = Math.max(1, Math.round(page.getWidth() * scale));
        int height = Math.max(1, Math.round(page.getHeight() * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            // Draw only this page's View hierarchy, excluding IME, overlays and system windows.
            page.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    || output.size() > 512 * 1024) {
                throw new IllegalStateException("fixture_image_limit");
            }
            result.putByteArray("png", output.toByteArray());
            result.putInt("width", width);
            result.putInt("height", height);
            return result;
        } finally { bitmap.recycle(); }
    }
}
