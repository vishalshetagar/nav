package com.example.nav

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*

/** Runs in the test APK's separate package so NaV observes it like an ordinary app. */
class TargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 60, 20, 20) }
        val status = TextView(this).apply { text = "Fixture ready" }
        column.addView(status)
        column.addView(LinearLayout(this).apply {
            isClickable = true
            addView(TextView(this@TargetActivity).apply { text = "Nested control label" })
            setOnClickListener { status.text = "Nested selected" }
        })
        column.addView(Button(this).apply { text = "Details"; setOnClickListener { status.text = "First selected" } })
        column.addView(Button(this).apply { text = "Details"; setOnClickListener { status.text = "Second selected" } })
        column.addView(Button(this).apply { text = "Disabled control"; isEnabled = false })
        column.addView(EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText("super-secret") })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(60) { index -> list.addView(TextView(this).apply { text = "Row $index"; textSize = 24f; setPadding(4, 16, 4, 16) }) }
        column.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
    }
}
