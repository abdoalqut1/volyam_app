package com.example.volkeys

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        l.addView(TextView(this).apply {
            text = "فعّل خدمة Volume Keys من إعدادات الإتاحة، وبعدها اقفل الشاشة وجرب:\n" +
                "• ضغطتين رفع الصوت = التالي\n• ضغطتين خفض الصوت = السابق\n• الاتنين مع بعض = إيقاف/تشغيل"
            textSize = 18f
        })
        l.addView(Button(this).apply {
            text = "افتح إعدادات الإتاحة"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(l)
    }
}
