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
 *   8. when a row is judged an ad, the ITEM is remembered (up to 32 items): the identity of the Litho ComponentTree that the
 *      LithoView shows (the field that holds it has an obfuscated name but a fixed type, com.facebook.litho.ComponentTree, so it is
 *      found by its type; Litho's RecyclerBinder keeps one tree per item) together with the height of the row. When the user scrolls
 *      back UP and RecyclerView binds that item to a view again, the row is hidden at once, in the frame in which it comes into the
 *      list, although its header is still above the list. It is hidden as "checked again": when its header is on the screen it is
 *      read as usual; if it turns out to be an ordinary post it is shown and forgotten. Identity AND height (2%) must agree. If the
 *      field is not found / not readable, nothing is remembered and everything else works as before. The log has "TREE fields=N" and
 *      "HIDE by remembered item".
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

/** "Suggested for you" in many languages. Same rules. Added when the option "Hide suggested posts" is on. */
private const val LABELS_SUGGESTED = "suggested for you|recommended for you|مقترح لك|مقترحات لك|предложения за вас|navrhované pro vás|vorschläge für dich|für dich empfohlen|προτεινόμενα για εσάς|sugerencias para ti|sugerido para ti|ehdotuksia sinulle|suggestion pour vous|suggestions pour vous|הצעות עבורך|disarankan untuk anda|contenuti suggeriti per te|suggerimenti per te|おすすめ|ieteikumi jums|voorgesteld voor jou|proponowane dla ciebie|sugestões para ti|sugestões para você|рекомендуем вам|предлагаемое для вас|senin için önerilenler|рекомендовано для вас|gợi ý cho bạn|đề xuất cho bạn|为你推荐|推荐给你|為你推薦|推薦給你"

/** "Paid partnership ..." labels: a star at the end means that the drawn text only has to START with it. Added when the option
 *  "Hide paid partnership posts" is on. */
private const val LABELS_PARTNERSHIP = "paid partnership*|mối quan hệ tài trợ*|mối quan hệ đối tác trả phí*|quan hệ đối tác trả phí*|hợp tác trả phí*|colaboración pagada*|colaboración de pago*|partenariat rémunéré*|bezahlte partnerschaft*|parceria paga*|parceria paga com*|partnership a pagamento*|collaborazione a pagamento*|kemitraan berbayar*"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val ADHIDE_DEX = listOf(
    "ZGV4CjAzOADSQ/Y2YH9jexCsp2yAcnMYSNbH0Npqee/IRgAAcAAAAHhWNBIAAAAAAAAAAChGAABiAQAAcAAAAEUAAAD4BQAAbQAA",
    "AAwHAAAuAAAAKAwAALAAAACYDQAAAwAAABgTAABQMwAAeBMAAHgTAAB6EwAAfhMAAIoTAACREwAAmxMAAKUTAACpEwAAuhMAANcT",
    "AADjEwAA8BMAAPoTAAAOFAAAFhQAAB4UAAAqFAAALxQAADgUAAA+FAAAbBQAAHMUAAB6FAAAgBQAAIgUAACLFAAAlBQAAJcUAACb",
    "FAAAphQAAKwUAAC4FAAAuxQAAMIUAADqFAAA8BQAAAkVAAAUFQAAIhUAADoVAAA9FQAAQRUAAEYVAABKFQAATxUAAFIVAABVFQAA",
    "WRUAAF4VAABiFQAAZxUAAG0VAAB0FQAAeBUAAHwVAACBFQAAhxUAAIwVAACSFQAAlhUAAK4VAADkFQAA/xUAABsWAAA/FgAAWhYA",
    "AHwWAAChFgAAwxYAAN4WAAD5FgAAFBcAAC4XAABHFwAAYRcAAH0XAACRFwAAphcAALwXAADRFwAA6xcAAAcYAAAjGAAAVBgAAHkY",
    "AACOGAAAtRgAAOIYAAD8GAAAFxkAAEoZAABrGQAAghkAAK0ZAADIGQAA4BkAAA8aAAA/GgAAbRoAAIIaAACcGgAArxoAAMEaAADV",
    "GgAA6xoAAP8aAAAaGwAALhsAAEUbAABXGwAAchsAAI4bAACrGwAAwxsAANcbAADeGwAA4xsAAOcbAADyGwAA+xsAAAQcAAAKHAAA",
    "FhwAACgcAAAyHAAAOxwAAEYcAABUHAAAWhwAAF0cAABhHAAAZRwAAGocAABwHAAAdxwAAHwcAACAHAAAhhwAAI0cAACSHAAAmxwA",
    "AKccAACsHAAAshwAALkcAAC+HAAAwhwAAMUcAADKHAAAzhwAANMcAADXHAAA2xwAAN8cAADzHAAACB0AAB0dAAA5HQAAVh0AAGgd",
    "AABsHQAAex0AAIAdAACFHQAApR0AALsdAADEHQAA8R0AAPkdAAABHgAACB4AABEeAAAcHgAAIh4AACceAAA1HgAAPR4AAEceAABQ",
    "HgAAWB4AAF8eAABqHgAAdB4AAIkeAACrHgAAyR4AAM8eAADdHgAA5h4AAPkeAAABHwAACB8AAA4fAAAaHwAAJB8AADEfAAA5HwAA",
    "QB8AAE4fAABfHwAAZh8AAG8fAAB3HwAAex8AAIAfAACLHwAAlx8AAKYfAACwHwAAxx8AANofAADuHwAAASAAAA8gAAAaIAAAISAA",
    "ADIgAAA7IAAAUCAAAF8gAABqIAAAfSAAAIggAACWIAAAnyAAAK8gAADCIAAA0iAAAOUgAADwIAAA/yAAAAkhAAAYIQAAKiEAADIh",
    "AAA6IQAAQyEAAFghAABnIQAAcSEAAHwhAACFIQAAjyEAAJMhAACbIQAAoSEAAKohAAC1IQAAuyEAAMQhAADOIQAA1SEAANohAADs",
    "IQAA9SEAAP4hAAAKIgAAEiIAABsiAAAkIgAAMCIAADgiAABBIgAASSIAAFMiAABbIgAAYyIAAG0iAABxIgAAdiIAAH4iAACIIgAA",
    "kyIAAJwiAAChIgAAqSIAALEiAAC6IgAAwiIAAMoiAADQIgAA3iIAAOUiAADrIgAA8yIAAP4iAAADIwAAFiMAACsjAAA9IwAAUCMA",
    "AG0jAACAIwAAkyMAAJwjAACnIwAAvyMAANkjAADfIwAA6CMAAPUjAAAZJAAAIyQAAC0kAAA+JAAAVyQAAF8kAABoJAAAcCQAAHsk",
    "AACEJAAAiSQAAJMkAACZJAAAqCQAALIkAADGJAAA1yQAAOskAAD3JAAAByUAAA8lAAAYJQAAJiUAADMlAABCJQAASCUAAE0lAABV",
    "JQAAXCUAAGMlAABvJQAAdyUAAIQlAACPJQAAnCUAAKYlAACtJQAAtiUAAL8lAADJJQAA0SUAANklAADjJQAA6yUAAPElAAD2JQAA",
    "BCYAAA0mAAAWJgAAIyYAACkmAAAvJgAAQSYAABoAAAAfAAAAJwAAACwAAAA7AAAAPAAAAD0AAAA+AAAAPwAAAEAAAABBAAAAQgAA",
    "AEMAAABEAAAARQAAAEYAAABHAAAASAAAAEkAAABKAAAASwAAAEwAAABNAAAATgAAAE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUA",
    "AABWAAAAVwAAAFgAAABZAAAAWgAAAFsAAABcAAAAXQAAAF4AAABfAAAAYAAAAGEAAABiAAAAYwAAAGQAAABlAAAAZgAAAGcAAABo",
    "AAAAaQAAAGoAAABrAAAAbAAAAG0AAABuAAAAbwAAAHAAAABxAAAAgAAAAJIAAACWAAAAlwAAAJgAAACZAAAAmgAAAJsAAACcAAAA",
    "nQAAABsAAAAAAAAAJCgAACcAAAACAAAAAAAAACgAAAACAAAAJCgAACkAAAACAAAA8CcAACoAAAACAAAAjCYAACsAAAACAAAAXCcA",
    "ACoAAAACAAAAcCcAACwAAAADAAAAAAAAADgAAAAHAAAACCcAADYAAAAKAAAAYCYAAC0AAAALAAAAAAAAADIAAAANAAAAaCgAAC0A",
    "AAAPAAAAAAAAAC0AAAAXAAAAAAAAAC0AAAAdAAAAAAAAADAAAAAdAAAAJCgAADUAAAAdAAAAcCcAAC0AAAAeAAAAAAAAAC0AAAAh",
    "AAAAAAAAAC0AAAAjAAAAAAAAAC0AAAAkAAAAAAAAADkAAAAnAAAAVCYAADoAAAArAAAAmCgAAC0AAAAsAAAAAAAAADEAAAAsAAAA",
    "8CcAAC0AAAAtAAAAAAAAADUAAAAtAAAAnCYAADAAAAAvAAAAJCgAADUAAAAvAAAAcCcAADgAAAAvAAAAYCgAADUAAAAvAAAAnCYA",
    "AC0AAAAxAAAAAAAAAC8AAAAxAAAA6CYAADEAAAAxAAAA8CcAADUAAAAxAAAA4CYAADUAAAAxAAAAGCcAADgAAAAxAAAA8CYAADUA",
    "AAAxAAAAoCgAADgAAAAxAAAApCYAADUAAAAxAAAAcCcAADUAAAAxAAAAnCYAADUAAAAxAAAA+CYAADcAAAAxAAAAVCgAAC4AAAAy",
    "AAAA+CcAADAAAAAyAAAAJCgAADEAAAAyAAAA8CcAADQAAAAyAAAALCgAADUAAAAyAAAAoCgAADUAAAAyAAAAnCYAADoAAAAyAAAA",
    "mCgAADgAAAA3AAAAHCgAAIAAAAA7AAAAAAAAAIEAAAA7AAAADCgAAIIAAAA7AAAAJCgAAIMAAAA7AAAA8CcAAIQAAAA7AAAAgCYA",
    "AIUAAAA7AAAAdCgAAIYAAAA7AAAAgCcAAIcAAAA7AAAA4CYAAI0AAAA7AAAATCgAAI0AAAA7AAAAvCcAAIcAAAA7AAAAECcAAIcA",
    "AAA7AAAAlCYAAIcAAAA7AAAAgCgAAIcAAAA7AAAAGCcAAIcAAAA7AAAAkCgAAIkAAAA7AAAAvCYAAI4AAAA7AAAAKCcAAI8AAAA7",
    "AAAAACgAAI8AAAA7AAAAxCcAAIcAAAA7AAAA2CcAAIcAAAA7AAAA2CYAAIcAAAA7AAAAPCgAAIcAAAA7AAAAiCcAAIcAAAA7AAAA",
    "jCYAAIoAAAA7AAAAXCcAAI0AAAA7AAAAqCcAAJAAAAA7AAAANCcAAI0AAAA7AAAAeCcAAIcAAAA7AAAAICcAAIcAAAA7AAAAFCgA",
    "AI0AAAA7AAAA0CYAAIcAAAA7AAAAACcAAIcAAAA7AAAAoCgAAIgAAAA7AAAAsCcAAIsAAAA7AAAA4CcAAIwAAAA7AAAAkCcAAIcA",
    "AAA7AAAAcCcAAIcAAAA7AAAA0CcAAIkAAAA7AAAAZCcAAIsAAAA7AAAAPCcAAI0AAAA7AAAAiCgAAJEAAAA7AAAAmCgAAIsAAAA7",
    "AAAArCYAAIwAAAA7AAAAaCYAAIcAAAA7AAAANCgAAIcAAAA7AAAARCgAAJIAAAA8AAAAAAAAAJMAAAA8AAAA8CcAAJQAAAA8AAAA",
    "jCYAAJQAAAA8AAAAFCgAAJQAAAA8AAAAoCgAAJQAAAA8AAAAcCcAAJUAAAA8AAAAyCYAAJQAAAA8AAAAnCYAAC0AAABAAAAAAAAA",
    "ADUAAABCAAAAnCYAAC0AAABDAAAAAAAAAC0AAABEAAAAAAAAAAoAMQBdAQAADAAMABkAAAATADEAdgAAABMAAgB8AAAAFAAxAHIA",
    "AAAfAAIArgAAACgAJgCpAAAAKAAxAKwAAAAoAA0ArQAAACgAPACxAAAAKAAEALsAAAAoACEAvgAAACgAAgDEAAAAKAAWAPAAAAAo",
    "AAIA8wAAACgAPAD4AAAAKAA8APkAAAAoADcA+gAAACgAHQAHAQAAKAAyAAwBAAAoAD8AEQEAACgAAgAXAQAAKAA8ABkBAAAoADwA",
    "HQEAACgAAgAqAQAAKAA8ADQBAAAoAAIASwEAACgAPABRAQAAKAAtAFIBAAAoADwAUwEAACgANgBUAQAAKAA2AFUBAAAoAAIAVgEA",
    "ACgAAgBfAQAAKgAxAMcAAAAqAAIAygAAACoAAgDyAAAAKgACAPsAAAAqAAIACwEAACoAAgA4AQAAKgAyAFkBAAArACsAIAAAACsA",
    "LQB/AAAANQAtAH8AAAA4ADgAcwAAADoAOgB4AAAABAAPAMUAAAAEABQA7wAAAAYAPQAsAQAABwAIABgBAAAIAD8APwEAAAkACgDh",
    "AAAACQAfAOIAAAAJAB4A6AAAAAsACQDgAAAADQALALoAAAANAAEA7gAAAA4AQQAXAAAADgAMANsAAAAPAF8ADwEAABYARwAXAAAA",
    "FgBnACsBAAAWAFgALwEAABcADQDaAAAAGAAHAFoBAAAdAEgAowAAAB0ARgC/AAAAHQAQAMYAAAAdAAEAzAAAAB0AFwDQAAAAHQAB",
    "ANUAAAAdAAEA1gAAAB0AEQDXAAAAHQABANgAAAAdAGAA2QAAAB0AAQDcAAAAHQASAOQAAAAdAAEA5gAAAB0AGwDpAAAAHQABAOoA",
    "AAAdABMA7AAAAB0AAQDtAAAAHQABAO4AAAAdADMA/wAAAB0AOAAIAQAAHQA2ABABAAAdADQAOgEAAB0ANQA7AQAAHQBPADwBAAAd",
    "AEkAPQEAAB0AOAA+AQAAHQA5AEABAAAdAFcAQAEAAB0ANQBEAQAAIABMAKUAAAAgAA8AzQAAACAAAQDOAAAAIAABANUAAAAjAFIA",
    "pAAAACMAYQABAQAAIwBSADABAAAkAA4A1AAAACUANwAXAAAAJgBAABcAAAAmAFMAQQEAACYANQBCAQAAJgA0AEMBAAAnABUADgEA",
    "ACcAMwBFAQAAKAAzABcAAAAoADoAqAAAACgAYQCqAAAAKAAzAKsAAAAoAGUArwAAACgAZQCwAAAAKAAlALMAAAAoAGEAtQAAACgA",
    "PAC5AAAAKABKAMkAAAAoAE4A9AAAACgATQD3AAAAKAAFAP0AAAAoAGgAAgEAACgAZAAEAQAAKABjAAUBAAAoAB8ABgEAACgAUAAK",
    "AQAAKABbAA0BAAAoADYAEgEAACgANQATAQAAKAADABQBAAAoAGIAFQEAACgAYwAWAQAAKAAoABoBAAAoAE4AGwEAACgAOwAeAQAA",
    "KAA6AB8BAAAoADoAIAEAACgAOgAhAQAAKAA7ACIBAAAoADoAIwEAACgAOgAkAQAAKABKACUBAAAoAGEAJgEAACgASgAnAQAAKABK",
    "ACgBAAAoAE4AKQEAACgAYQAtAQAAKABKAC4BAAAoACQAMQEAACgAIgAzAQAAKABRADUBAAAoADMANgEAACgATgBFAQAAKABOAEYB",
    "AAAoAGEARwEAACgAPgBJAQAAKAA8AFABAAAoAAQAVwEAACgAIwBcAQAAKABLAF4BAAApAD4A/gAAACoAQQAXAAAAKgBUAKIAAAAq",
    "AEIAwAAAACoAQwDAAAAAKgBEAMAAAAAqAEUAwAAAACoAVQDBAAAAKgBZAMEAAAAqAFoAwQAAACoAXQDBAAAAKgBWAMIAAAAqAF4A",
    "wgAAACsAFgBbAQAALAAYAEwBAAAtABoAyAAAAC0AawDRAAAALQBsANIAAAAtADIA3QAAAC0AHwDfAAAALQAZAOcAAAAtAGYAAwEA",
    "AC4AAgChAAAALwAzABcAAAAvABkAzwAAADEAAACyAAAAMQBmAMMAAAAxAAEA8QAAADEAAQAJAQAAMQAgADIBAAAxAGoASAEAADEA",
    "aABKAQAAMQAhAE0BAAAxACkATgEAADEAHwBYAQAAMQAnAFsBAAAxACoAWwEAADIAMwAXAAAAMgArAKcAAAAyACwApwAAADIALgCn",
    "AAAAMgAvAKcAAAAyADAApwAAADIAMQCnAAAAMgAtAL0AAAAyAAEACQEAADIAHwBPAQAAMwAHALwAAAAzAAYA/AAAADQAHwBPAQAA",
    "NgAcAMsAAAA2AAEA3gAAADYAGQDrAAAANgBcADkBAAA3ABkA0wAAADcAAQDeAAAANwAfAN8AAAA3AGkA4wAAADcAGQDlAAAANwAd",
    "AAABAAA5ACYAHAEAACgAAAARAAAALwAAAEwnAAAcAAAAAAAAALtEAAAAAAAAKQAAABEAAAAvAAAAAAAAAB4AAAAAAAAAzUUAAAAA",
    "AAAqAAAAEQAAAA4AAAAAAAAAewAAAAAAAADWRQAAAAAAAAAAAgoKAAoKYWN0aXZpdHk9AAUKYXBwPQAICmRldmljZT0ACApoaWRk",
    "ZW49AAIgIAAPICBBRCBieSBsYWJlbCAnABsgIEFEIGJ5IHRoZSBoaWRlLWFkIGJ1dHRvbgoACiAgYW5kcm9pZD0ACyAgY29sbGFw",
    "c2U9AAggIGZhaWxzPQASICByZW1lbWJlcmVkLWhpdHM9AAYgIHNkaz0ABiBiYW5kPQAKIGNoaWxkcmVuPQADIGg9AAcgaGlkZGVu",
    "AAQgbHA9ACwgcm93KHMpIGhpZGRlbi4gTG9nIGNvcGllZCB0byB0aGUgY2xpcGJvYXJkLgAFIHRvcD0ABSB2aXM9AAQnIHk9AAY8",
    "aW5pdD4AAT8AB0FMUEhBXzgAAUMAAkNJAAlDb3JlLmphdmEABEVYQyAACkVudHJ5LmphdmEAAUYABUZBTFNFACZHSVZFIFVQIChu",
    "byB0ZXh0IGluIHRoZSBoZWFkZXIpLCBzaG93bgAESElERQAXSElERSBieSByZW1lbWJlcmVkIGl0ZW0ACUhJTlQgZXhjIAAMSElO",
    "VCBtZXRob2QgABZISU5UIG5vbmUsIGNhbmRpZGF0ZXM9AAFJAAJJSQADSUlJAAJJTAADSUxJAAFKAAFMAAJMQwADTENDAAJMSQAD",
    "TElJAARMSUlMAAVMSVNUIAACTEoAAkxMAANMTEkABExMSUkAA0xMTAAETExMSQACTFoAFkxhbmRyb2lkL2FwcC9BY3Rpdml0eTsA",
    "NExhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbiRBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrczsAGUxhbmRyb2lkL2FwcC9BcHBsaWNh",
    "dGlvbjsAGkxhbmRyb2lkL2NvbnRlbnQvQ2xpcERhdGE7ACJMYW5kcm9pZC9jb250ZW50L0NsaXBib2FyZE1hbmFnZXI7ABlMYW5k",
    "cm9pZC9jb250ZW50L0NvbnRleHQ7ACBMYW5kcm9pZC9jb250ZW50L3BtL1BhY2thZ2VJbmZvOwAjTGFuZHJvaWQvY29udGVudC9w",
    "bS9QYWNrYWdlTWFuYWdlcjsAIExhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcCRDb25maWc7ABlMYW5kcm9pZC9ncmFwaGljcy9CaXRt",
    "YXA7ABlMYW5kcm9pZC9ncmFwaGljcy9DYW52YXM7ABlMYW5kcm9pZC9ncmFwaGljcy9NYXRyaXg7ABhMYW5kcm9pZC9ncmFwaGlj",
    "cy9QYWludDsAF0xhbmRyb2lkL2dyYXBoaWNzL1JlY3Q7ABhMYW5kcm9pZC9ncmFwaGljcy9SZWN0RjsAGkxhbmRyb2lkL29zL0J1",
    "aWxkJFZFUlNJT047ABJMYW5kcm9pZC9vcy9CdWlsZDsAE0xhbmRyb2lkL29zL0J1bmRsZTsAFExhbmRyb2lkL29zL0hhbmRsZXI7",
    "ABNMYW5kcm9pZC9vcy9Mb29wZXI7ABhMYW5kcm9pZC9vcy9TeXN0ZW1DbG9jazsAGkxhbmRyb2lkL3ZpZXcvU3VyZmFjZVZpZXc7",
    "ABpMYW5kcm9pZC92aWV3L1RleHR1cmVWaWV3OwAvTGFuZHJvaWQvdmlldy9WaWV3JE9uQXR0YWNoU3RhdGVDaGFuZ2VMaXN0ZW5l",
    "cjsAI0xhbmRyb2lkL3ZpZXcvVmlldyRPbkNsaWNrTGlzdGVuZXI7ABNMYW5kcm9pZC92aWV3L1ZpZXc7ACVMYW5kcm9pZC92aWV3",
    "L1ZpZXdHcm91cCRMYXlvdXRQYXJhbXM7ACtMYW5kcm9pZC92aWV3L1ZpZXdHcm91cCRNYXJnaW5MYXlvdXRQYXJhbXM7ABhMYW5k",
    "cm9pZC92aWV3L1ZpZXdHcm91cDsAGUxhbmRyb2lkL3ZpZXcvVmlld1BhcmVudDsAMUxhbmRyb2lkL3ZpZXcvVmlld1RyZWVPYnNl",
    "cnZlciRPblByZURyYXdMaXN0ZW5lcjsAH0xhbmRyb2lkL3ZpZXcvVmlld1RyZWVPYnNlcnZlcjsAFUxhbmRyb2lkL3ZpZXcvV2lu",
    "ZG93OwApTGFuZHJvaWQvd2lkZ2V0L0ZyYW1lTGF5b3V0JExheW91dFBhcmFtczsAGUxhbmRyb2lkL3dpZGdldC9UZXh0VmlldzsA",
    "FkxhbmRyb2lkL3dpZGdldC9Ub2FzdDsALUxhcHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL2FkaGlkZS9Db3JlOwAuTGFw",
    "cC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svYWRoaWRlL0VudHJ5OwAsTGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJv",
    "b2svYWRoaWRlL1JlYzsAE0xqYXZhL2xhbmcvQm9vbGVhbjsAGExqYXZhL2xhbmcvQ2hhclNlcXVlbmNlOwARTGphdmEvbGFuZy9D",
    "bGFzczsAEExqYXZhL2xhbmcvTWF0aDsAEkxqYXZhL2xhbmcvT2JqZWN0OwAUTGphdmEvbGFuZy9SdW5uYWJsZTsAEkxqYXZhL2xh",
    "bmcvU3RyaW5nOwAZTGphdmEvbGFuZy9TdHJpbmdCdWlsZGVyOwASTGphdmEvbGFuZy9TeXN0ZW07ABVMamF2YS9sYW5nL1Rocm93",
    "YWJsZTsAEExqYXZhL2xhbmcvVm9pZDsAGUxqYXZhL2xhbmcvcmVmbGVjdC9GaWVsZDsAGkxqYXZhL2xhbmcvcmVmbGVjdC9NZXRo",
    "b2Q7ABtMamF2YS90ZXh0L05vcm1hbGl6ZXIkRm9ybTsAFkxqYXZhL3RleHQvTm9ybWFsaXplcjsAEkxqYXZhL3V0aWwvTG9jYWxl",
    "OwAFTU9ERUwAA05GQwACT0sACVJFTEFZT1VUIAAHUkVMRUFTRQAHUkVOREVSIAAEUk9PVAAKUlYgaGlkZXI6IAAQUlYtSElERVIg",
    "MQp0aW1lPQAIUmVjLmphdmEAB1NES19JTlQACVRSRUUgZXhjIAAMVFJFRSBmaWVsZHM9AARUWVBFAAFWAAJWRgACVkkAA1ZJSQAE",
    "VklJSQAFVklJSUkAA1ZJTAACVkwABFZMRkYABVZMRkZMAANWTEkAB1ZMSUlGRkwAClZMSUlJSUZGWkwAA1ZMTAAEVkxMTAAFVkxM",
    "TEwAA1ZMWgACVloAAVoAA1pJSQACWkwAA1pMSgACW0MAAltGAAJbSQASW0xqYXZhL2xhbmcvQ2xhc3M7ABNbTGphdmEvbGFuZy9P",
    "YmplY3Q7ABNbTGphdmEvbGFuZy9TdHJpbmc7ABpbTGphdmEvbGFuZy9yZWZsZWN0L0ZpZWxkOwAbW0xqYXZhL2xhbmcvcmVmbGVj",
    "dC9NZXRob2Q7ABBbaGlkZS1hZCBidXR0b25dAAJcfAANX19SVl9MQUJFTFNfXwADYWJzAANhZGQAHmFkZE9uQXR0YWNoU3RhdGVD",
    "aGFuZ2VMaXN0ZW5lcgAUYWRkT25QcmVEcmF3TGlzdGVuZXIAB2FkZFZpZXcAK2FwcC5yZXZhbmNlZC5leHRlbnNpb24uZmFjZWJv",
    "b2suYWRoaWRlLkNvcmUABmFwcGVuZAAGYXR0YWNoAAViYWRnZQAHYmFkZ2VPbgAJYmFkZ2VUZXh0AARiYW5kAANibXAADGJvdHRv",
    "bU1hcmdpbgAGY2RJc0FkAAhjZElzUG9zdAAHY2hhbmdlZAAGY2hhckF0AAVjbGVhbgAJY2xpcGJvYXJkAAhjb2xsYXBzZQATY29t",
    "LmZhY2Vib29rLmxpdGhvLgAgY29tLmZhY2Vib29rLmxpdGhvLkNvbXBvbmVudFRyZWUAHGNvbS5mYWNlYm9vay5saXRoby5MaXRo",
    "b1ZpZXcABGNvcHkADGNyZWF0ZUJpdG1hcAAHY3VycmVudAARY3VycmVudFRpbWVNaWxsaXMABmRlbGV0ZQAFZGlydHkABGRyYXcA",
    "CmRyYXdCaXRtYXAACGRyYXdUZXh0AAtkcmF3VGV4dFJ1bgAGZXF1YWxzAAVmYWlscwAMZmluZFZpZXdCeUlkAA9maW5kVmlld1dp",
    "dGhUYWcABWZpcnN0AAdmb3JOYW1lAAZmb3JnZXQAAmZ5AANnZXQACWdldEJvdHRvbQAKZ2V0Q2hpbGRBdAANZ2V0Q2hpbGRDb3Vu",
    "dAAIZ2V0Q2xhc3MAFWdldENvbnRlbnREZXNjcmlwdGlvbgARZ2V0RGVjbGFyZWRGaWVsZHMAEmdldERlY2xhcmVkTWV0aG9kcwAR",
    "Z2V0RGVjbGFyaW5nQ2xhc3MADGdldERlY29yVmlldwAJZ2V0SGVpZ2h0AAVnZXRJZAAPZ2V0TGF5b3V0UGFyYW1zAAdnZXRMZWZ0",
    "ABNnZXRMb2NhdGlvbk9uU2NyZWVuAA1nZXRNYWluTG9vcGVyAAlnZXRNYXRyaXgAEWdldE1lYXN1cmVkSGVpZ2h0AAlnZXRNZXRo",
    "b2QADGdldE1vZGlmaWVycwAHZ2V0TmFtZQAOZ2V0UGFja2FnZUluZm8AEWdldFBhY2thZ2VNYW5hZ2VyAA5nZXRQYWNrYWdlTmFt",
    "ZQARZ2V0UGFyYW1ldGVyVHlwZXMACWdldFBhcmVudAANZ2V0UmV0dXJuVHlwZQAIZ2V0UmlnaHQADWdldFN1cGVyY2xhc3MAEGdl",
    "dFN5c3RlbVNlcnZpY2UABmdldFRhZwAGZ2V0VG9wAAdnZXRUeXBlABNnZXRWaWV3VHJlZU9ic2VydmVyAA1nZXRWaXNpYmlsaXR5",
    "AAhnZXRXaWR0aAAJZ2V0V2luZG93AAdoYW5kbGVyAAhoYXNoQ29kZQACaGkABmhpZGRlbgAEaGlkZQAHaGlkZSBhZAAJaGlkZSBw",
    "b3N0AARoaW50AAdoaW50QmFkAAhoaW50RG9uZQAFaGludE0AA2hpdAAQaWRlbnRpdHlIYXNoQ29kZQAHaW5zcGVjdAAHaW5zdGFs",
    "bAAKaW52YWxpZGF0ZQAGaW52b2tlAAdpc0FsaXZlAAdpc0V4YWN0AAppc0luc3RhbmNlAAZpc0xpc3QAB2lzTGl0aG8ABmxhYmVs",
    "cwAIbGFzdExpc3QABmxheW91dAAGbGVuZ3RoAAhsaXN0UGFzcwACbG8AA2xvZwAGbG9nUm93AAhtYWtlVGV4dAAJbWFwUG9pbnRz",
    "AAdtZWFzdXJlAANtZW0ABm1lbUFkZAAGbWVtRGVsAAdtZW1GaW5kAAZtZW1IYXMABm1lbUhpdAAEbWVtTgAMbmV3UGxhaW5UZXh0",
    "AAVub1JlbAAEbm9ybQAGbm9ybWFsAAlub3JtYWxpemUAA29mZgARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0cm95",
    "ZWQAEG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9uQWN0",
    "aXZpdHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAAHb25DbGljawAJb25QcmVEcmF3ABZvblZpZXdBdHRhY2hlZFRvV2luZG93",
    "ABhvblZpZXdEZXRhY2hlZEZyb21XaW5kb3cABHBlbmQAB3BlbmRpbmcAC3Bvc3REZWxheWVkACJyZWdpc3RlckFjdGl2aXR5TGlm",
    "ZWN5Y2xlQ2FsbGJhY2tzAAhyZWxheW91dAAIcmVtZW1iZXIAD3JlbW92ZUNhbGxiYWNrcwAXcmVtb3ZlT25QcmVEcmF3TGlzdGVu",
    "ZXIABnJlbmRlcgAHcmVwbGFjZQAGcmVwb3J0AAlyZXN0b3JpbmcAB3Jvd1Bhc3MAA3J1bgAIcnYtaGlkZXIABHNlZW4ADXNldEFj",
    "Y2Vzc2libGUACHNldEFscGhhABJzZXRCYWNrZ3JvdW5kQ29sb3IAD3NldExheW91dFBhcmFtcwASc2V0T25DbGlja0xpc3RlbmVy",
    "AApzZXRQYWRkaW5nAA5zZXRQcmltYXJ5Q2xpcAAGc2V0VGFnAAdzZXRUZXh0AAxzZXRUZXh0Q29sb3IAC3NldFRleHRTaXplAA1z",
    "ZXRWaXNpYmlsaXR5AARzaG93AANzaWcABnNtb290aAAFc3BsaXQABXN0YXJ0AApzdGFydHNXaXRoAAZzdHJlYWsAC3N1YlNlcXVl",
    "bmNlAAlzdWJzdHJpbmcAC3RvTG93ZXJDYXNlAAh0b1N0cmluZwAFdG9hc3QAB3RyZWVCYWQAB3RyZWVDbHMACHRyZWVEb25lAAZ0",
    "cmVlRjEABnRyZWVGMgAIdHJlZUhpdHMABnRyZWVJZAAEdHJpbQADdHh0AAx1cHRpbWVNaWxsaXMAB3ZhbHVlT2YAB3ZlcnNpb24A",
    "C3ZlcnNpb25OYW1lAAR3YWxrAAR3aW5XAAvhuqluIGLDoGkgdmnhur90AAzhuqluIHF14bqjbmcgY8OhbwADAAAACQAsAAIAAAAC",
    "AAAAMQACAAkAAAA9AAIAAgACAAIAAQABADwAEAAAAAMAAAACAAIAAgAAAAEAAAAdAAAAAQAAAAYAAAABAAAAMQAAAAIAAAAsADgA",
    "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",
    "KAAkAA4AAAAHAAEABQABAAAAAABWAAAAVGALABIBW2ELAFViFgA5Ak0AcQBtAAAACgI4AkcAIAIdADgCQwAfAB0AbhAkAAAACgJu",
    "EBgAAAAKAz0CNwA9AzUAFQQAQLZCtkNuMCcAIANuEBsAAAAKAm4QIQAAAAoDbhAfAAAACgRuEBYAAAAKBW5VJgAgQxIQDwANABIR",
    "XGEWAFRhEwAaAnUAbiCdACEAbhCkAAAADABuIJ0AAQATAAoAbiCZAAEAEgAPABUAAAAmAAEAAQA9AAUAAgADAAAAAAAAAA4AAABw",
    "IHAAQwAKADgACQBuEBgABAAKAXAwUgADAQ4ABAACAAIAAQAAAAAADAAAAG4gFAAyABoAAAARAA0AbhCkAAAADAARAAAAAAADAAEA",
    "AQAGAAYAAgADAAAAAAAAAJAAAAAiADIAcBCYAAAAGgF6AG4gnQAQAHEAogAAAAsCbjCbACADGgEEAG4gnQAQAGIBBABuIJ0AEAAa",
    "AQkAbiCdABAAYgECAG4gnQAQABoBDQBuIJ0AEABgAQMAbiCaABAAGgEDAG4gnQAQAG4QBgAFAAwBbiCdABAAGgEGAG4gnQAQAHEQ",
    "cQAFAAwBbiCdABAAGgECAG4gnQAQAG4QiwAFAAwBbhCGAAEADAFuIJ0AEAAaAQUAbiCdABAAUkEOAG4gmgAQABoBCwBuIJ0AEABS",
    "QQwAbiCaABAAGgEMAG4gnQAQAFJBIABuIJoAEAAaAQoAbiCdABAAcQBGAAAACgFuIJ4AEAAaAQEAbiCdABAAVEETAG4gnAAQAG4Q",
    "oQAAAAwAEQAOAAMAAwAAAAAAAADHAQAAFAABoPB/biAgAA0ADAEgEj8AOQIJABMCDAAjIT8AbjAtAA0BHwE/AFWyGQA4AgYAcDBr",
    "ANsBDgBxABIAAAALAoQiEiREAwEEOQMEAEsCAQQSUyMzPwBxIGwAPQASBEQFAwTaBQUfEhREBgMEsGUSJEQGAwQSFEQHAQQSGDOH",
    "FQA5BgwAbhAYAA0ACggTCQgARAkBCTKYHgASB0sHAQQSJEsCAQQoFxIoM4cVABIIRAgBCDJYDAASCEsFAQgSR0sHAQQSJEsCAQQo",
    "BXAwSQDbAQ4AEghbuAcAOQZAABJIMoc9AHAgVgDbAAoIOAg3ABJYRAkBCDkJCAASGUsJAQhuIBMAvQASSBIZSwgBCRIISwUBCBIo",
    "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",
    "AQISEksJAQJuIA0AEABEAgEChyJSYyQANjI2AFJkJwDYBAQBWWQnAFJjJgA0MiwAcRBFAAcADANUZCgAbhCgAAQACgUTACwBNQUK",
    "AG4gnQA0ABMAfABuIJkABABxEEwAAwAKBDgEEABSZCUA2AQEAVlkJQBUZSIAOQUGAFtjIgBZYiMADgAAAAUABQAAAAAAAAAAAAEA",
    "AAAOAAAABAAEAAAAAAAAAAAAAQAAAA4AAAAFAAUAAAAAAAAAAAABAAAADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAACQAHAAQAAQAA",
    "AAAACgAAAHIwgQBDBQwAcEB1AAJ2DgANACj+AAAAAAcAAQABAAgABgAFAAQAAQAAAAAABgAAAHBAdQAhQw4ADQAo/gAAAAADAAEA",
    "AQAEAAkABwAEAAEAAAAAAAoAAABuMJMAQwUMAHBAdQACdg4ADQAo/gAAAAAHAAEAAQAIAAkABwAEAAEAAAAAAAoAAABxMJcAQwUM",
    "AHBAdQACdg4ADQAo/gAAAAAHAAEAAQAIAAwACgAEAAEAAAAAAAoAAAByMIEAQwUMAHBAdQACmA4ADQAo/gAAAAAHAAEAAQAIAAwA",
    "CgAEAAEAAAAAAAoAAABxMJcAQwUMAHBAdQACmA4ADQAo/gAAAAAHAAEAAQAIABwoDAYCAQIBAgECAQIBAgECARIBAgECAQIBAgEC",
    "ARIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECP4KABKxRAQL0UQEJqFMBArxTAQmMVAEJ7FQBCcxVAQmcVgEKsFYBApBXAQK0",
    "VwEC4FgBAtBbAQmIXgEKrF8BCsxgAQmEYQECnGEBArxjAQLkZAECsGUBAvBlAQKoZgEC9GYBCaxnAQLUaAwCmGkBAsRpAQKMawEK",
    "uGsBAuxrAQKcbgICvHUBCrR2AQn4dwEJjHgBCrB4AQLkeAEKvHsBAoB8WQHwfAEBhH0BAZh9AQH8fQEBgH8BAZR/AQGofwEBvH8B",
    "AayAAQEB6IIBAQH8ggEHAdyDAQAAAQBzCZyEAQAHAgoiAQEBAQEBAQEBAQEBEXSBgAT8hAEBAqSFAXYB1IYBAQHohgEBAfyGAQEB",
    "kIcBAQGkhwEBAdSHAQEB/IcBAQGsiAEBAdyIAQEBjIkBAAANAAAAAAAAAAEAAAAAAAAAAQAAAGIBAABwAAAAAgAAAEUAAAD4BQAA",
    "AwAAAG0AAAAMBwAABAAAAC4AAAAoDAAABQAAALAAAACYDQAABgAAAAMAAAAYEwAAAiAAAGIBAAB4EwAAARAAAD0AAABUJgAAAxAA",
    "AAEAAACoKAAAASAAAEEAAACsKAAAACAAAAMAAAC7RAAAABAAAAEAAAAoRgAA",
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
        "Remembers the ads it has hidden, so scrolling back up does not show them again. " +
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
