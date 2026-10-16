package app.olauncher

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import app.olauncher.data.Prefs
import app.olauncher.helper.AppLimiter
import org.json.JSONObject

/** Device-side checks using Android's actual JSON parser and SharedPreferences. */
class FeedbackRegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        val prefs = Prefs(targetContext)
        val original = prefs.exportToJson()
        try {
            prefs.textSizeScale = 1.5f
            val valid = prefs.exportToJson()
            val invalid = JSONObject(valid).apply {
                getJSONObject("TEXT_SIZE_SCALE").put("v", "invalid")
            }.toString()
            check(!prefs.importFromJson(invalid)) { "Invalid float accepted" }
            check(JSONObject(prefs.exportToJson()).toString() == JSONObject(valid).toString()) {
                "Invalid import changed existing preferences"
            }
            val unknown = JSONObject(valid).put("BAD_ENTRY", JSONObject().put("t", "unknown").put("v", 1))
            check(!prefs.importFromJson(unknown.toString())) { "Unknown type accepted" }
            check(prefs.textSizeScale == 1.5f)
            prefs.textSizeScale = 1f
            check(prefs.importFromJson(valid))
            check(prefs.textSizeScale == 1.5f) { "Valid backup not restored" }

            val key = "test.limited.app|0"
            prefs.clearLimitState(key)
            prefs.appLimitEnabled = true
            val now = System.currentTimeMillis()
            check(AppLimiter.evaluate(prefs, key, now) == AppLimiter.Decision.Allow)
            val until = now + AppLimiter.durationForStep(prefs.limitLevel(key))
            prefs.setLimitUntil(key, until)
            prefs.setLimitRetryCount(key, 0)
            val first = AppLimiter.evaluate(prefs, key, now + 1) as AppLimiter.Decision.Block
            check(first.untilMillis == until && first.penaltyMinutes == 0)
            val second = AppLimiter.evaluate(prefs, key, now + 2) as AppLimiter.Decision.Block
            check(second.untilMillis == until + 900_000L && second.penaltyMinutes == 15)
            check(AppLimiter.evaluate(prefs, key, second.untilMillis + 1) == AppLimiter.Decision.Allow)
            results.putString("stream", "PASS: invalid import preserves preferences; valid round trip; unknown type rejected; first retry unchanged; second retry +15 minutes; expiry allows launch.\n")
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        } finally {
            check(prefs.importFromJson(original))
        }
    }
}
