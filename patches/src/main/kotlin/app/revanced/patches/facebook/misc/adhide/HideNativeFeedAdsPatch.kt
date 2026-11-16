package app.revanced.patches.facebook.misc.adhide

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.util.Base64
import java.util.logging.Logger

/*
 * Hide sponsored posts in the native feed on android 8 apk app
 *
 * ONE self-contained file. It adds NO file to the apk (no asset, no resource, no manifest change, no library). It adds
 * three small classes (Entry, Core, Rec) to the dex the app already has, and one call at the top of the Application.onCreate()
 * that already exists. Nothing of Facebook's own code is read, renamed or changed, so it works on the obfuscated and on the
 * Dextricks builds alike (Android 8: 490 / 521 / 585, Android 11: the same builds).
 *
 * Why it works at run time and not on Facebook's code: the News Feed is NATIVE (Litho views in a list, not a web page) and on
 * Android 8 almost all of the app's code is invisible to the patcher. What is always there is what the app DRAWS. The probe
 * patch showed that a Sponsored post draws its label ("Được tài trợ", "Sponsored" ...) as a separate string at the same place
 * of the post header (about 13% of the row width below the top, x = 170 on a 1080 px screen) and has a "Ẩn quảng cáo" (Hide ad)
 * button in the header. This patch reads exactly that, on the rows that are on the screen.
 *
 * What it does, before every frame is drawn (ViewTreeObserver.OnPreDrawListener, UI thread, nothing else runs):
 *   1. finds the list(s) of the screen that are really on it (id android:id/list with row views, or a list whose first two
 *      rows are LithoViews); the other tabs the pager keeps to the right are skipped;
 *   2. for every row looks at its views. A row is only looked at (and only ever drawn or touched) when it has the feed header
 *      buttons: a content description "Hide ad" / "Ẩn quảng cáo" (= an ad), or "Hide post" / "Ẩn bài viết" and no video surface.
 *      Rows without them (Reels, Page / profile screens, menus, comments, anything that is not a feed post) are NEVER drawn,
 *      measured or changed by this patch;
 *   3. if the header of the row is fully on the screen, the row is drawn ONCE into a small throw-away bitmap through a Canvas
 *      subclass (Rec) that records the strings drawn between 9% and 25% of the row width from the top. A string that IS one
 *      of the Sponsored labels (whole string, lower case, Unicode composed, no-break spaces dropped) means an ad;
 *   4. an ad row is made INVISIBLE and, with the option "Collapse space", its height is taken out of the layout (bottom margin
 *      = - height) so there is no gap. The list is then laid out again at once, INSIDE the frame that is being drawn (option
 *      "Smooth hiding"), so the frame is not dropped. A row that shows header text but no label is an ordinary post and is left
 *      alone (believed at the second look, in a later frame: while scrolling fast Litho may still show the previous frame's content);
 *   5. a row that has not drawn its header yet is kept transparent for at most 300 ms (it is still laid out and drawn, so the
 *      header does get drawn), then shown;
 *   6. when RecyclerView recycles a row view (it leaves the window) the row is given back untouched (visible, own margin).
 *   7. when a row is hidden, Litho is told that the row is "not visible" (LithoView.setVisibilityHint(false), found by its shape
 *      through reflection: the only public, non-static void (boolean, boolean) method that LithoView declares itself; its name is obfuscated
 *      and different in every build). Without it the app does not know that the row was hidden and its video can keep playing
 *      or sounding for a second under the invisible row. It is told "visible" again when the row is shown. If the method is not
 *      found, or is found twice, or throws, this step is skipped for good (nothing else changes). The log (tap on the badge)
 *      says "HINT method ..." or "HINT none".
 *
 * Costs: the tree walk is a few dozen views per frame; a row is drawn into the bitmap once or twice per judgement (not every
 * frame; a row cut by the edge of the list at most every 80 ms);
 * one ALPHA_8 bitmap of  width x 40% width  is kept. When a row is hidden the list is measured / laid out once more, with its own
 * size, before the frame is drawn (the same work the parent would do one frame later). If that call ever throws, it is switched
 * off for good and the old way is used: the frame is cancelled and redone (at most 3 times in a row).
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. If drawing a row fails 6 times the patch switches itself off and gives every row back.
 * Rows shorter than 40% of their width (menu entries, small cards) are never taken for ads by their label.
 *
 * What else is hidden (labels compared in the same way, each group can be switched off):
 *   - "Suggested for you" posts (option "Hide suggested posts"): the label "Gợi ý cho bạn" / "Suggested for you" is drawn in the
 *     same place as "Sponsored" (seen in a real probe: name at y=82, label at y=139, then "·", "8 giờ", "·").
 *   - Paid partnership posts (option "Hide paid partnership posts"): a line such as "Mối quan hệ tài trợ ..." / "Paid partnership
 *     with ..." under the page name. These are PREFIX labels (a label that ends with a star): the drawn text only has to START with
 *     it, because the brand name follows. Only the position (header band) is assumed, it has not been seen on a real probe yet.
 *
 * What you see: a small dark button "RV hider: N hidden" at the bottom left (option "Show counter badge"). Tap it: the
 * log (what was hidden and why, the texts of the header band of the posts that were left alone, which list class) is copied to the
 * clipboard so it can be pasted into a message.
 *
 * Limits (honest):
 *   - Stories / story ads are NOT handled here (see the patch "Hide story ads for android 8 app").
 *   - A Suggested / paid partnership post that has a video is not judged by its label (a video surface is in the row), so it is left.
 *   - An ad row that has neither of the header buttons (no "Hide ad" and no "Hide post") is left alone: the label alone is not
 *     enough to touch a row any more.
 *   - Scrolling UP: an ad row comes in from the top with its header outside the list, so it cannot be recognised until the
 *     header is on the screen; the lower part of that ad is visible until then.
 *   - The first frames of a row that enters from the bottom edge can show the top of an ad for a few frames before the label
 *     is on the screen.
 *   - Hiding by negative bottom margin needs a list whose rows have margin LayoutParams (RecyclerView); on a ListView the rows
 *     are only made invisible (blank gap).
 *   - Not run on a real device by the author when this file was written: it was checked with a verifier of the register / type
 *     rules and with a simulation of the feed that runs this very smali; see the README.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * The smali of the three classes is in the zip next to this file (source folder); the Base64 below is exactly its assembly.
 */

private const val ENTRY = "Lapp/revanced/extension/facebook/adhide/Entry;"
private const val CORE = "Lapp/revanced/extension/facebook/adhide/Core;"
private const val APP_CLASS = "Lcom/facebook/katana/app/FacebookApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"

/** "Sponsored" in every language, lower case, Unicode composed, joined by a vertical bar. Compared with the WHOLE drawn string. */
private const val LABELS_SPONSORED = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告"

/** "Suggested for you" in many languages. Same rules. Added when the option "Hide suggested posts" is on. */
private const val LABELS_SUGGESTED = "suggested for you|recommended for you|مقترح لك|مقترحات لك|предложения за вас|navrhované pro vás|vorschläge für dich|für dich empfohlen|προτεινόμενα για εσάς|sugerencias para ti|sugerido para ti|ehdotuksia sinulle|suggestion pour vous|suggestions pour vous|הצעות עבורך|disarankan untuk anda|contenuti suggeriti per te|suggerimenti per te|おすすめ|ieteikumi jums|voorgesteld voor jou|proponowane dla ciebie|sugestões para ti|sugestões para você|рекомендуем вам|предлагаемое для вас|senin için önerilenler|рекомендовано для вас|gợi ý cho bạn|đề xuất cho bạn|为你推荐|推荐给你|為你推薦|推薦給你"

/** "Paid partnership ..." labels: a star at the end means that the drawn text only has to START with it. Added when the option
 *  "Hide paid partnership posts" is on. */
private const val LABELS_PARTNERSHIP = "paid partnership*|mối quan hệ tài trợ*|mối quan hệ đối tác trả phí*|quan hệ đối tác trả phí*|hợp tác trả phí*|colaboración pagada*|colaboración de pago*|partenariat rémunéré*|bezahlte partnerschaft*|parceria paga*|parceria paga com*|partnership a pagamento*|collaborazione a pagamento*|kemitraan berbayar*"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val ADHIDE_DEX = listOf(
    "ZGV4CjAzOADL/OWjejR05Oo6RnThbjzBv+QAGhLREVKkPwAAcAAAAHhWNBIAAAAAAAAAAAQ/AAA/AQAAcAAAAEIAAABsBQAAZQAA",
    "AHQGAAAmAAAAMAsAAKEAAABgDAAAAwAAAGgRAADcLQAAyBEAAMgRAADKEQAAzhEAANoRAADhEQAA6xEAAPURAAD5EQAAChIAACcS",
    "AAAzEgAAQBIAAEoSAABSEgAAWhIAAGYSAABrEgAAdBIAAHoSAACoEgAArxIAALYSAAC8EgAAxBIAAMcSAADQEgAA0xIAANcSAADi",
    "EgAA6BIAAPQSAAD3EgAA/hIAACYTAAAsEwAANxMAAEUTAABdEwAAYBMAAGUTAABoEwAAaxMAAG8TAAB0EwAAeBMAAH0TAACDEwAA",
    "ihMAAI4TAACSEwAAlxMAAJ0TAACiEwAAqBMAAKwTAADEEwAA+hMAABUUAAAxFAAAVRQAAHAUAACSFAAAtxQAANkUAAD0FAAADxUA",
    "ACoVAABEFQAAXRUAAHcVAACTFQAApxUAALwVAADSFQAA5xUAAAEWAAAdFgAAORYAAGoWAACPFgAApBYAAMsWAAD4FgAAEhcAAC0X",
    "AABgFwAAgRcAAJgXAADDFwAA3hcAAPYXAAAlGAAAVRgAAIMYAACYGAAAshgAAMUYAADZGAAA7xgAAAMZAAAeGQAAMhkAAEkZAABb",
    "GQAAdxkAAJQZAACsGQAAwBkAAMcZAADMGQAA0BkAANsZAADkGQAA7RkAAPMZAAD/GQAAERoAABsaAAAkGgAAKhoAAC0aAAAxGgAA",
    "NRoAADoaAABAGgAARxoAAEwaAABQGgAAVhoAAF0aAABiGgAAaxoAAHcaAAB8GgAAghoAAIkaAACOGgAAkRoAAJUaAACaGgAAnhoA",
    "AKIaAACmGgAAuhoAAM8aAADkGgAAARsAABMbAAAXGwAAJhsAACsbAABLGwAAYRsAAGobAACXGwAAnxsAAKcbAACuGwAAtxsAAMIb",
    "AADIGwAAzRsAANsbAADjGwAA7RsAAPYbAAD+GwAABRwAABAcAAAaHAAALxwAAE0cAABTHAAAYRwAAGocAAB9HAAAhRwAAIwcAACS",
    "HAAAnhwAAKgcAAC1HAAAvRwAAMQcAADSHAAA4xwAAOocAADzHAAA9xwAAAIdAAAOHQAAHR0AACcdAAA+HQAAUh0AAGUdAABzHQAA",
    "fh0AAIUdAACWHQAAnx0AALQdAADDHQAAzh0AAOEdAADsHQAA+h0AAAMeAAATHgAAJh4AADYeAABJHgAAVB4AAGMeAABtHgAAfB4A",
    "AI4eAACWHgAAnh4AALMeAADCHgAAzB4AANceAADgHgAA6h4AAO4eAAD2HgAA/B4AAAUfAAAQHwAAFh8AAB8fAAApHwAAMB8AADUf",
    "AAA+HwAARx8AAFMfAABbHwAAZB8AAG0fAAB5HwAAgR8AAIofAACSHwAAnB8AAKQfAACsHwAAth8AALofAAC/HwAAxx8AANEfAADc",
    "HwAA5R8AAPMfAAD6HwAAACAAAAggAAATIAAAGCAAACsgAABAIAAAUiAAAGUgAACCIAAAlSAAAKggAACxIAAAvCAAANQgAADuIAAA",
    "9CAAAP0gAAAKIQAALiEAADghAABJIQAAYiEAAGohAABzIQAAeyEAAIYhAACPIQAAlCEAAJ4hAACkIQAAriEAAMIhAADTIQAA5yEA",
    "APMhAAADIgAACyIAABQiAAAiIgAALyIAAD4iAABEIgAASSIAAFEiAABYIgAAXyIAAGsiAABzIgAAgCIAAIsiAACYIgAAoiIAAKki",
    "AACvIgAAtCIAAMIiAADLIgAA1CIAAOEiAADnIgAA7SIAAP8iAAAZAAAAHgAAACUAAAAnAAAANgAAADcAAAA4AAAAOQAAADoAAAA7",
    "AAAAPAAAAD0AAAA+AAAAPwAAAEAAAABBAAAAQgAAAEMAAABEAAAARQAAAEYAAABHAAAASAAAAEkAAABKAAAASwAAAEwAAABNAAAA",
    "TgAAAE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUAAABWAAAAVwAAAFgAAABZAAAAWgAAAFsAAABcAAAAXQAAAF4AAABfAAAAYAAA",
    "AGEAAABiAAAAYwAAAGQAAABlAAAAZgAAAGcAAABoAAAAaQAAAGoAAAB3AAAAiAAAAIsAAACMAAAAjQAAAI4AAACPAAAAkAAAAJEA",
    "AAAaAAAAAAAAAOQkAAAlAAAAAgAAAAAAAAAmAAAAAgAAABwkAAAnAAAAAwAAAAAAAAAzAAAABwAAAMgjAAAxAAAACgAAACAjAAAo",
    "AAAACwAAAAAAAAAtAAAADQAAACglAAAoAAAADwAAAAAAAAAoAAAAFwAAAAAAAAAoAAAAHQAAAAAAAAArAAAAHQAAAOQkAAAwAAAA",
    "HQAAADAkAAAoAAAAHgAAAAAAAAAoAAAAIQAAAAAAAAAoAAAAIwAAAAAAAAAoAAAAJAAAAAAAAAA0AAAAJwAAABQjAAA1AAAAKwAA",
    "AFglAAAoAAAALAAAAAAAAAAsAAAALAAAALAkAAAoAAAALQAAAAAAAAAwAAAALQAAAFwjAAArAAAALgAAAOQkAAAzAAAALgAAACAl",
    "AAAwAAAALgAAAFwjAAAoAAAAMAAAAAAAAAAqAAAAMAAAAKgjAAAsAAAAMAAAALAkAAAwAAAAMAAAAKAjAAAwAAAAMAAAANgjAAAz",
    "AAAAMAAAALAjAAAwAAAAMAAAAGAlAAAzAAAAMAAAAGQjAAAwAAAAMAAAADAkAAAwAAAAMAAAAFwjAAAwAAAAMAAAALgjAAAyAAAA",
    "MAAAABQlAAApAAAAMQAAALgkAAArAAAAMQAAAOQkAAAsAAAAMQAAALAkAAAvAAAAMQAAAOwkAAAwAAAAMQAAAGAlAAAwAAAAMQAA",
    "AFwjAAA1AAAAMQAAAFglAAAzAAAANQAAANwkAAB3AAAAOQAAAAAAAAB4AAAAOQAAAMwkAAB5AAAAOQAAAOQkAAB6AAAAOQAAALAk",
    "AAB7AAAAOQAAAEAjAAB8AAAAOQAAADQlAAB9AAAAOQAAAEAkAAB+AAAAOQAAAKAjAACEAAAAOQAAAAwlAACEAAAAOQAAAHwkAAB+",
    "AAAAOQAAANAjAAB+AAAAOQAAAFQjAAB+AAAAOQAAAEAlAAB+AAAAOQAAANgjAAB+AAAAOQAAAFAlAACAAAAAOQAAAHwjAACFAAAA",
    "OQAAAOgjAACGAAAAOQAAAMAkAACGAAAAOQAAAIQkAAB+AAAAOQAAAJgkAAB+AAAAOQAAAJgjAAB+AAAAOQAAAPwkAAB+AAAAOQAA",
    "AEgkAAB+AAAAOQAAAEwjAACBAAAAOQAAABwkAACEAAAAOQAAAGgkAACHAAAAOQAAAPQjAACEAAAAOQAAADgkAAB+AAAAOQAAAOAj",
    "AAB+AAAAOQAAANQkAACEAAAAOQAAAJAjAAB+AAAAOQAAAMAjAAB+AAAAOQAAAGAlAAB/AAAAOQAAAHAkAACCAAAAOQAAAKAkAACD",
    "AAAAOQAAAFAkAAB+AAAAOQAAADAkAAB+AAAAOQAAAJAkAACAAAAAOQAAACQkAACCAAAAOQAAAPwjAACEAAAAOQAAAEglAACCAAAA",
    "OQAAAGwjAACDAAAAOQAAACgjAAB+AAAAOQAAAPQkAAB+AAAAOQAAAAQlAACIAAAAOgAAAAAAAACJAAAAOgAAAEwjAACJAAAAOgAA",
    "ANQkAACJAAAAOgAAAGAlAACJAAAAOgAAADAkAACKAAAAOgAAAIgjAACJAAAAOgAAAFwjAAAoAAAAPgAAAAAAAAAwAAAAQAAAAFwj",
    "AAAoAAAAQQAAAAAAAAAKADAAOgEAAAwADAAYAAAAEwAwAG8AAAATAAIAdQAAABQAMABrAAAAHwACAKEAAAAoACYAnAAAACgAMACf",
    "AAAAKAANAKAAAAAoADoApAAAACgABACtAAAAKAAhALAAAAAoAAIAtgAAACgAFgDeAAAAKAACAOEAAAAoADoA5gAAACgAOgDnAAAA",
    "KAA1AOgAAAAoAB0A9AAAACgAMQD5AAAAKAA6AP8AAAAoADoAAwEAACgAAgAQAQAAKAA6ABkBAAAoAAIALwEAACgAAgA8AQAAKgAw",
    "ALkAAAAqAAIAuwAAACoAAgDgAAAAKgACAOkAAAAqAAIA+AAAACoAAgAdAQAAKgAxADYBAAArACsAHwAAACsALQB2AAAANAAtAHYA",
    "AAA2ADYAbAAAADgAOABxAAAABAALALcAAAAEABAA3QAAAAYAOAASAQAABwAEAP4AAAAIADoAIwEAAAkABgDQAAAACQAaANEAAAAJ",
    "ABkA1wAAAAsABQDPAAAADQAHAKwAAAANAAEA3AAAAA4APAAWAAAADgAIAMoAAAAPAFkA/AAAABYAQgAWAAAAFgBgABEBAAAWAFMA",
    "FAEAABcACQDJAAAAGAADADcBAAAdAEMAlgAAAB0AQQCxAAAAHQAMALgAAAAdAAEAvAAAAB0AEwDAAAAAHQABAMQAAAAdAAEAxQAA",
    "AB0ADQDGAAAAHQABAMcAAAAdAFoAyAAAAB0AAQDLAAAAHQAOANMAAAAdAAEA1QAAAB0AFwDYAAAAHQABANkAAAAdAA8A2gAAAB0A",
    "AQDbAAAAHQABANwAAAAdAC4A7AAAAB0AMwD1AAAAHQAxAP0AAAAdAC8AHgEAAB0AMAAfAQAAHQBKACABAAAdAEQAIQEAAB0AMwAi",
    "AQAAHQA0ACQBAAAdAFIAJAEAAB0AMAAoAQAAIABHAJgAAAAgAAsAvQAAACAAAQC+AAAAIAABAMQAAAAjAE0AlwAAACMAWwDuAAAA",
    "IwBNABUBAAAkAAoAwwAAACUAMgAWAAAAJgA7ABYAAAAmAE4AJQEAACYAMAAmAQAAJgAvACcBAAAnABEA+wAAACcALgApAQAAKAAu",
    "ABYAAAAoADUAmwAAACgAWwCdAAAAKAAuAJ4AAAAoAF4AogAAACgAXgCjAAAAKAAgAKYAAAAoAFsAqAAAACgANwCrAAAAKABJAOIA",
    "AAAoAEgA5QAAACgAAgDqAAAAKABhAO8AAAAoAF0A8QAAACgAXADyAAAAKAAaAPMAAAAoAEsA9wAAACgAVgD6AAAAKAAjAAABAAAo",
    "AEkAAQEAACgANgAEAQAAKAA1AAUBAAAoADUABgEAACgANQAHAQAAKAA2AAgBAAAoADUACQEAACgANQAKAQAAKABFAAsBAAAoAFsA",
    "DAEAACgARQANAQAAKABFAA4BAAAoAEkADwEAACgAWwATAQAAKAAfABYBAAAoAB0AGAEAACgATAAaAQAAKAAuABsBAAAoAEkAKQEA",
    "ACgASQAqAQAAKABbACsBAAAoADkALQEAACgANwA0AQAAKAAeADkBAAAoAEYAOwEAACkAOQDrAAAAKgA8ABYAAAAqAE8AlQAAACoA",
    "PQCyAAAAKgA+ALIAAAAqAD8AsgAAACoAQACyAAAAKgBQALMAAAAqAFQAswAAACoAVQCzAAAAKgBXALMAAAAqAFEAtAAAACoAWAC0",
    "AAAAKwASADgBAAAsABQAMAEAAC0AFgC6AAAALQBkAMEAAAAtAC0AzAAAAC0AGgDOAAAALQAVANYAAAAtAF8A8AAAAC4ALgAWAAAA",
    "LgAVAL8AAAAwAAAApQAAADAAXwC1AAAAMAABAN8AAAAwAAEA9gAAADAAGwAXAQAAMABjACwBAAAwAGEALgEAADAAHAAxAQAAMAAk",
    "ADIBAAAwABoANQEAADAAIgA4AQAAMAAlADgBAAAxAC4AFgAAADEAJgCaAAAAMQAnAJoAAAAxACkAmgAAADEAKgCaAAAAMQArAJoA",
    "AAAxACwAmgAAADEAKACvAAAAMQABAPYAAAAxABoAMwEAADIAAwCuAAAAMwAaADMBAAA1ABUAwgAAADUAAQDNAAAANQAaAM4AAAA1",
    "AGIA0gAAADUAFQDUAAAANQAYAO0AAAA3ACEAAgEAACgAAAARAAAALgAAAAwkAAAbAAAAAAAAANc9AAAAAAAAKQAAABEAAAAuAAAA",
    "AAAAAB0AAAAAAAAAtT4AAAAAAAAqAAAAEQAAAA4AAAAAAAAAdAAAAAAAAAC9PgAAAAAAAAAAAgoKAAoKYWN0aXZpdHk9AAUKYXBw",
    "PQAICmRldmljZT0ACApoaWRkZW49AAIgIAAPICBBRCBieSBsYWJlbCAnABsgIEFEIGJ5IHRoZSBoaWRlLWFkIGJ1dHRvbgoACiAg",
    "YW5kcm9pZD0ACyAgY29sbGFwc2U9AAggIGZhaWxzPQAGICBzZGs9AAYgYmFuZD0ACiBjaGlsZHJlbj0AAyBoPQAHIGhpZGRlbgAE",
    "IGxwPQAsIHJvdyhzKSBoaWRkZW4uIExvZyBjb3BpZWQgdG8gdGhlIGNsaXBib2FyZC4ABSB0b3A9AAUgdmlzPQAEJyB5PQAGPGlu",
    "aXQ+AAE/AAdBTFBIQV84AAFDAAJDSQAJQ29yZS5qYXZhAARFWEMgAApFbnRyeS5qYXZhAAFGAAVGQUxTRQAmR0lWRSBVUCAobm8g",
    "dGV4dCBpbiB0aGUgaGVhZGVyKSwgc2hvd24ABEhJREUACUhJTlQgZXhjIAAMSElOVCBtZXRob2QgABZISU5UIG5vbmUsIGNhbmRp",
    "ZGF0ZXM9AAFJAANJTEkAAUoAAUwAAkxDAANMQ0MAAkxJAANMSUkABExJSUwABUxJU1QgAAJMSgACTEwAA0xMSQAETExJSQADTExM",
    "AARMTExJAAJMWgAWTGFuZHJvaWQvYXBwL0FjdGl2aXR5OwA0TGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uJEFjdGl2aXR5TGlmZWN5",
    "Y2xlQ2FsbGJhY2tzOwAZTGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uOwAaTGFuZHJvaWQvY29udGVudC9DbGlwRGF0YTsAIkxhbmRy",
    "b2lkL2NvbnRlbnQvQ2xpcGJvYXJkTWFuYWdlcjsAGUxhbmRyb2lkL2NvbnRlbnQvQ29udGV4dDsAIExhbmRyb2lkL2NvbnRlbnQv",
    "cG0vUGFja2FnZUluZm87ACNMYW5kcm9pZC9jb250ZW50L3BtL1BhY2thZ2VNYW5hZ2VyOwAgTGFuZHJvaWQvZ3JhcGhpY3MvQml0",
    "bWFwJENvbmZpZzsAGUxhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcDsAGUxhbmRyb2lkL2dyYXBoaWNzL0NhbnZhczsAGUxhbmRyb2lk",
    "L2dyYXBoaWNzL01hdHJpeDsAGExhbmRyb2lkL2dyYXBoaWNzL1BhaW50OwAXTGFuZHJvaWQvZ3JhcGhpY3MvUmVjdDsAGExhbmRy",
    "b2lkL2dyYXBoaWNzL1JlY3RGOwAaTGFuZHJvaWQvb3MvQnVpbGQkVkVSU0lPTjsAEkxhbmRyb2lkL29zL0J1aWxkOwATTGFuZHJv",
    "aWQvb3MvQnVuZGxlOwAUTGFuZHJvaWQvb3MvSGFuZGxlcjsAE0xhbmRyb2lkL29zL0xvb3BlcjsAGExhbmRyb2lkL29zL1N5c3Rl",
    "bUNsb2NrOwAaTGFuZHJvaWQvdmlldy9TdXJmYWNlVmlldzsAGkxhbmRyb2lkL3ZpZXcvVGV4dHVyZVZpZXc7AC9MYW5kcm9pZC92",
    "aWV3L1ZpZXckT25BdHRhY2hTdGF0ZUNoYW5nZUxpc3RlbmVyOwAjTGFuZHJvaWQvdmlldy9WaWV3JE9uQ2xpY2tMaXN0ZW5lcjsA",
    "E0xhbmRyb2lkL3ZpZXcvVmlldzsAJUxhbmRyb2lkL3ZpZXcvVmlld0dyb3VwJExheW91dFBhcmFtczsAK0xhbmRyb2lkL3ZpZXcv",
    "Vmlld0dyb3VwJE1hcmdpbkxheW91dFBhcmFtczsAGExhbmRyb2lkL3ZpZXcvVmlld0dyb3VwOwAZTGFuZHJvaWQvdmlldy9WaWV3",
    "UGFyZW50OwAxTGFuZHJvaWQvdmlldy9WaWV3VHJlZU9ic2VydmVyJE9uUHJlRHJhd0xpc3RlbmVyOwAfTGFuZHJvaWQvdmlldy9W",
    "aWV3VHJlZU9ic2VydmVyOwAVTGFuZHJvaWQvdmlldy9XaW5kb3c7AClMYW5kcm9pZC93aWRnZXQvRnJhbWVMYXlvdXQkTGF5b3V0",
    "UGFyYW1zOwAZTGFuZHJvaWQvd2lkZ2V0L1RleHRWaWV3OwAWTGFuZHJvaWQvd2lkZ2V0L1RvYXN0OwAtTGFwcC9yZXZhbmNlZC9l",
    "eHRlbnNpb24vZmFjZWJvb2svYWRoaWRlL0NvcmU7AC5MYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUvRW50",
    "cnk7ACxMYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUvUmVjOwATTGphdmEvbGFuZy9Cb29sZWFuOwAYTGph",
    "dmEvbGFuZy9DaGFyU2VxdWVuY2U7ABFMamF2YS9sYW5nL0NsYXNzOwASTGphdmEvbGFuZy9PYmplY3Q7ABRMamF2YS9sYW5nL1J1",
    "bm5hYmxlOwASTGphdmEvbGFuZy9TdHJpbmc7ABlMamF2YS9sYW5nL1N0cmluZ0J1aWxkZXI7ABJMamF2YS9sYW5nL1N5c3RlbTsA",
    "FUxqYXZhL2xhbmcvVGhyb3dhYmxlOwAQTGphdmEvbGFuZy9Wb2lkOwAaTGphdmEvbGFuZy9yZWZsZWN0L01ldGhvZDsAG0xqYXZh",
    "L3RleHQvTm9ybWFsaXplciRGb3JtOwAWTGphdmEvdGV4dC9Ob3JtYWxpemVyOwASTGphdmEvdXRpbC9Mb2NhbGU7AAVNT0RFTAAD",
    "TkZDAAJPSwAJUkVMQVlPVVQgAAdSRUxFQVNFAAdSRU5ERVIgAARST09UAApSViBoaWRlcjogABBSVi1ISURFUiAxCnRpbWU9AAhS",
    "ZWMuamF2YQAHU0RLX0lOVAAEVFlQRQABVgACVkYAAlZJAANWSUkABFZJSUkABVZJSUlJAANWSUwAAlZMAARWTEZGAAVWTEZGTAAD",
    "VkxJAAdWTElJRkZMAApWTElJSUlGRlpMAANWTEwABFZMTEwABVZMTExMAANWTFoAAVoAAlpMAANaTEoAAltDAAJbRgACW0kAEltM",
    "amF2YS9sYW5nL0NsYXNzOwATW0xqYXZhL2xhbmcvT2JqZWN0OwATW0xqYXZhL2xhbmcvU3RyaW5nOwAbW0xqYXZhL2xhbmcvcmVm",
    "bGVjdC9NZXRob2Q7ABBbaGlkZS1hZCBidXR0b25dAAJcfAANX19SVl9MQUJFTFNfXwADYWRkAB5hZGRPbkF0dGFjaFN0YXRlQ2hh",
    "bmdlTGlzdGVuZXIAFGFkZE9uUHJlRHJhd0xpc3RlbmVyAAdhZGRWaWV3ACthcHAucmV2YW5jZWQuZXh0ZW5zaW9uLmZhY2Vib29r",
    "LmFkaGlkZS5Db3JlAAZhcHBlbmQABmF0dGFjaAAFYmFkZ2UAB2JhZGdlT24ACWJhZGdlVGV4dAAEYmFuZAADYm1wAAxib3R0b21N",
    "YXJnaW4ABmNkSXNBZAAIY2RJc1Bvc3QAB2NoYW5nZWQABmNoYXJBdAAFY2xlYW4ACWNsaXBib2FyZAAIY29sbGFwc2UAE2NvbS5m",
    "YWNlYm9vay5saXRoby4AHGNvbS5mYWNlYm9vay5saXRoby5MaXRob1ZpZXcABGNvcHkADGNyZWF0ZUJpdG1hcAAHY3VycmVudAAR",
    "Y3VycmVudFRpbWVNaWxsaXMABmRlbGV0ZQAFZGlydHkABGRyYXcACmRyYXdCaXRtYXAACGRyYXdUZXh0AAtkcmF3VGV4dFJ1bgAG",
    "ZXF1YWxzAAVmYWlscwAMZmluZFZpZXdCeUlkAA9maW5kVmlld1dpdGhUYWcABWZpcnN0AAdmb3JOYW1lAAJmeQAJZ2V0Qm90dG9t",
    "AApnZXRDaGlsZEF0AA1nZXRDaGlsZENvdW50AAhnZXRDbGFzcwAVZ2V0Q29udGVudERlc2NyaXB0aW9uABJnZXREZWNsYXJlZE1l",
    "dGhvZHMAEWdldERlY2xhcmluZ0NsYXNzAAxnZXREZWNvclZpZXcACWdldEhlaWdodAAFZ2V0SWQAD2dldExheW91dFBhcmFtcwAH",
    "Z2V0TGVmdAATZ2V0TG9jYXRpb25PblNjcmVlbgANZ2V0TWFpbkxvb3BlcgAJZ2V0TWF0cml4ABFnZXRNZWFzdXJlZEhlaWdodAAJ",
    "Z2V0TWV0aG9kAAxnZXRNb2RpZmllcnMAB2dldE5hbWUADmdldFBhY2thZ2VJbmZvABFnZXRQYWNrYWdlTWFuYWdlcgAOZ2V0UGFj",
    "a2FnZU5hbWUAEWdldFBhcmFtZXRlclR5cGVzAAlnZXRQYXJlbnQADWdldFJldHVyblR5cGUACGdldFJpZ2h0AA1nZXRTdXBlcmNs",
    "YXNzABBnZXRTeXN0ZW1TZXJ2aWNlAAZnZXRUYWcABmdldFRvcAATZ2V0Vmlld1RyZWVPYnNlcnZlcgANZ2V0VmlzaWJpbGl0eQAI",
    "Z2V0V2lkdGgACWdldFdpbmRvdwAHaGFuZGxlcgAIaGFzaENvZGUAAmhpAAZoaWRkZW4ABGhpZGUAB2hpZGUgYWQACWhpZGUgcG9z",
    "dAAEaGludAAHaGludEJhZAAIaGludERvbmUABWhpbnRNAANoaXQAB2luc3BlY3QAB2luc3RhbGwACmludmFsaWRhdGUABmludm9r",
    "ZQAHaXNBbGl2ZQAHaXNFeGFjdAAKaXNJbnN0YW5jZQAGaXNMaXN0AAdpc0xpdGhvAAZsYWJlbHMACGxhc3RMaXN0AAZsYXlvdXQA",
    "Bmxlbmd0aAAIbGlzdFBhc3MAAmxvAANsb2cABmxvZ1JvdwAIbWFrZVRleHQACW1hcFBvaW50cwAHbWVhc3VyZQAMbmV3UGxhaW5U",
    "ZXh0AAVub1JlbAAEbm9ybQAGbm9ybWFsAAlub3JtYWxpemUAA29mZgARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0",
    "cm95ZWQAEG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9u",
    "QWN0aXZpdHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAAHb25DbGljawAJb25QcmVEcmF3ABZvblZpZXdBdHRhY2hlZFRvV2lu",
    "ZG93ABhvblZpZXdEZXRhY2hlZEZyb21XaW5kb3cABHBlbmQAB3BlbmRpbmcAC3Bvc3REZWxheWVkACJyZWdpc3RlckFjdGl2aXR5",
    "TGlmZWN5Y2xlQ2FsbGJhY2tzAAhyZWxheW91dAAPcmVtb3ZlQ2FsbGJhY2tzABdyZW1vdmVPblByZURyYXdMaXN0ZW5lcgAGcmVu",
    "ZGVyAAdyZXBsYWNlAAZyZXBvcnQACXJlc3RvcmluZwAHcm93UGFzcwADcnVuAAhydi1oaWRlcgAEc2VlbgAIc2V0QWxwaGEAEnNl",
    "dEJhY2tncm91bmRDb2xvcgAPc2V0TGF5b3V0UGFyYW1zABJzZXRPbkNsaWNrTGlzdGVuZXIACnNldFBhZGRpbmcADnNldFByaW1h",
    "cnlDbGlwAAZzZXRUYWcAB3NldFRleHQADHNldFRleHRDb2xvcgALc2V0VGV4dFNpemUADXNldFZpc2liaWxpdHkABHNob3cAA3Np",
    "ZwAGc21vb3RoAAVzcGxpdAAFc3RhcnQACnN0YXJ0c1dpdGgABnN0cmVhawALc3ViU2VxdWVuY2UACXN1YnN0cmluZwALdG9Mb3dl",
    "ckNhc2UACHRvU3RyaW5nAAV0b2FzdAAEdHJpbQADdHh0AAx1cHRpbWVNaWxsaXMAB3ZhbHVlT2YAB3ZlcnNpb24AC3ZlcnNpb25O",
    "YW1lAAR3YWxrAAR3aW5XAAvhuqluIGLDoGkgdmnhur90AAzhuqluIHF14bqjbmcgY8OhbwAAAAMAAAAJACwAAgAAAAIAAAAwAAIA",
    "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",
    "IAMaAQQAbiCTABAAYgEEAG4gkwAQABoBCQBuIJMAEABiAQIAbiCTABAAGgEMAG4gkwAQAGABAwBuIJAAEAAaAQMAbiCTABAAbhAG",
    "AAUADAFuIJMAEAAaAQYAbiCTABAAcRBpAAUADAFuIJMAEAAaAQIAbiCTABAAbhCBAAUADAFuEH0AAQAMAW4gkwAQABoBBQBuIJMA",
    "EABSQQ4AbiCQABAAGgELAG4gkwAQAFJBDABuIJAAEAAaAQoAbiCTABAAcQBGAAAACgFuIJQAEAAaAQEAbiCTABAAVEETAG4gkgAQ",
    "AG4QlwAAAAwAEQAOAAMAAwAAAAAAAAB+AQAAFAABoPB/biAgAA0ADAEgEj0AOQIJABMCDAAjIT0AbjAtAA0BHwE9AFWyFwA4AgYA",
    "cDBkANsBDgBxABIAAAALAoQiEiREAwEEOQMEAEsCAQQSUyMzPQBxIGUAPQASBEQFAwTaBQUfEhREBgMEsGUSJEQGAwQSFEQHAQQS",
    "GDOHFQA5BgwAbhAYAA0ACggTCQgARAkBCTKYHgASB0sHAQQSJEsCAQQoFxIoM4cVABIIRAgBCDJYDAASCEsFAQgSR0sHAQQSJEsC",
    "AQQoBXAwSADbAQ4AEghbuAcAOQYkABJIRAgDCDkIBwASOEQIAwg5CBoAEkgzhwYAcDBIANsBDgASKEsCAQgSeEQJAQg4CQoAEglL",
    "CQEIFQqAP24gKACtAA4AElhECQEIOQkIABIZSwkBCG4gEwC9AG4QGAANAAoIEwkIAEsIAQk5BrQAbhAhAA0ACghuECQADQAKCdoK",
    "CRnbCgpksIpuEDMADAAKBD0KigA1SIgAOghzADZKcQASBHAwSgDbBAoEEigyhJMAEhgzhDUAEwgJAEQJAQg5CRMASwIBCBJIM4cG",
    "AHAwSADbAQ4AcDBSANsBEjgSFEsIAQQOAJEJAgkTCggANakDAA4AcDBkANsBEhgSFEsIAQQSBEsFAQQTBAkAEghLCAEEGghtAHAw",
    "UACLDQ4AEkgzhw0AEigSFEsIAQQSBEsFAQRwMEgA2wEOABIoRAgBCJEIAggTBCwBN0gSAHAwZADbARIYEhRLCAEEEgRLBQEEGggg",
    "AHAwUACLDQ4AcDBeANsBUrgWANgICAFZuBYADgASZEQIAQSRCAIIEwlQADSYDABLAgEEEgRwMEoA2wQKBBIoMoQZABIoSwIBCBMI",
    "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",
    "AgAAAAAAAABPAAAAOQcDAA4AbhAMAAYADAASISMRPAASAksIAQISEksJAQJuIA0AEABEAgEChyJSYxwANjI2AFJkHwDYBAQBWWQf",
    "AFJjHgA0MiwAcRBFAAcADANUZCAAbhCWAAQACgUTACwBNQUKAG4gkwA0ABMAfABuII8ABABxEEsAAwAKBDgEEABSZB0A2AQEAVlk",
    "HQBUZRoAOQUGAFtjGgBZYhsADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAABAAEAAAAAAAAAAAAAQAAAA4AAAAFAAUAAAAAAAAAAAAB",
    "AAAADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAACQAHAAQAAQAAAAAACgAAAHIweQBDBQwAcEBtAAJ2DgANACj+AAAAAAcAAQABAAgA",
    "BgAFAAQAAQAAAAAABgAAAHBAbQAhQw4ADQAo/gAAAAADAAEAAQAEAAkABwAEAAEAAAAAAAoAAABuMIkAQwUMAHBAbQACdg4ADQAo",
    "/gAAAAAHAAEAAQAIAAkABwAEAAEAAAAAAAoAAABxMI0AQwUMAHBAbQACdg4ADQAo/gAAAAAHAAEAAQAIAAwACgAEAAEAAAAAAAoA",
    "AAByMHkAQwUMAHBAbQACmA4ADQAo/gAAAAAHAAEAAQAIAAwACgAEAAEAAAAAAAoAAABxMI0AQwUMAHBAbQACmA4ADQAo/gAAAAAH",
    "AAEAAQAIABQgDAYCAQIBAgECAQIBAgECARIBAgECAQIBAgECARIBAgECAQIBAgECAQI/goAE7EoBAqhLAQncTAEC8EwBCcBNAQmg",
    "TgEJgE8BCdBPAQrkTwECxFABAvBRAQLgVAEJmFcBCrxYAQrcWQEJlFoBAqxaAQLMXAEJ9F0BApxfDALgXwECjGABCtRhAQKIYgEC",
    "pGQCArBqAQqoawEJ7GwBCYBtAQqkbQEK2G0BApxuUwGMbwEBoG8BAbRvAQGYcAEBnHEBAbBxAQHEcQEB2HEBAchyAQGEdQEBmHUG",
    "Afh1AAABAGsJuHYABwIKGgEBAQEBAQEBAQEBARFsgYAEmHcBAsB3bgHweAEBhHkBAZh5AQGseQEBwHkBAfB5AQGYegEByHoBAfh6",
    "AQGoewAAAA0AAAAAAAAAAQAAAAAAAAABAAAAPwEAAHAAAAACAAAAQgAAAGwFAAADAAAAZQAAAHQGAAAEAAAAJgAAADALAAAFAAAA",
    "oQAAAGAMAAAGAAAAAwAAAGgRAAACIAAAPwEAAMgRAAABEAAAPQAAABQjAAADEAAAAQAAAGglAAABIAAAOQAAAGwlAAAAIAAAAwAA",
    "ANc9AAAAEAAAAQAAAAQ/AAA=",
).joinToString("")

private fun smaliString(s: String): String {
    val sb = StringBuilder(s.length + 64)
    for (c in s) {
        when {
            c == '\\' -> sb.append("\\\\")
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 || c.code > 0x7e -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun ClassDef.hasOnCreateBody() = methods.any {
    it.name == "onCreate" &&
        it.returnType == "V" &&
        it.parameterTypes.isEmpty() &&
        it.implementation != null &&
        !AccessFlags.STATIC.isSet(it.accessFlags)
}

private fun BytecodePatchContext.reachesApplication(start: ClassDef): Boolean {
    var current: ClassDef? = start
    val seen = HashSet<String>()
    while (current != null && seen.add(current.type)) {
        if (current.superclass == ANDROID_APPLICATION) return true
        current = current.superclass?.let { classDefs[it] }
    }
    return false
}

/** First class on the Application chain that has its own onCreate() with a body. */
private fun BytecodePatchContext.findHookClass(): ClassDef? {
    val start = classDefs[APP_CLASS]
        ?: classDefs.toList().firstOrNull { it.type.endsWith("Application;") && reachesApplication(it) }
        ?: return null
    var current: ClassDef? = start
    val seen = HashSet<String>()
    while (current != null && seen.add(current.type)) {
        if (current.hasOnCreateBody()) return current
        current = current.superclass?.let { classDefs[it] }
    }
    return null
}

@Suppress("unused")
val hideNativeFeedAdsPatch = bytecodePatch(
    name = "Hide 'Sponsored Stories' on android 8 app",
    description = "ANDROID 8 and ANDROID 11 (all builds). Hides Sponsored posts in the NATIVE News Feed while you scroll: " +
        "reads what the app draws in the post header (the \"Sponsored\" label in 40+ languages, e.g. \"Được tài trợ\") and the " +
        "\"Hide ad\" button, and makes that row invisible and (option) takes its height out of the list. Options also hide " +
        "\"Suggested for you\" posts (\"Gợi ý cho bạn\") and paid partnership posts (\"Mối quan hệ tài trợ ...\"). Works at run " +
        "time on what is on the screen, so it does not depend on obfuscated names and works on the Dextricks " +
        "(Android 8) builds. Adds no file to the apk, no resource, no manifest change. Not for stories yet. " +
        "Only feed posts are touched (never Reels, Page / profile or other lists). " +
        "A small badge at the bottom left shows how many posts were hidden; tap it to copy a log.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val collapseSpace by booleanOption(
        name = "Collapse space",
        description = "Takes the height of a hidden ad out of the list so that the next post moves up (negative bottom " +
            "margin, RecyclerView only). Turn off to keep an invisible blank gap instead.",
        default = true,
    )

    val hideSuggested by booleanOption(
        name = "Hide suggested posts",
        description = "Also hides posts whose header says \"Suggested for you\" (\"Gợi ý cho bạn\", \"Đề xuất cho bạn\" and the " +
            "translations) instead of \"Sponsored\". Turn off to keep them.",
        default = true,
    )

    val hidePartnership by booleanOption(
        name = "Hide paid partnership posts",
        description = "Also hides posts with a line that starts with \"Paid partnership\" / \"Mối quan hệ tài trợ\" (brand deals " +
            "under the page name). Turn off to keep them.",
        default = true,
    )

    val smoothHiding by booleanOption(
        name = "Smooth hiding",
        description = "After a row is hidden, lay the list out again inside the frame that is being drawn, so no frame is dropped " +
            "(no stutter). Turn off if the list ever shows a glitch: the old way is used then (the frame is dropped and redone).",
        default = true,
    )

    val showBadge by booleanOption(
        name = "Show counter badge",
        description = "Shows a small dark 'RV hider: N hidden' button at the bottom left of the screen; a tap copies the " +
            "log to the clipboard. Turn it off after testing (then the log cannot be copied).",
        default = false,
    )

    apply {
        val log = Logger.getLogger("HideNativeFeedAds")

        if (classDefs[ENTRY] != null) {
            log.info("Hide native feed ads: Entry is already in the dex, nothing to add")
            return@apply
        }

        // ---- 1. Find the hook (read only) ------------------------------------------------------------------------
        val hookClass = findHookClass()
            ?: throw PatchException(
                "Hide native feed ads: no Application.onCreate() found ($APP_CLASS or a class extending " +
                    "$ANDROID_APPLICATION). Send the smali of the app's Application class.",
            )

        // ---- 2. Add the three classes into the existing dex ------------------------------------------------------
        val added = try {
            DexBackedDexFile(null, Base64.getDecoder().decode(ADHIDE_DEX), 0).classes.toList()
        } catch (e: Exception) {
            throw PatchException("Hide native feed ads: cannot read the embedded classes: $e")
        }
        if (added.size != 3 || added.none { it.type == ENTRY } || added.none { it.type == CORE }) {
            throw PatchException("Hide native feed ads: the embedded dex does not hold Entry, Core and Rec (${added.map { it.type }})")
        }
        @Suppress("UNCHECKED_CAST")
        val mutableClassDefs = classDefs as MutableSet<ClassDef>
        for (def in added) {
            if (!mutableClassDefs.add(def)) {
                throw PatchException("Hide native feed ads: could not add ${def.type} to the dex")
            }
        }

        // ---- 3. Label list and the two options go into Core ------------------------------------------------------
        val core = added.first { it.type == CORE }
        val coreMethods = classDefs.getOrReplaceMutable(core).methods
        val labelsMethod = coreMethods
            .firstOrNull { it.name == "labels" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: throw PatchException("Hide native feed ads: Core.labels() not found")
        val labels = LABELS_SPONSORED +
            (if (hideSuggested != false) "|$LABELS_SUGGESTED" else "") +
            (if (hidePartnership != false) "|$LABELS_PARTNERSHIP" else "")
        labelsMethod.replaceInstruction(0, "const-string v0, \"${smaliString(labels)}\"")

        if (collapseSpace == false) {
            val m = coreMethods.firstOrNull { it.name == "collapse" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.collapse() not found")
            m.replaceInstruction(0, "const/4 v0, 0x0")
        }
        if (smoothHiding == false) {
            val m = coreMethods.firstOrNull { it.name == "smooth" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.smooth() not found")
            m.replaceInstruction(0, "const/4 v0, 0x0")
        }
        if (showBadge == false) {
            val m = coreMethods.firstOrNull { it.name == "badgeOn" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.badgeOn() not found")
            m.replaceInstruction(0, "const/4 v0, 0x0")
        }

        // ---- 4. One call at the very top of the existing Application.onCreate() ----------------------------------
        val onCreate = classDefs.getOrReplaceMutable(hookClass).methods.first {
            it.name == "onCreate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty() &&
                it.implementation != null &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        onCreate.addInstructions(0, "invoke-static {p0}, $ENTRY->install(Landroid/app/Application;)V")

        log.info(
            "Hide native feed ads: hooked ${hookClass.type}->onCreate(), ${added.size} classes added, " +
                "collapse=${collapseSpace != false}, suggested=${hideSuggested != false}, " +
                "partnership=${hidePartnership != false}, smooth=${smoothHiding != false}, badge=${showBadge != false}",
        )
    }
}
