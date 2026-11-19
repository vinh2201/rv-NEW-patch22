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
 *   4. an ad row is made INVISIBLE and its height is taken out of the layout (bottom margin = - height) so there is no gap.
 *      The list is then laid out again at once, INSIDE the frame that is being drawn, so the frame is not dropped (if that call
 *      ever throws, the old way is used: the frame is cancelled and redone, at most 3 times in a row). A row that shows
 *      header text but no label is an ordinary post and is left
 *      alone (believed at the second look, in a later frame: while scrolling fast Litho may still show the previous frame's content);
 *   5. a row that has not drawn its header yet is kept transparent for at most 300 ms (it is still laid out and drawn, so the
 *      header does get drawn), then shown;
 *   6. when RecyclerView recycles a row view (it leaves the window) the row is given back untouched (visible, own margin).
 *   7. when a row is hidden, Litho is told that the row is "not visible" (LithoView.setVisibilityHint(false), found by its shape
 *      through reflection: the only public, non-static void (boolean, boolean) method that LithoView declares itself; its name is obfuscated
 *      and different in every build). Without it the app does not know that the row was hidden and its video can keep playing
 *      or sounding for a second under the invisible row. It is told "visible" again when the row is shown. If the method is not
 *      found, or is found twice, this step is skipped for good (nothing else changes). An error in ONE call only loses that call:
 *      the next hide tries again (version 2 gave up for good at the first error, and from then on hidden ads could sound again).
 *      The log (tap on the badge) says "HINT method ...", "HINT none" or "HINT exc ..." (the first 10 errors).
 *   8. (option "Hide again when scrolling back up", OFF by default, experimental) when a row is judged an ad, the ITEM is
 *      remembered (up to 32 items): the identity of the Litho ComponentTree that the
 *      LithoView shows (the field that holds it has an obfuscated name but a fixed type, com.facebook.litho.ComponentTree, so it is
 *      found by its type; Litho's RecyclerBinder keeps one tree per item) together with the height of the row. When the user scrolls
 *      back UP and RecyclerView binds that item to a view again, the row is hidden at once, in the frame in which it comes into the
 *      visible part of the list, although its header is still above the list. Such a row is only made invisible and collapsed;
 *      Litho is NOT told "not visible" at once but 150 ms later, if the row is still bound then, or as soon as its header has been
 *      read and has confirmed the ad (an earlier version told Litho in the very frame of the first bind, before the row had ever
 *      been reported visible, and that is the suspected cause of the stalls reported with it). Rows that are
 *      completely outside the list are not touched. When the header is on the screen it is read as usual; if it turns out to be an
 *      ordinary post it is shown and forgotten; while the header has no text yet the row stays hidden. Identity AND height (2%)
 *      must agree. If the field is not found / not readable, nothing is remembered and everything else works as before. The log
 *      has "TREE fields=N" and "HIDE by remembered item".
 *
 * Costs: the tree walk is a few dozen views per frame; a row is drawn into the bitmap once or twice per judgement (not every
 * frame; a row cut by the edge of the list at most every 80 ms);
 * one ALPHA_8 bitmap of  width x 40% width  is kept. When a row is hidden the list is measured / laid out once more, with its own
 * size, before the frame is drawn (the same work the parent would do one frame later). If that call ever throws, it is switched
 * off for good and the old way is used: the frame is cancelled and redone (at most 3 times in a row).
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. A row whose drawing fails 3 times is not drawn again while it is bound (it is judged by its buttons
 * only). If drawing fails 6 times IN A ROW (no row drawn well in between), or the per-frame pass throws 6 times in a row, the patch
 * gives every row back and rests for 10 s, then starts again (version 2 counted the failures of the whole session and switched
 * itself off for good at the 6th: after some minutes of scrolling the ads and their sound came back).
 * Rows shorter than 40% of their width (menu entries, small cards) are never taken for ads by their label.
 *
 * What else is hidden (labels compared in the same way, switched off together by the option "Hide suggested and partnership posts"):
 *   - "Suggested for you" posts: the label "Gợi ý cho bạn" / "Suggested for you" is drawn in the
 *     same place as "Sponsored" (seen in a real probe: name at y=82, label at y=139, then "·", "8 giờ", "·").
 *   - Paid partnership posts: a line such as "Mối quan hệ tài trợ ..." / "Paid partnership
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
 *   - Scrolling UP over an ad that was NOT hidden before (for example the app was started in the middle of the feed, or the
 *     ad's Litho tree was released and rebuilt): its header is outside the list, so it cannot be recognised until the header is on
 *     the screen; the lower part of that ad is visible until then. Ads that were hidden on the way down are remembered (item 8).
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

/** "Suggested for you" in many languages. Same rules. Added when the option "Hide suggested and partnership posts" is on. */
private const val LABELS_SUGGESTED = "suggested for you|recommended for you|مقترح لك|مقترحات لك|предложения за вас|navrhované pro vás|vorschläge für dich|für dich empfohlen|προτεινόμενα για εσάς|sugerencias para ti|sugerido para ti|ehdotuksia sinulle|suggestion pour vous|suggestions pour vous|הצעות עבורך|disarankan untuk anda|contenuti suggeriti per te|suggerimenti per te|おすすめ|ieteikumi jums|voorgesteld voor jou|proponowane dla ciebie|sugestões para ti|sugestões para você|рекомендуем вам|предлагаемое для вас|senin için önerilenler|рекомендовано для вас|gợi ý cho bạn|đề xuất cho bạn|为你推荐|推荐给你|為你推薦|推薦給你"

/** "Paid partnership ..." labels: a star at the end means that the drawn text only has to START with it. Added when the option
 *  "Hide suggested and partnership posts" is on. */
private const val LABELS_PARTNERSHIP = "paid partnership*|mối quan hệ tài trợ*|mối quan hệ đối tác trả phí*|quan hệ đối tác trả phí*|hợp tác trả phí*|colaboración pagada*|colaboración de pago*|partenariat rémunéré*|bezahlte partnerschaft*|parceria paga*|parceria paga com*|partnership a pagamento*|collaborazione a pagamento*|kemitraan berbayar*"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val ADHIDE_DEX = listOf(
    "ZGV4CjAzOADDx3qxRnWo4ct6yfz1lVZcym1ajM+k+6a8SQAAcAAAAHhWNBIAAAAAAAAAABxJAABsAQAAcAAAAEUAAAAgBgAAbQAA",
    "ADQHAAAxAAAAUAwAALMAAADYDQAAAwAAAHATAADsNQAA0BMAANATAADSEwAA1hMAAOITAADpEwAA8xMAAP0TAAABFAAAEhQAAC8U",
    "AAA7FAAASBQAAFIUAABiFAAAdhQAAH4UAACJFAAAkRQAAJ0UAACiFAAAqxQAALEUAADfFAAA5hQAAO0UAADzFAAA+xQAAP4UAAAH",
    "FQAAIBUAACMVAAAnFQAAMhUAADgVAABEFQAARxUAAE4VAAB2FQAAfBUAAJUVAACgFQAArhUAAMYVAADJFQAAzRUAANIVAADWFQAA",
    "2xUAAN4VAADhFQAA5RUAAOoVAADuFQAA8xUAAPkVAAAAFgAABBYAAAgWAAANFgAAExYAABgWAAAeFgAAIhYAADoWAABwFgAAixYA",
    "AKcWAADLFgAA5hYAAAgXAAAtFwAATxcAAGoXAACFFwAAoBcAALoXAADTFwAA7RcAAAkYAAAdGAAAMhgAAEgYAABdGAAAdxgAAJMY",
    "AACvGAAA4BgAAAUZAAAaGQAAQRkAAG4ZAACIGQAAoxkAANYZAAD3GQAADhoAADkaAABUGgAAbBoAAJsaAADLGgAA+RoAAA4bAAAo",
    "GwAAOxsAAE0bAABhGwAAdxsAAIsbAACmGwAAuhsAANEbAADjGwAA/hsAABocAAA3HAAATxwAAGMcAABqHAAAbxwAAHMcAAB+HAAA",
    "hxwAAJAcAAC5HAAAvxwAAMscAADdHAAA5xwAAPAcAAD7HAAACR0AAA8dAAASHQAAFh0AABodAAAfHQAAJR0AACwdAAAxHQAANR0A",
    "ADsdAABCHQAARx0AAFAdAABcHQAAYR0AAGcdAABuHQAAcx0AAHcdAAB6HQAAfx0AAIMdAACIHQAAjB0AAJAdAACUHQAAqB0AAL0d",
    "AADSHQAA7h0AAAseAAAdHgAAIR4AADAeAAA1HgAAOh4AAFoeAABwHgAAeR4AAKYeAACuHgAAth4AAL0eAADGHgAA0R4AANceAADc",
    "HgAA6h4AAPIeAAD8HgAABR8AAA0fAAAUHwAAHx8AACkfAAA+HwAAYB8AAH4fAACEHwAAkh8AAJsfAACuHwAAth8AAL0fAADDHwAA",
    "zx8AANkfAADmHwAA7h8AAPUfAAADIAAAFCAAABsgAAAkIAAALCAAADAgAAA1IAAAQCAAAEwgAABbIAAAZSAAAHwgAACPIAAAoyAA",
    "ALYgAADEIAAAzyAAANYgAADnIAAA8CAAAAUhAAAUIQAAHyEAADIhAAA9IQAASyEAAFQhAABkIQAAdyEAAIchAACaIQAApSEAALQh",
    "AAC+IQAAzSEAAN8hAADnIQAA7yEAAPghAAANIgAAHCIAACYiAAAxIgAAOiIAAEQiAABIIgAAUCIAAFYiAABfIgAAaiIAAHAiAAB5",
    "IgAAgyIAAIwiAACTIgAAmCIAAKoiAACzIgAAvCIAAMgiAADQIgAA2SIAAOIiAADuIgAA9iIAAP8iAAAHIwAAESMAABkjAAAhIwAA",
    "KyMAAC8jAAA0IwAAPCMAAEYjAABRIwAAWiMAAF8jAABnIwAAbyMAAHgjAACAIwAAiCMAAI4jAACWIwAApCMAAKsjAACxIwAAuSMA",
    "AMQjAADJIwAA0CMAAOMjAAD4IwAACiQAAB0kAAA6JAAATSQAAGAkAABpJAAAdCQAAIwkAACmJAAArCQAALUkAADCJAAA5iQAAPAk",
    "AAD6JAAACyUAACQlAAAsJQAANSUAAD0lAABIJQAAUSUAAFglAABhJQAAZiUAAHAlAAB2JQAAhSUAAI8lAACjJQAAtCUAAMglAADU",
    "JQAA5CUAAOwlAAD1JQAAAyYAABAmAAAfJgAAJSYAAComAAAyJgAAOSYAAEAmAABMJgAAVCYAAGEmAABsJgAAeSYAAIMmAACKJgAA",
    "kyYAAJwmAACmJgAAriYAALYmAADAJgAAyCYAAM4mAADTJgAA4SYAAOomAADzJgAAACcAAAYnAAAMJwAAFCcAACYnAAAdAAAAIgAA",
    "ACoAAAAvAAAAPgAAAD8AAABAAAAAQQAAAEIAAABDAAAARAAAAEUAAABGAAAARwAAAEgAAABJAAAASgAAAEsAAABMAAAATQAAAE4A",
    "AABPAAAAUAAAAFEAAABSAAAAUwAAAFQAAABVAAAAVgAAAFcAAABYAAAAWQAAAFoAAABbAAAAXAAAAF0AAABeAAAAXwAAAGAAAABh",
    "AAAAYgAAAGMAAABkAAAAZQAAAGYAAABnAAAAaAAAAGkAAABqAAAAawAAAGwAAABtAAAAbgAAAG8AAABwAAAAcQAAAHIAAABzAAAA",
    "dAAAAIQAAACWAAAAmgAAAJsAAACcAAAAnQAAAJ4AAACfAAAAoAAAAKEAAAAeAAAAAAAAAAwpAAAqAAAAAgAAAAAAAAArAAAAAgAA",
    "AAwpAAAsAAAAAgAAANgoAAAtAAAAAgAAAHQnAAAuAAAAAgAAAEQoAAAtAAAAAgAAAFgoAAAvAAAAAwAAAAAAAAA7AAAABwAAAPAn",
    "AAA5AAAACgAAAEgnAAAwAAAACwAAAAAAAAA1AAAADQAAAFApAAAwAAAADwAAAAAAAAAwAAAAFwAAAAAAAAAwAAAAHQAAAAAAAAAz",
    "AAAAHQAAAAwpAAA4AAAAHQAAAFgoAAAwAAAAHgAAAAAAAAAwAAAAIQAAAAAAAAAwAAAAIwAAAAAAAAAwAAAAJAAAAAAAAAA8AAAA",
    "JwAAADwnAAA9AAAAKwAAAIApAAAwAAAALAAAAAAAAAA0AAAALAAAANgoAAAwAAAALQAAAAAAAAA4AAAALQAAAIQnAAAzAAAALwAA",
    "AAwpAAA4AAAALwAAAFgoAAA7AAAALwAAAEgpAAA4AAAALwAAAIQnAAAwAAAAMQAAAAAAAAAyAAAAMQAAANAnAAA0AAAAMQAAANgo",
    "AAA4AAAAMQAAAMgnAAA4AAAAMQAAAAAoAAA7AAAAMQAAANgnAAA4AAAAMQAAAIgpAAA7AAAAMQAAAIwnAAA4AAAAMQAAAFgoAAA4",
    "AAAAMQAAAIQnAAA4AAAAMQAAAOAnAAA6AAAAMQAAADwpAAAxAAAAMgAAAOAoAAAzAAAAMgAAAAwpAAA0AAAAMgAAANgoAAA3AAAA",
    "MgAAABQpAAA4AAAAMgAAAIgpAAA4AAAAMgAAAIQnAAA9AAAAMgAAAIApAAA7AAAANwAAAAQpAACEAAAAOwAAAAAAAACFAAAAOwAA",
    "APQoAACGAAAAOwAAAAwpAACHAAAAOwAAANgoAACIAAAAOwAAAGgnAACJAAAAOwAAAFwpAACKAAAAOwAAAGgoAACLAAAAOwAAAMgn",
    "AACRAAAAOwAAADQpAACRAAAAOwAAAKQoAACLAAAAOwAAAPgnAACLAAAAOwAAAHwnAACLAAAAOwAAAGgpAACLAAAAOwAAAAAoAACL",
    "AAAAOwAAAHgpAACNAAAAOwAAAKQnAACSAAAAOwAAABAoAACTAAAAOwAAAOgoAACTAAAAOwAAAKwoAACLAAAAOwAAAMAoAACLAAAA",
    "OwAAAMAnAACLAAAAOwAAACQpAACLAAAAOwAAAHAoAACLAAAAOwAAAHQnAACOAAAAOwAAAEQoAACRAAAAOwAAAJAoAACUAAAAOwAA",
    "ABwoAACRAAAAOwAAAGAoAACLAAAAOwAAAAgoAACLAAAAOwAAAPwoAACRAAAAOwAAALgnAACLAAAAOwAAAOgnAACLAAAAOwAAAIgp",
    "AACMAAAAOwAAAJgoAACPAAAAOwAAAMgoAACQAAAAOwAAAHgoAACLAAAAOwAAAFgoAACLAAAAOwAAALgoAACNAAAAOwAAAEwoAACP",
    "AAAAOwAAACQoAACRAAAAOwAAAHApAACVAAAAOwAAAIApAACPAAAAOwAAAJQnAACQAAAAOwAAAFAnAACLAAAAOwAAABwpAACLAAAA",
    "OwAAACwpAACWAAAAPAAAAAAAAACXAAAAPAAAANgoAACYAAAAPAAAAHQnAACYAAAAPAAAAPwoAACYAAAAPAAAAIgpAACYAAAAPAAA",
    "AFgoAACZAAAAPAAAALAnAACYAAAAPAAAAIQnAAAwAAAAQAAAAAAAAAA4AAAAQgAAAIQnAAAwAAAAQwAAAAAAAAAwAAAARAAAAAAA",
    "AAAKADEAZgEAAAwADAAbAAAAEwAxAHkAAAATAAIAgAAAABQAMQB1AAAAHwACALIAAAAoACYArQAAACgAMQCwAAAAKAANALEAAAAo",
    "ADwAtQAAACgABAC/AAAAKAAhAMIAAAAoAAIAyAAAACgAFgD0AAAAKAACAPcAAAAoADwA/AAAACgAPAD9AAAAKAACAP4AAAAoADcA",
    "/wAAACgAHQAMAQAAKAAyABEBAAAoAD8AFgEAACgAAgAcAQAAKAA8AB8BAAAoADwAIwEAACgAAgAkAQAAKAACADEBAAAoADwAOwEA",
    "ACgAAgBUAQAAKAA8AFoBAAAoAC0AWwEAACgAPABcAQAAKAA2AF0BAAAoADYAXgEAACgAAgBfAQAAKAACAGgBAAAoAAIAaQEAACoA",
    "MQDLAAAAKgACAM4AAAAqAAIA9gAAACoAAgAAAQAAKgACABABAAAqAAIAQQEAACoAMgBiAQAAKwArACMAAAArAC0AgwAAADUALQCD",
    "AAAAOAA4AHYAAAA6ADoAfAAAAAQADwDJAAAABAAUAPMAAAAGAD0AMwEAAAcACAAeAQAACAA/AEgBAAAJAAoA5QAAAAkAHwDmAAAA",
    "CQAeAOwAAAALAAkA5AAAAA0ACwC+AAAADQABAPIAAAAOAEEAGQAAAA4ADADfAAAADwBfABQBAAAWAEcAGQAAABYAZwAyAQAAFgBY",
    "ADYBAAAXAA0A3gAAABgABwBjAQAAHQBIAKcAAAAdAEYAwwAAAB0AEADKAAAAHQABANAAAAAdABcA1AAAAB0AAQDZAAAAHQABANoA",
    "AAAdABEA2wAAAB0AAQDcAAAAHQBgAN0AAAAdAAEA4AAAAB0AEgDoAAAAHQABAOoAAAAdABsA7QAAAB0AAQDuAAAAHQATAPAAAAAd",
    "AAEA8QAAAB0AAQDyAAAAHQAzAAQBAAAdADgADQEAAB0ANgAVAQAAHQA0AEMBAAAdADUARAEAAB0ATwBFAQAAHQBJAEYBAAAdADgA",
    "RwEAAB0AOQBJAQAAHQBXAEkBAAAdADUATQEAACAATACpAAAAIAAPANEAAAAgAAEA0gAAACAAAQDZAAAAIwBSAKgAAAAjAGEABgEA",
    "ACMAUgA3AQAAJAAOANgAAAAlADcAGQAAACYAQAAZAAAAJgBTAEoBAAAmADUASwEAACYANABMAQAAJwAVABMBAAAnADMATgEAACgA",
    "MwAZAAAAKAA6AKwAAAAoAGEArgAAACgAMwCvAAAAKABlALMAAAAoAGUAtAAAACgAJQC3AAAAKABhALkAAAAoADwAvQAAACgASgDN",
    "AAAAKABOAPgAAAAoAE0A+wAAACgABQACAQAAKABoAAcBAAAoAGQACQEAACgAYwAKAQAAKAAfAAsBAAAoAFAADwEAACgAWwASAQAA",
    "KAA2ABcBAAAoADUAGAEAACgAAwAZAQAAKABiABoBAAAoAGMAGwEAACgAYQAdAQAAKAAoACABAAAoAE4AIQEAACgAOwAlAQAAKAA6",
    "ACYBAAAoADoAJwEAACgAOgAoAQAAKAA7ACkBAAAoADoAKgEAACgAOgArAQAAKABKACwBAAAoAGEALQEAACgASgAuAQAAKABKAC8B",
    "AAAoAE4AMAEAACgAYQA0AQAAKABKADUBAAAoACQAOAEAACgAIgA6AQAAKABKADwBAAAoAEoAPQEAACgAUQA+AQAAKAAzAD8BAAAo",
    "AE4ATgEAACgATgBPAQAAKABhAFABAAAoAD4AUgEAACgAPABZAQAAKAAEAGABAAAoACMAZQEAACgASwBnAQAAKQA+AAMBAAAqAEEA",
    "GQAAACoAVACmAAAAKgBCAMQAAAAqAEMAxAAAACoARADEAAAAKgBFAMQAAAAqAFUAxQAAACoAWQDFAAAAKgBaAMUAAAAqAF0AxQAA",
    "ACoAVgDGAAAAKgBeAMYAAAArABYAZAEAACwAGABVAQAALQAaAMwAAAAtAGsA1QAAAC0AbADWAAAALQAyAOEAAAAtAB8A4wAAAC0A",
    "GQDrAAAALQBmAAgBAAAuAAIApQAAAC8AMwAZAAAALwAZANMAAAAxAAAAtgAAADEAZgDHAAAAMQABAPUAAAAxAAEADgEAADEAIAA5",
    "AQAAMQBqAFEBAAAxAGgAUwEAADEAIQBWAQAAMQApAFcBAAAxAB8AYQEAADEAJwBkAQAAMQAqAGQBAAAyADMAGQAAADIAKwCrAAAA",
    "MgAsAKsAAAAyAC4AqwAAADIALwCrAAAAMgAwAKsAAAAyADEAqwAAADIALQDBAAAAMgABAA4BAAAyAB8AWAEAADMABwDAAAAAMwAG",
    "AAEBAAA0AB8AWAEAADYAHADPAAAANgABAOIAAAA2ABkA7wAAADYAXABCAQAANwAZANcAAAA3AAEA4gAAADcAHwDjAAAANwBpAOcA",
    "AAA3ABkA6QAAADcAHQAFAQAAOQAmACIBAAAoAAAAEQAAAC8AAAA0KAAAHwAAAAAAAACTRwAAAAAAACkAAAARAAAALwAAAAAAAAAh",
    "AAAAAAAAAMFIAAAAAAAAKgAAABEAAAAOAAAAAAAAAH8AAAAAAAAAykgAAAAAAAAAAAIKCgAKCmFjdGl2aXR5PQAFCmFwcD0ACApk",
    "ZXZpY2U9AAgKaGlkZGVuPQACICAADyAgQUQgYnkgbGFiZWwgJwAbICBBRCBieSB0aGUgaGlkZS1hZCBidXR0b24KAAogIGFuZHJv",
    "aWQ9AAsgIGNvbGxhcHNlPQAIICBmYWlscz0ADiAgaGludC1lcnJvcnM9ABIgIHJlbWVtYmVyZWQtaGl0cz0ABiAgc2RrPQAJICB4",
    "ZmFpbHM9AAYgYmFuZD0ACiBjaGlsZHJlbj0AAyBoPQAHIGhpZGRlbgAEIGxwPQAsIHJvdyhzKSBoaWRkZW4uIExvZyBjb3BpZWQg",
    "dG8gdGhlIGNsaXBib2FyZC4ABSB0b3A9AAUgdmlzPQAEJyB5PQAGPGluaXQ+AAE/AAdBTFBIQV84ABdCQUNLIE9OIGFmdGVyIHRo",
    "ZSByZXN0CgABQwACQ0kACUNvcmUuamF2YQAERVhDIAAKRW50cnkuamF2YQABRgAFRkFMU0UAJkdJVkUgVVAgKG5vIHRleHQgaW4g",
    "dGhlIGhlYWRlciksIHNob3duAARISURFABdISURFIGJ5IHJlbWVtYmVyZWQgaXRlbQAJSElOVCBleGMgAAxISU5UIG1ldGhvZCAA",
    "FkhJTlQgbm9uZSwgY2FuZGlkYXRlcz0AAUkAAklJAANJSUkAAklMAANJTEkAAUoAAUwAAkxDAANMQ0MAAkxJAANMSUkABExJSUwA",
    "BUxJU1QgAAJMSgACTEwAA0xMSQAETExJSQADTExMAARMTExJAAJMWgAWTGFuZHJvaWQvYXBwL0FjdGl2aXR5OwA0TGFuZHJvaWQv",
    "YXBwL0FwcGxpY2F0aW9uJEFjdGl2aXR5TGlmZWN5Y2xlQ2FsbGJhY2tzOwAZTGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uOwAaTGFu",
    "ZHJvaWQvY29udGVudC9DbGlwRGF0YTsAIkxhbmRyb2lkL2NvbnRlbnQvQ2xpcGJvYXJkTWFuYWdlcjsAGUxhbmRyb2lkL2NvbnRl",
    "bnQvQ29udGV4dDsAIExhbmRyb2lkL2NvbnRlbnQvcG0vUGFja2FnZUluZm87ACNMYW5kcm9pZC9jb250ZW50L3BtL1BhY2thZ2VN",
    "YW5hZ2VyOwAgTGFuZHJvaWQvZ3JhcGhpY3MvQml0bWFwJENvbmZpZzsAGUxhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcDsAGUxhbmRy",
    "b2lkL2dyYXBoaWNzL0NhbnZhczsAGUxhbmRyb2lkL2dyYXBoaWNzL01hdHJpeDsAGExhbmRyb2lkL2dyYXBoaWNzL1BhaW50OwAX",
    "TGFuZHJvaWQvZ3JhcGhpY3MvUmVjdDsAGExhbmRyb2lkL2dyYXBoaWNzL1JlY3RGOwAaTGFuZHJvaWQvb3MvQnVpbGQkVkVSU0lP",
    "TjsAEkxhbmRyb2lkL29zL0J1aWxkOwATTGFuZHJvaWQvb3MvQnVuZGxlOwAUTGFuZHJvaWQvb3MvSGFuZGxlcjsAE0xhbmRyb2lk",
    "L29zL0xvb3BlcjsAGExhbmRyb2lkL29zL1N5c3RlbUNsb2NrOwAaTGFuZHJvaWQvdmlldy9TdXJmYWNlVmlldzsAGkxhbmRyb2lk",
    "L3ZpZXcvVGV4dHVyZVZpZXc7AC9MYW5kcm9pZC92aWV3L1ZpZXckT25BdHRhY2hTdGF0ZUNoYW5nZUxpc3RlbmVyOwAjTGFuZHJv",
    "aWQvdmlldy9WaWV3JE9uQ2xpY2tMaXN0ZW5lcjsAE0xhbmRyb2lkL3ZpZXcvVmlldzsAJUxhbmRyb2lkL3ZpZXcvVmlld0dyb3Vw",
    "JExheW91dFBhcmFtczsAK0xhbmRyb2lkL3ZpZXcvVmlld0dyb3VwJE1hcmdpbkxheW91dFBhcmFtczsAGExhbmRyb2lkL3ZpZXcv",
    "Vmlld0dyb3VwOwAZTGFuZHJvaWQvdmlldy9WaWV3UGFyZW50OwAxTGFuZHJvaWQvdmlldy9WaWV3VHJlZU9ic2VydmVyJE9uUHJl",
    "RHJhd0xpc3RlbmVyOwAfTGFuZHJvaWQvdmlldy9WaWV3VHJlZU9ic2VydmVyOwAVTGFuZHJvaWQvdmlldy9XaW5kb3c7AClMYW5k",
    "cm9pZC93aWRnZXQvRnJhbWVMYXlvdXQkTGF5b3V0UGFyYW1zOwAZTGFuZHJvaWQvd2lkZ2V0L1RleHRWaWV3OwAWTGFuZHJvaWQv",
    "d2lkZ2V0L1RvYXN0OwAtTGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svYWRoaWRlL0NvcmU7AC5MYXBwL3JldmFuY2Vk",
    "L2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUvRW50cnk7ACxMYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUv",
    "UmVjOwATTGphdmEvbGFuZy9Cb29sZWFuOwAYTGphdmEvbGFuZy9DaGFyU2VxdWVuY2U7ABFMamF2YS9sYW5nL0NsYXNzOwAQTGph",
    "dmEvbGFuZy9NYXRoOwASTGphdmEvbGFuZy9PYmplY3Q7ABRMamF2YS9sYW5nL1J1bm5hYmxlOwASTGphdmEvbGFuZy9TdHJpbmc7",
    "ABlMamF2YS9sYW5nL1N0cmluZ0J1aWxkZXI7ABJMamF2YS9sYW5nL1N5c3RlbTsAFUxqYXZhL2xhbmcvVGhyb3dhYmxlOwAQTGph",
    "dmEvbGFuZy9Wb2lkOwAZTGphdmEvbGFuZy9yZWZsZWN0L0ZpZWxkOwAaTGphdmEvbGFuZy9yZWZsZWN0L01ldGhvZDsAG0xqYXZh",
    "L3RleHQvTm9ybWFsaXplciRGb3JtOwAWTGphdmEvdGV4dC9Ob3JtYWxpemVyOwASTGphdmEvdXRpbC9Mb2NhbGU7AAVNT0RFTAAD",
    "TkZDAAJPSwAJUkVMQVlPVVQgAAdSRUxFQVNFAAdSRU5ERVIgACdSRVNUSU5HIGZvciAxMCBzICg2IGZhaWx1cmVzIGluIGEgcm93",
    "KQoABFJPT1QAClJWIGhpZGVyOiAAEFJWLUhJREVSIDEKdGltZT0ACFJlYy5qYXZhAAdTREtfSU5UAAlUUkVFIGV4YyAADFRSRUUg",
    "ZmllbGRzPQAEVFlQRQABVgACVkYAAlZJAANWSUkABFZJSUkABVZJSUlJAANWSUwAAlZMAARWTEZGAAVWTEZGTAADVkxJAAdWTElJ",
    "RkZMAApWTElJSUlGRlpMAANWTEwABFZMTEwABVZMTExMAANWTFoAAlZaAAFaAANaSUkAAlpMAANaTEoAAltDAAJbRgACW0kAEltM",
    "amF2YS9sYW5nL0NsYXNzOwATW0xqYXZhL2xhbmcvT2JqZWN0OwATW0xqYXZhL2xhbmcvU3RyaW5nOwAaW0xqYXZhL2xhbmcvcmVm",
    "bGVjdC9GaWVsZDsAG1tMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOwAQW2hpZGUtYWQgYnV0dG9uXQACXHwADV9fUlZfTEFCRUxT",
    "X18AA2FicwADYWRkAB5hZGRPbkF0dGFjaFN0YXRlQ2hhbmdlTGlzdGVuZXIAFGFkZE9uUHJlRHJhd0xpc3RlbmVyAAdhZGRWaWV3",
    "ACthcHAucmV2YW5jZWQuZXh0ZW5zaW9uLmZhY2Vib29rLmFkaGlkZS5Db3JlAAZhcHBlbmQABmF0dGFjaAAFYmFkZ2UAB2JhZGdl",
    "T24ACWJhZGdlVGV4dAAEYmFuZAADYm1wAAxib3R0b21NYXJnaW4ABmNkSXNBZAAIY2RJc1Bvc3QAB2NoYW5nZWQABmNoYXJBdAAF",
    "Y2xlYW4ACWNsaXBib2FyZAAIY29sbGFwc2UAE2NvbS5mYWNlYm9vay5saXRoby4AIGNvbS5mYWNlYm9vay5saXRoby5Db21wb25l",
    "bnRUcmVlABxjb20uZmFjZWJvb2subGl0aG8uTGl0aG9WaWV3AARjb3B5AAxjcmVhdGVCaXRtYXAAB2N1cnJlbnQAEWN1cnJlbnRU",
    "aW1lTWlsbGlzAAZkZWxldGUABWRpcnR5AARkcmF3AApkcmF3Qml0bWFwAAhkcmF3VGV4dAALZHJhd1RleHRSdW4ABmVxdWFscwAF",
    "ZmFpbHMADGZpbmRWaWV3QnlJZAAPZmluZFZpZXdXaXRoVGFnAAVmaXJzdAAHZm9yTmFtZQAGZm9yZ2V0AAJmeQADZ2V0AAlnZXRC",
    "b3R0b20ACmdldENoaWxkQXQADWdldENoaWxkQ291bnQACGdldENsYXNzABVnZXRDb250ZW50RGVzY3JpcHRpb24AEWdldERlY2xh",
    "cmVkRmllbGRzABJnZXREZWNsYXJlZE1ldGhvZHMAEWdldERlY2xhcmluZ0NsYXNzAAxnZXREZWNvclZpZXcACWdldEhlaWdodAAF",
    "Z2V0SWQAD2dldExheW91dFBhcmFtcwAHZ2V0TGVmdAATZ2V0TG9jYXRpb25PblNjcmVlbgANZ2V0TWFpbkxvb3BlcgAJZ2V0TWF0",
    "cml4ABFnZXRNZWFzdXJlZEhlaWdodAAJZ2V0TWV0aG9kAAxnZXRNb2RpZmllcnMAB2dldE5hbWUADmdldFBhY2thZ2VJbmZvABFn",
    "ZXRQYWNrYWdlTWFuYWdlcgAOZ2V0UGFja2FnZU5hbWUAEWdldFBhcmFtZXRlclR5cGVzAAlnZXRQYXJlbnQADWdldFJldHVyblR5",
    "cGUACGdldFJpZ2h0AA1nZXRTdXBlcmNsYXNzABBnZXRTeXN0ZW1TZXJ2aWNlAAZnZXRUYWcABmdldFRvcAAHZ2V0VHlwZQATZ2V0",
    "Vmlld1RyZWVPYnNlcnZlcgANZ2V0VmlzaWJpbGl0eQAIZ2V0V2lkdGgACWdldFdpbmRvdwAHaGFuZGxlcgAIaGFzaENvZGUAAmhp",
    "AAZoaWRkZW4ABGhpZGUAB2hpZGUgYWQACWhpZGUgcG9zdAAEaGludAAHaGludEJhZAAIaGludERvbmUAB2hpbnRFeGMABWhpbnRN",
    "AANoaXQAEGlkZW50aXR5SGFzaENvZGUAB2luc3BlY3QAB2luc3RhbGwACmludmFsaWRhdGUABmludm9rZQAHaXNBbGl2ZQAHaXNF",
    "eGFjdAAKaXNJbnN0YW5jZQAGaXNMaXN0AAdpc0xpdGhvAAZsYWJlbHMACGxhc3RMaXN0AAZsYXlvdXQABmxlbmd0aAAIbGlzdFBh",
    "c3MAAmxvAANsb2cABmxvZ1JvdwAIbWFrZVRleHQACW1hcFBvaW50cwAHbWVhc3VyZQADbWVtAAZtZW1BZGQABm1lbURlbAAHbWVt",
    "RmluZAAGbWVtSGFzAAZtZW1IaXQABG1lbU4ABm1lbW9yeQAMbmV3UGxhaW5UZXh0AAVub1JlbAAEbm9ybQAGbm9ybWFsAAlub3Jt",
    "YWxpemUAA29mZgAFb2ZmQXQAEW9uQWN0aXZpdHlDcmVhdGVkABNvbkFjdGl2aXR5RGVzdHJveWVkABBvbkFjdGl2aXR5UGF1c2Vk",
    "ABFvbkFjdGl2aXR5UmVzdW1lZAAbb25BY3Rpdml0eVNhdmVJbnN0YW5jZVN0YXRlABFvbkFjdGl2aXR5U3RhcnRlZAARb25BY3Rp",
    "dml0eVN0b3BwZWQAB29uQ2xpY2sACW9uUHJlRHJhdwAWb25WaWV3QXR0YWNoZWRUb1dpbmRvdwAYb25WaWV3RGV0YWNoZWRGcm9t",
    "V2luZG93AARwZW5kAAdwZW5kaW5nAAtwb3N0RGVsYXllZAAicmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAIcmVs",
    "YXlvdXQACHJlbWVtYmVyAA9yZW1vdmVDYWxsYmFja3MAF3JlbW92ZU9uUHJlRHJhd0xpc3RlbmVyAAZyZW5kZXIAB3JlcGxhY2UA",
    "BnJlcG9ydAAJcmVzdG9yaW5nAAdyb3dGYWlsAAVyb3dPawAHcm93UGFzcwADcnVuAAhydi1oaWRlcgAEc2VlbgANc2V0QWNjZXNz",
    "aWJsZQAIc2V0QWxwaGEAEnNldEJhY2tncm91bmRDb2xvcgAPc2V0TGF5b3V0UGFyYW1zABJzZXRPbkNsaWNrTGlzdGVuZXIACnNl",
    "dFBhZGRpbmcADnNldFByaW1hcnlDbGlwAAZzZXRUYWcAB3NldFRleHQADHNldFRleHRDb2xvcgALc2V0VGV4dFNpemUADXNldFZp",
    "c2liaWxpdHkABHNob3cAA3NpZwAGc21vb3RoAAVzcGxpdAAFc3RhcnQACnN0YXJ0c1dpdGgABnN0cmVhawALc3ViU2VxdWVuY2UA",
    "CXN1YnN0cmluZwALdG9Mb3dlckNhc2UACHRvU3RyaW5nAAV0b2FzdAAHdHJlZUJhZAAHdHJlZUNscwAIdHJlZURvbmUABnRyZWVG",
    "MQAGdHJlZUYyAAh0cmVlSGl0cwAGdHJlZUlkAAR0cmltAAN0eHQADHVwdGltZU1pbGxpcwAHdmFsdWVPZgAHdmVyc2lvbgALdmVy",
    "c2lvbk5hbWUABHdhbGsABHdpblcABnhmYWlscwAL4bqpbiBiw6BpIHZp4bq/dAAM4bqpbiBxdeG6o25nIGPDoW8AAAAAAwAAAAkA",
    "LAACAAAAAgAAADEAAgAJAAAAPQACAAIAAgACAAEAAQA8ABAAAAADAAAAAgACAAIAAAABAAAAHQAAAAEAAAAGAAAAAQAAADEAAAAC",
    "AAAALAA4AAYAAAA9AAIAAgABAAEAEAAEAAAADQABAAEAEAACAAAAMAADAAIAAAAgAB0AAQAAABcAAAABAAAABAAAAAIAAAAAAAAA",
    "AgAAAB0AKgABAAAAOgAAAAEAAAAiAAAAAgAAACwALAABAAAABQAAAAEAAAAJAAAAAQAAAB4AAAADAAAADQAPABAAAAACAAAAHQA8",
    "AAYAAAAxAAIAAgABAAEAEAAFAAAABQAiADAAHAAbAAAAAgAAAB0AAgAEAAAAMQABAAEAEAABAAAALwAAAAIAAAAdAD8AAgAAAAIA",
    "LwABAAAAHAAAAAkAAAAsAAIAAgACAAIAAQABADwAEAAAAAIAAAAdAB4AAwAAACwAAQABAAAAAgAAAAQAMQAEAAAADQARABIAEAAB",
    "AAAAMAAAAAEAAAAOAAAABgAAACwAAgACAAEAAQAQAAIAAAACAAIAAQAAAAAAAAAEAAAADQARABEAEAABAAAAAQAAAAEAAAAgAAAA",
    "AgAAADEAQAABAAAAAgAAAAEAAAADAAAAAQAAAD4AAAABAAAAGwAAAAEAAAA/AAAAAgAAAAQAFQADAAAAPQACAAIAAAACAAAALwBB",
    "AAMAAAACAAIADAAAAAQAAAACAAIAAgACAAEAAAAHAAAAAgAAADEAHQABAAAADQAAAAEAAAA8AAAAAQAAACwAAAAAAAAAAwABAAIA",
    "AAAAAAAAHAAAAHAQjQACACIAFgBxABEAAAAMAXAgDgAQAFsgDQAiADIAcBCbAAAAWyAUABMAQAAjAD8AWyAVAA4ACAACAAUAAAAA",
    "AAAAUQAAABQAAgACAW4gAAAHAAwAIAEgADgBRwAaAUABbiAVABAADAIgIyYAOAMKAB8CJgBbYgYAcBBCAAYADgAfACAAIgImAHAg",
    "OQByAG4gLgASABUDIEFuIDwAMgAS824gOwAyABQDAAAAmW4gKQAyABMDDAASRG5ULAAyNG4gKwBiACIDJQAS5BQFUwCAAHBAOABD",
    "VG4wMAAgA1tiBgBwEEIABgAOAAAAAQAAAAAAAAAAAAAAAgAAABIQDwAEAAEAAgAAAAAAAAAgAAAAVDAGADgAHQAiATIAcBCbAAEA",
    "GgJ9AG4goAAhAFIyDgBuIJ0AIQAaAhMAbiCgACEAbhCkAAEADAFuIDoAEAAOAAUAAQACAAAAAAAAACcAAABxEJkABAAMAG4QkgAA",
    "AAoBEmI0IRoAEwIUADYhFgBxEFgAAAAMABoB+QBuIJAAEAAKAjkCDAAaAWsBbiCQABAACgI5AgQAEgIPAhISDwIAAAUAAQACAAAA",
    "AAAAACcAAABxEJkABAAMAG4QkgAAAAoBEmI0IRoAEwIUADYhFgBxEFgAAAAMABoB+gBuIJAAEAAKAjkCDAAaAWoBbiCQABAACgI5",
    "AgQAEgIPAhISDwIAAAUAAQADAAAAAAAAACAAAABxEJkABAAMABMBCgATAiAAbjCTABACDAATAQ0AbjCTABACDABuEJIAAAAKARMD",
    "yAA3MQcAEgFuMJYAEAMMABEAAQAAAAAAAAAAAAAAAgAAABIQDwAGAAIAAwABAAAAAAAiAAAAbhCSAAUACgAUAcDUAQA3EAcAEgBu",
    "MJYABQEMBRoAuABuIAcABAAMAB8ACAAaAUABcSADAFEADAFuIAQAEAAOAA0AKP4AAAAAHwABAAEAIAAEAAIAAgAAAAAAAAAKAAAA",
    "cCBzADIACgA4AAUAcCBTAAIADgAIAAMAAwAAAAAAAABmAAAAEkBuIC8ABgATAQoAEhJLAgcBEwELAEQCBwE5AgoAEhJLAgcBEgNw",
    "MEoAZQMoGRIjMzIXAHEAEgAAAAsDhDMTBA0ARAQHBLFDEwSWADRDCQASEksCBwESA3AwSgBlA3EARgAAAAoAOAAyAG4QHQAGAAoB",
    "PQEsAG4QGgAGAAwAIAIfADgCJAAfAB8AEkJEAwcCOQMKAFIDBQASNEsDBwQSE0sDBwJ7EVIDBQAyExAAWQEFAG4gKgAGAG4QHgAG",
    "AAwDW1MLABITXFMJAA4ADwADAAMAAQAAAAAAsQAAAFXADwA5AI8AVMASADkAbwBVwRAAOQGHABIRXMEQABoBvABxEIUAAQAMAW4Q",
    "hwABAAwCISMSBBIFEgA1NDIARgYCBNgEBAFuEK0ABgAKB90IBwg5CPT/3QgHATgI8P9uELAABgAMB2IILgAzh+j/bhCvAAYADAch",
    "eBIpM5jg/2IJLQASCkYKBwozmtn/EhpGCgcKM5rU/9gFBQEHYCjPVMYUABIXMnUQABoHKQBuIKAAdgBuIJ0AVgATBwoAbiCcAHYA",
    "KDBbwBIAGgcoAG4goAB2AG4QrgAAAAwHbiCgAHYAEwcKAG4gnAB2AG4QrAAAAAwBbiCLANEACgI4AhQAEiIjIkEAcRCDAA4ADAMS",
    "BE0DAgRiAywAEhRNAwIEbjCxANACDgANAFLBEQDYAQEBWcERABMCCgA2Ifb/VMEUABoCJwBuIKAAIQBuEKcAAAAMAG4goAABABMA",
    "CgBuIJwAAQAo4QAABAAAAI0AAQABAJIBCwADAAMAAAAAAAAArwAAAG4QJAAJAAoAPAAEABIADwBuEBgACQAKAdoCAALbAgIFNSEE",
    "ABIQDwAUAwGg8H9uICAAOQAMAyA0PwA4BBEAHwM/ABMEDABEBAMEEjU0VAgAOAoEABIgDwASEA8AVIEIADgBCABuEAoAAQAKAjIC",
    "DgDaAgAC2wICBWIDAQBxMAkAIAMMAVuBCAAiAioAcCB3ABIA2gMACdsDA2RZIykA2gMAGdsDA2RZIycAcSBoACkADARuEJIABAAK",
    "AzgDFQBwIGoAmABUhRQAGgZ6AG4goABlAG4goABFABMGCgBuIJwAZQAoBHAgawCYAFQlKwBuEKQABQAMBVuFBwBUhBQAUiMoAD0D",
    "HQAaBQcAbiCgAFQAVCUlAG4goABUABoFGABuIKAAVABSJSYAbiCdAFQAEwUKAG4gnABUABIgDwA4CgkAGgUIAG4goABUABIgDwBS",
    "IyoAPAMEABIADwASEA8AAAAJAAEAAwAAAAAAAABKAAAAOQgEABIADwBxEFgACAAMAW4QkgABAAoCOAI8ABMDUAA2MjgAcQBPAAAA",
    "DAMaBKMAbiCUAEMADAMhNBIFNUUqAEYGAwXYBQUBbhCSAAYACgLYAgL/PQIWAG4gjwAmAAoAEwcqADNwDgASAG4wlgAGAgwGbiCV",
    "AGEACgA4AOH/KAduIJAAYQAKADgA2v8SEA8AEgAPAAUAAQACAAAAAAAAAEgAAABuEBkABAAKABQBCgACATMQEABuEDIABAAKAD0A",
    "OQBuECQABAAKAD0AMwASEA8AbhAyAAQACgASITQQKgASAW4gMQAUAAwCcRBOAAIACgI4Ah8AEhFuIDEAFAAMAnEQTgACAAoCOAIU",
    "AG4QjgAEAAwCbhCJAAIADAIaA7oAbiCVADIACgI5AgQAEhAPABIADwADAAEAAgAAAAAAAAATAAAAOQIEABIADwBuEI4AAgAMAG4Q",
    "iQAAAAwAGgG8AG4gkAAQAAoADwAAAAEAAAAAAAAAAAAAAAMAAAAaAKQAEQAAAAoAAgADAAAAAAAAAIgAAAASICMAPwBuIBwACQAS",
    "AUQBAAFSgiMANSF8AG4QJAAJAAoDsBM9A3UAVIETADKRVgBbiRMAVIEUABoCNgBuIKAAIQBuEI4ACQAMAhIEOAIYABJVNVQVAG4Q",
    "iQACAAwDbiCgADEAEwUgAG4gnABRAG4QigACAAwC2AQEASjpGgMRAG4goAAxAG4QMgAJAAoDbiCdADEAEgNuIDEAOQAMAzgDGABu",
    "EBoAAwAMAzgDEgAaBBQAbiCgAEEAbhCOAAMADANuEIkAAwAMA24goAAxABMDCgBuIJwAMQBuEDIACQAKARICNRIWAG4gMQApAAwD",
    "2AICATgD+P9uECMAAwAKBBMFCAAyVPD/cDBsAJgDKOsOAAcAAwADAAAAAAAAAEwAAABUQBQAbiCgAFAAGgEWAG4goAAQAG4QIQAG",
    "AAoBbiCdABAAGgESAG4goAAQAG4QGAAGAAoBbiCdABAAGgEXAG4goAAQAG4QIwAGAAoBbiCdABAAVEEHADgBDQAaAhAAbiCgACAA",
    "biCgABAAEgFbQQcAEwEKAG4gnAAQAG4QowAAAAoBEwIwdTchCAASARMDmDpuMKIAEAMOAAcAAwADAAAAAAAAAB0AAAA4BRwAPQYa",
    "AHAwVABUBgoAVEEVADsADgBSQhYA3QMCH9oAAwLYAgIBWUIWAEsFAQDYAAABSwYBAA4AAAAGAAIAAAAAAAAAAAAYAAAAOAUXAFRA",
    "FQASARMCQAA1IRAARAIAATNSCQASA0sDAAHYAgEBSwMAAtgBAQIo7w4ACQADAAAAAAAAAAAAEwAAADgHEQBUYBUAEgETAkAANSEK",
    "AEQCAAEzcgMADwHYAQECKPUS8A8AAAAIAAMAAwAAAAAAAAAeAAAAcDBUAGUHCgA6ABgAVFEVANgAAAFEAQEAkQIHAXEQjAACAAoC",
    "2wMBMhJENUMDABJDNjIEABIQDwASAA8ABQACAAMAAAAAAAAAEwAAAHAgcwBDAAoAOAANAG4QGAAEAAoBPQEHAHAwVQADAQoADwAS",
    "AA8AAAABAAAAAAAAAAAAAAACAAAAEhAPAAcAAQACAAAAAAAAAEwAAABuEJgABgAMAGIBLwBxILIAEAAMAG4QkgAAAAoBIgIyAHAQ",
    "mwACABIDNRMpAG4gjwAwAAoE2AMDARMFoAAzVAUAEwQgACgXEwULIDRUFAATBS4gN1QIABQF//4AADJU5v8oCRMFDyA3VOH/EwUo",
    "IDVU3f9uIJwAQgAo2G4QpAACAAwAbhCYAAAADABiATAAbiCXABAADAARAAYAAwACAAAAAAAAABoAAAATAAoARAEFADgBCAASAUsB",
    "BQBuIC8AFAAScEQBBQA4AQoAEgFLAQUAFQKAP24gKAAkAA4ABgADAAIAAAAAAAAADQAAABJwRAEFADkBCQASEUsBBQASAm4gKAAk",
    "AA4AAAAHAAEABQABAAAAAABWAAAAVGALABIBW2ELAFViFwA5Ak0AcQBwAAAACgI4AkcAIAIdADgCQwAfAB0AbhAkAAAACgJuEBgA",
    "AAAKAz0CNwA9AzUAFQQAQLZCtkNuMCcAIANuEBsAAAAKAm4QIQAAAAoDbhAfAAAACgRuEBYAAAAKBW5VJgAgQxIQDwANABIRXGEX",
    "AFRhFAAaAngAbiCgACEAbhCnAAAADABuIKAAAQATAAoAbiCcAAEAEgAPABUAAAAmAAEAAQA9AAUAAgADAAAAAAAAAA4AAABwIHMA",
    "QwAKADgACQBuEBgABAAKAXAwUgADAQ4ABAACAAIAAQAAAAAADAAAAG4gFAAyABoAAAARAA0AbhCnAAAADAARAAAAAAADAAEAAQAG",
    "AAYAAgADAAAAAAAAAKQAAAAiADIAcBCbAAAAGgF+AG4goAAQAHEApQAAAAsCbjCeACADGgEEAG4goAAQAGIBBABuIKAAEAAaAQkA",
    "biCgABAAYgECAG4goAAQABoBDgBuIKAAEABgAQMAbiCdABAAGgEDAG4goAAQAG4QBgAFAAwBbiCgABAAGgEGAG4goAAQAHEQdAAF",
    "AAwBbiCgABAAGgECAG4goAAQAG4QjgAFAAwBbhCJAAEADAFuIKAAEAAaAQUAbiCgABAAUkEOAG4gnQAQABoBCwBuIKAAEABSQQwA",
    "biCdABAAGgEPAG4goAAQAFJBJABuIJ0AEAAaAQwAbiCgABAAUkERAG4gnQAQABoBDQBuIKAAEABSQSIAbiCdABAAGgEKAG4goAAQ",
    "AHEARgAAAAoBbiChABAAGgEBAG4goAAQAFRBFABuIJ8AEABuEKQAAAAMABEABgACAAIAAAAAAAAAHAAAAFJADADYAAABWUAMABQA",
    "AaDwf24gIAAFAAwBIBI/ADgCDAAfAT8AEwIMAEQDAQLYAwMBSwMBAg4ABgACAAIAAAAAAAAAFgAAABIAWUAMABQAAaDwf24gIAAF",
    "AAwBIBI/ADgCCQAfAT8AEwIMABIDSwMBAg4ADgADAAMAAAAAAAAA5wEAABQAAaDwf24gIAANAAwBIBI/ADkCCQATAg4AIyE/AG4w",
    "LQANAR8BPwBVshsAOAIGAHAwbgDbAQ4AcQASAAAACwKEIhIkRAMBBDkDBABLAgEEElMjMz8AcSBvAD0AEgREBQME2gUFHxIURAYD",
    "BLBlEiREBgMEEhREBwEEEhgzhxUAOQYMAG4QGAANAAoIEwkIAEQJAQkymB4AEgdLBwEEEiRLAgEEKBcSKDOHFQASCEQIAQgyWAwA",
    "EghLBQEIEkdLBwEEEiRLAgEEKAVwMEkA2wEOABIIW7gHADkGXQASSDKHWgBuEBYADQAKCD0IVABuECEADQAKCG4QMwAMAAoJNZhK",
    "AHAgVgDbAAoIOAhEABJYRAkBCDkJCAASGUsJAQhuIBMAvQASSBIZSwgBCRIISwUBCBIoSwIBCG4QGAANAAoIEwkIAEsIAQkTCAsA",
    "RAkBCDkJCQASKUsJAQgTCA0ASwIBCHAwSQDbAVK4IgDYCAgBWbgiAFK4DgDYCAgBWbgOAHAQQgALABoIJgBwMFEAiw0OADkGJAAS",
    "SEQIAwg5CAcAEjhECAMIOQgaABJIM4cGAHAwSQDbAQ4AEihLAgEIEnhECQEIOAkKABIJSwkBCBUKgD9uICgArQAOABJYRAkBCDkJ",
    "CAASGUsJAQhuIBMAvQBuEBgADQAKCBMJCABLCAEJOQazAG4QIQANAAoIbhAkAA0ACgnaCgkZ2woKZLCKbhAzAAwACgQ9CokANUiH",
    "ADoIcgA2SnAAEgRwMEsA2wQKBBIoMoSSABIYM4Q7ABMICQBECQEIOQkTAEsCAQgSSDOHBgBwMEkA2wEOAHAwWQDbARI4EhRLCAEE",
    "DgCRCQIJEwoIADWpAwAOAHAwbgDbARJIM4cFAHAgSADbABIYEhRLCAEEEgRLBQEEEwQJABIISwgBBBoIdwBwMFEAiw0OABJIM4cG",
    "AHAwSQDbAQ4AEihECAEIkQgCCBMELAE3SBIAcDBuANsBEhgSFEsIAQQSBEsFAQQaCCQAcDBRAIsNDgBwMGUA2wFSuBoA2AgIAVm4",
    "GgAOABJkRAgBBJEIAggTCVAANJgMAEsCAQQSBHAwSwDbBAoEEigyhBkAEihLAgEIEwgJABIJSwkBCBJIM4cGAHAwSQDbAQ4AcDBZ",
    "ANsBEjgSFEsIAQQOABJIMocZADgGBgAaCKIAW7gHAHAgZwDbABoIJQBwMFEAiw1SuA4A2AgIAVm4DgBwEEIACwATCAsARAkBCBIq",
    "M6kFABIJSwkBCHAwSQDbARMICQASCUsJAQgSKBIUSwgBBBIESwUBBA4AAAAHAAMAAwAAAAAAAAA2AAAAEwALAEQBBgA4AQsAEgJL",
    "AgYAEhIzIQUAcDBKAFQCcDBZAFQGEkBEAQYAOAEgABIBSwEGAG4QGgAFAAwAIAEfADgBFQAfAB8AEjFEAQYBWQEFAG4gKgAFAG4Q",
    "HgAFAAwBW0ELABIRXEEJAA4ABwACAAIAAAAAAAAAWQAAADgFWAASEEQBBgDYAQEBSwEGABMC0Ac2IU0AIFIaADkCBgAgUhkAOAIF",
    "ABJCSwAGAm4QFwAFAAwCOAIlAHEQQwACAAoDOAMGABIkSwAGBCgKcRBEAAIACgM4AwUAEjRLAAYEcRCZAAIADAJuEJEAAgAKAhID",
    "RAQGA9oEBB+wJEsEBgMgUiAAOAIVAB8FIABuEDIABQAKAhIDNSMMAG4gMQA1AAwEcSBvAGQA2AMDASj1DgAAAAEAAAAAAAAAAAAA",
    "AAIAAAASEA8AAgABAAIAAAAAAAAACQAAACIAKABwED8AAABuIAIAAQAOAAAABAACAAMAAQAAAAAACwAAABIQcTA9ADIADABuED4A",
    "AAAOAA0AKP4AAAAAAAAIAAEAAQAJAAsAAgACAAEAAAAAAKMAAABxAFcAAAAKADgAhABVkB0AOQCAAFSQHgA5AFwAVZEfADkBeAAS",
    "EVyRHwAaAbwAcRCFAAEADABuEIYAAAAMASESEgMSBDUjLwBGBQED2AMDAW4QqgAFAAwGbhCJAAYADAYaB7sAbiCQAHYACgY4Buz/",
    "bhCpAAUACgbdBgYIOQbk/xIWbiCrAGUAOQQHAFuVIADYBAQBKNgSFjNk/P9blSEAKPhUkRQAGgKCAG4goAAhAG4gnQBBABMCCgBu",
    "IJwAIQA5BAYAEhFckR0AKCNbkB4AbiCLAKAACgE4ARwAVJEgAG4gqAChAAwCOQIMAFSRIQA4ARAAbiCoAKEADAI4AgoAcRCmAAIA",
    "CgE5AQMAEhEPARIADwANABIRXJEdAFSRFAAaAoEAbiCgACEAbhCnAAAADABuIKAAAQATAAoAbiCcAAEAEgAPAAAACgAAAH4AAQAB",
    "AIoBBAABAAMAAQAAAAAAFAAAAG4QBQADAAwAbhAGAAMADAESAm4wCAAQAgwAVAAAABEADQAaABoAEQAAAAAADwABAAEAEAAIAAMA",
    "AwAAAAAAAAAwAAAAOAYvABMAKAA1BysAbhAjAAYACgA5ACUAIGAgADgAIQAfBiAAcRBNAAYACgA4AAYAcCBQAGUADgBuEDIABgAK",
    "ANgBBwESAjUCDABuIDEAJgAMA3AwdQA1AdgCAgEo9Q4AAwADAAAAAAAAAAAAAQAAAA4AAAACAAIAAAAAAAAAAAABAAAADgAAAAUA",
    "AgACAAEAAAAAACQAAABUMA0AbiAQADAAbhABAAQADABuEDcAAAAMAG4QIgAAAAwAbhA1AAAACgE4AQUAbiA2ADAAVDAKADNABQAS",
    "AFswCgAOAA0AKPcFAAAAFQABAAEAIgAFAAIAAgABAAAAAAA0AAAAWzQKABIAWTAcAFswEwBuEAEABAAMAG4QNwAAAAwAbhAiAAAA",
    "DAFuEDUAAQAKAjgCCABuIDYAMQBuIDQAMQBxAEEAAAAKATgBBQBwIEAAQwBuECUAAAAOAA0AUjEkANgBAQFZMSQAKPgHAAAAJAAB",
    "AAEALAADAAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAAAgACAAAAAAAAAAAAAQAAAA4AAAAGAAIAAgABAAAA",
    "AAApAAAAVEAKADgAJABwIGkABAAMAXEgRwAQACIBMgBwEJsAAQAaAn0AbiCgACEAUkIOAG4gnQAhABoCFQBuIKAAIQBuEKQAAQAM",
    "AXEgcgAQAA4ADQAo/gAABAAAACIAAQABACcABwABAAQAAQAAAAAAxAAAAFJgDAASUTYQXgBSYCQANhBaAFVgGAA5ADsAVGAKADgA",
    "NQBuEAEAAAAMAG4QNwAAAAwAbhAkAAAACgE9AScAWWEjABIFEgJcYgkAWWIaAFtiCwBwMHUABgJSYRoAPQEMAFRhDQBuIBAAYQAW",
    "AzIAbkAPAGFDEgFZYSQAVWEJADkBTQASAVlhHAASEA8AcQASAAAACwKEIlJjGQCxMhMDECc0MvT/EgJcYhgAWWIMAFliJABUYhQA",
    "GgMcAG4goAAyACitVWAYADkA4/8SEFxgGABxABIAAAALAoQiWWIZAFRiFAAaA3sAbiCgADIAXGAbAFRgCgA4AA4AbhABAAAADABu",
    "EDcAAAAMABICcDB1AAYCEgBcYBsAKLkSMTUVCwBwEGYABgAKATgBBQDYBQUBKItSYRwA2AEBAVlhHAASMjchBwASAVlhHAASEA8A",
    "EgAPAA0AUmEkANgBAQFZYSQAVGEUABoCIABuIKAAIQBuEKcAAAAMAG4goAABABMACgBuIJwAAQASEA8AAAAAAKgAAQABAKgBAgAC",
    "AAAAAAAAAAAAAQAAAA4AAAAGAAIAAwABAAAAAAAhAAAAFAABoPB/biAgAAUADAEgEj8AOAIVAB8BPwBwMG4AVAESABMCDgA1IAsA",
    "ElMyMAUAEgNLAwEA2AAAASj2DgANACj+AAAAAAAAHgABAAEAHwADAAEAAQABAAAAAAASAAAAVCAKADgADQBuEAEAAAAMAG4QNwAA",
    "AAwAbhAlAAAADgANACj+BAAAAAsAAQABABAABgABAAMAAQAAAAAAIQAAABoAqgBxEIUAAAAMABoBUgESEiMiQAASAxwEBgBNBAID",
    "bjCIABACDAESAhITIzNBABIETQUDBG4wsQAhAw4ADQAo/gAAAAAAAB4AAQABAB8AAwACAAIAAAAAAAAACwAAAHAgCwAhACIAMgBw",
    "EJsAAABbECsADgAAAAoABAACAAAAAAAAAE8AAAA5BwMADgBuEAwABgAMABIhIxE+ABICSwgBAhISSwkBAm4gDQAQAEQCAQKHIlJj",
    "JwA2MjYAUmQqANgEBAFZZCoAUmMpADQyLABxEEUABwAMA1RkKwBuEKMABAAKBRMALAE1BQoAbiCgADQAEwB8AG4gnAAEAHEQTAAD",
    "AAoEOAQQAFJkKADYBAQBWWQoAFRlJQA5BQYAW2MlAFliJgAOAAAABQAFAAAAAAAAAAAAAQAAAA4AAAAEAAQAAAAAAAAAAAABAAAA",
    "DgAAAAUABQAAAAAAAAAAAAEAAAAOAAAABQAFAAAAAAAAAAAAAQAAAA4AAAAJAAcABAABAAAAAAAKAAAAcjCEAEMFDABwQHgAAnYO",
    "AA0AKP4AAAAABwABAAEACAAGAAUABAABAAAAAAAGAAAAcEB4ACFDDgANACj+AAAAAAMAAQABAAQACQAHAAQAAQAAAAAACgAAAG4w",
    "lgBDBQwAcEB4AAJ2DgANACj+AAAAAAcAAQABAAgACQAHAAQAAQAAAAAACgAAAHEwmgBDBQwAcEB4AAJ2DgANACj+AAAAAAcAAQAB",
    "AAgADAAKAAQAAQAAAAAACgAAAHIwhABDBQwAcEB4AAKYDgANACj+AAAAAAcAAQABAAgADAAKAAQAAQAAAAAACgAAAHEwmgBDBQwA",
    "cEB4AAKYDgANACj+AAAAAAcAAQABAAgAHysMBgIBAgECAQIBAgECAQIBEgECAQIBAgECAQIBAgESAQIBAgECAQIBAgECAQIBAgEC",
    "AQIBAgECAQIBAgECAQI/goAElFMBAtxTAQmQVQECpFUBCfRVAQnUVgEJtFcBCYRYAQqYWAEC+FgBApxZAQL4WgEC+F0BCehgAQqM",
    "YgEKrGMBCeRjAQL8YwECnGYBAsRnAQKQaAEC0GgBAohpAQLUaQEJjGoBCaBqAQLIawwCjGwBArhsAQKAbgEKrG4BAuBuAQK4cQEC",
    "gHIBArxyAgKcegEKmHsBCdx8AQnwfAEKlH0BAsh9AQqsgAEBAvCAAVoB4IEBAQH0gQEBAYiCAQEB7IIBAQHwgwEBAYSEAQEBmIQB",
    "AQGshAEBAZyFAQEBwIgBAQHUiAEJAbSJAQAAAQB2CfSJAQAHAgolAQEBAQEBAQEBAQEBEXeBgATUigEBAvyKAXkBrIwBAQHAjAEB",
    "AdSMAQEB6IwBAQH8jAEBAayNAQEB1I0BAQGEjgEBAbSOAQEB5I4BAAANAAAAAAAAAAEAAAAAAAAAAQAAAGwBAABwAAAAAgAAAEUA",
    "AAAgBgAAAwAAAG0AAAA0BwAABAAAADEAAABQDAAABQAAALMAAADYDQAABgAAAAMAAABwEwAAAiAAAGwBAADQEwAAARAAAD0AAAA8",
    "JwAAAxAAAAEAAACQKQAAASAAAEQAAACUKQAAACAAAAMAAACTRwAAABAAAAEAAAAcSQAA",
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
        "\"Hide ad\" button, and makes that row invisible and takes its height out of the list. Also hides " +
        "\"Suggested for you\" posts (\"Gợi ý cho bạn\") and paid partnership posts (\"Mối quan hệ tài trợ ...\") (one option). Works at run " +
        "time on what is on the screen, so it does not depend on obfuscated names and works on the Dextricks " +
        "(Android 8) builds. Adds no file to the apk, no resource, no manifest change. Not for stories yet. " +
        "Remembers the ads it has hidden, so scrolling back up does not show them again. " +
        "Only feed posts are touched (never Reels, Page / profile or other lists). " +
        "A small badge at the bottom left shows how many posts were hidden; tap it to copy a log.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val hideSuggested by booleanOption(
        name = "Hide suggested and partnership posts",
        description = "Also hides posts whose header says \"Suggested for you\" (\"Gợi ý cho bạn\", \"Đề xuất cho bạn\" and the " +
            "translations) and posts with a \"Paid partnership\" / \"Mối quan hệ tài trợ\" line under the page name. " +
            "Turn off to hide only \"Sponsored\" posts.",
        default = true,
    )

    val rememberAds by booleanOption(
        name = "Hide again when scrolling back up",
        description = "EXPERIMENTAL. Remembers the ads that were hidden (not their content: only a number that identifies the " +
            "item and the row height) so that an ad you scroll back up to is hidden at once instead of showing for a moment " +
            "until its header is on the screen. Off by default: turn it on to test. If scrolling stalls or posts do not load, turn it off.",
        default = false,
    )

    val showBadge by booleanOption(
        name = "Show counter badge",
        description = "Shows a small dark 'RV hider: N hidden' button at the bottom left of the screen; a tap copies the " +
            "log to the clipboard. Turn it on while testing (the log is needed to find problems), off for daily use.",
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

        // ---- 3. Label list and the options go into Core ----------------------------------------------------------
        val core = added.first { it.type == CORE }
        val coreMethods = classDefs.getOrReplaceMutable(core).methods
        val labelsMethod = coreMethods
            .firstOrNull { it.name == "labels" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: throw PatchException("Hide native feed ads: Core.labels() not found")
        val labels = LABELS_SPONSORED +
            (if (hideSuggested != false) "|$LABELS_SUGGESTED|$LABELS_PARTNERSHIP" else "")
        labelsMethod.replaceInstruction(0, "const-string v0, \"${smaliString(labels)}\"")

        if (rememberAds != true) {
            val m = coreMethods.firstOrNull { it.name == "memory" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.memory() not found")
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
                "suggested+partnership=${hideSuggested != false}, remember=${rememberAds == true}, badge=${showBadge == true}",
        )
    }
}
