package com.example.agent.rootpilot.input

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A test-APK-only, in-memory editor with no send action, network or persistence. */
class InputFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 120, 32, 32)
            addView(TextView(context).apply { text = "RootPilot 输入验收（无发送、无保存）"; textSize = 22f })
            addView(EditText(context).apply {
                id = android.R.id.edit
                hint = "第一个输入框"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
            })
            addView(EditText(context).apply {
                id = android.R.id.text2
                hint = "第二个输入框"
                inputType = InputType.TYPE_CLASS_TEXT
            })
            addView(EditText(context).apply {
                id = android.R.id.input
                hint = "密码框（拒绝自动输入）"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            })
        })
    }
}
