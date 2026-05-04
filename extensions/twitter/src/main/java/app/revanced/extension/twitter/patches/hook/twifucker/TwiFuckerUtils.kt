package app.revanced.extension.twitter.patches.hook.twifucker

import org.json.JSONArray
import org.json.JSONObject

internal object TwiFuckerUtils {
    inline fun JSONArray.forEach(action: (JSONObject) -> Unit) {
        var i = 0
        val len = this.length()
        while (i < len) {
            val item = this.opt(i)
            if (item is JSONObject) {
                action(item)
            }
            i++
        }
    }

    inline fun JSONArray.forEachIndexed(action: (index: Int, JSONObject) -> Unit) {
        var i = 0
        val len = this.length()
        while (i < len) {
            val item = this.opt(i)
            if (item is JSONObject) {
                action(i, item)
            }
            i++
        }
    }
}
