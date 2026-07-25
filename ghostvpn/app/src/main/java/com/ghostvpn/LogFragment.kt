package com.ghostvpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

class LogFragment : Fragment() {
    lateinit var tvLog: TextView
    lateinit var scrollView: ScrollView
    private var logCallback: LogCallback? = null

    interface LogCallback {
        fun onClearLog()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_log, container, false)
        tvLog = v.findViewById(R.id.tvLog)
        scrollView = v.findViewById(R.id.scrollView)
        v.findViewById<Button>(R.id.btnClearLog).setOnClickListener { tvLog.text = ""; logCallback?.onClearLog() }
        return v
    }

    fun appendLog(msg: String) {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    fun setCallback(cb: LogCallback) { logCallback = cb }
}
