package app.conversate.glass.meeting

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** Explicit adb diagnostics only. Does not request audio or generation. */
class NativeCxrProbeActivity : Activity() {
    private lateinit var probe: NativeCxrProbe
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        probe = NativeCxrProbe(this)
        val result = probe.connect { Log.i("CxrProbe", "received result bytes=${it.length}") }
        Log.i("CxrProbe", result.toString())
        setContentView(TextView(this).apply { text = result.toString(2); setTextColor(android.graphics.Color.GREEN) })
    }
    override fun onDestroy() { probe.close(); super.onDestroy() }
}
