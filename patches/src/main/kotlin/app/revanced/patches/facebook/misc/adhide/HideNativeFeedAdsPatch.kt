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
 *   2. for every row looks at its views: a content description "Hide ad" / "Ẩn quảng cáo" means an ad;
 *   3. if the header of the row is fully on the screen, the row is drawn ONCE into a small throw-away bitmap through a Canvas
 *      subclass (Rec) that records the strings drawn between 9% and 25% of the row width from the top. A string that IS one
 *      of the Sponsored labels (whole string, lower case, Unicode composed, no-break spaces dropped) means an ad;
 *   4. an ad row is made INVISIBLE and, with the option "Collapse space", its height is taken out of the layout (bottom margin
 *      = - height) so there is no gap. A row that shows header text but no label is an ordinary post and is left alone
 *      (believed at the second look, in a later frame: while scrolling fast Litho may still show the previous frame's content);
 *   5. a row that has not drawn its header yet is kept transparent for at most 300 ms (it is still laid out and drawn, so the
 *      header does get drawn), then shown;
 *   6. when RecyclerView recycles a row view (it leaves the window) the row is given back untouched (visible, own margin).
 *
 * Costs: the tree walk is a few dozen views per frame; a row is drawn into the bitmap once or twice per judgement (not every
 * frame; a row cut by the edge of the list at most every 80 ms);
 * one ALPHA_8 bitmap of  width x 40% width  is kept. The frame is cancelled and redone only when a layout was changed
 * (at most 3 times in a row).
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. If drawing a row fails 6 times the patch switches itself off and gives every row back.
 * Rows shorter than 40% of their width (menu entries, small cards) are never taken for ads by their label.
 *
 * What you see: a small dark button "RV hider: N ad(s) hidden" at the bottom left (option "Show counter badge"). Tap it: the
 * log (what was hidden, where, which list class) is copied to the clipboard so it can be pasted into a message.
 *
 * Limits (honest):
 *   - Stories / story ads and "Suggested for you" cards are NOT handled here (no sample yet).
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
private const val LABELS = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val ADHIDE_DEX = listOf(
    "ZGV4CjAzOAAhYpnfoNr/1xip4UjBSaHSmt4MAaI5cwhQNwAAcAAAAHhWNBIAAAAAAAAAALA2AAAWAQAAcAAAADwAAADIBAAAXwAA",
    "ALgFAAAcAAAALAoAAI8AAAAMCwAAAwAAAIQPAABsJwAA5A8AAOQPAADmDwAA6g8AAPYPAAD9DwAABxAAABEQAAAVEAAAJhAAAEMQ",
    "AABPEAAAXBAAAGYQAABuEAAAnxAAAK4QAAC6EAAAvxAAAMUQAADMEAAA0xAAANkQAADhEAAA5BAAAO0QAADwEAAA9BAAAP8QAAAF",
    "EQAAEREAABQRAAA8EQAAQhEAAEURAABKEQAATREAAFARAABUEQAAWREAAF0RAABiEQAAaBEAAG8RAABzEQAAdxEAAHwRAACCEQAA",
    "hxEAAI0RAACREQAAqREAAN8RAAD6EQAAFhIAADoSAABVEgAAdxIAAJwSAAC+EgAA2RIAAPQSAAAPEwAAKRMAAEITAABcEwAAeBMA",
    "AIwTAAChEwAAtxMAAMwTAADmEwAAFxQAADwUAABRFAAAeBQAAKUUAAC/FAAA8hQAABMVAAAqFQAAVRUAAHAVAACIFQAAtxUAAOcV",
    "AAAVFgAALxYAAEIWAABWFgAAbBYAAIAWAACbFgAArxYAAMYWAADiFgAA/xYAABcXAAArFwAAMhcAADcXAAA7FwAARBcAAE0XAABT",
    "FwAAXxcAAHEXAAB7FwAAhBcAAIcXAACLFwAAjxcAAJUXAACcFwAAoRcAAKUXAACrFwAAshcAALcXAADAFwAAzBcAANEXAADXFwAA",
    "3hcAAOEXAADlFwAA6hcAAO4XAADyFwAA9hcAAAoYAAAfGAAANBgAADgYAABHGAAATBgAAGwYAACCGAAAixgAALgYAADAGAAAyBgA",
    "AM8YAADYGAAA4xgAAOgYAAD2GAAA/hgAAAcZAAAPGQAAFhkAACEZAAArGQAAQBkAAF4ZAABkGQAAchkAAHsZAACOGQAAlhkAAJwZ",
    "AACoGQAAshkAAL8ZAADHGQAAzhkAANwZAADtGQAA9BkAAP0ZAAABGgAADRoAABwaAAAmGgAAPRoAAEsaAABWGgAAXRoAAG4aAACD",
    "GgAAkhoAAJ0aAACwGgAAuxoAAMQaAADUGgAA5xoAAPcaAAAGGwAAGBsAACAbAAAoGwAAPRsAAEwbAABWGwAAYRsAAGobAAB0GwAA",
    "eBsAAIAbAACGGwAAjxsAAJQbAACdGwAAphsAALIbAAC6GwAAwxsAAMwbAADUGwAA3RsAAOUbAADvGwAA9xsAAAEcAAAFHAAAChwA",
    "ABIcAAAcHAAAJxwAADUcAAA7HAAAQxwAAE4cAABTHAAAZhwAAHscAACNHAAAoBwAAL0cAADQHAAA4xwAAOwcAAD3HAAADx0AACkd",
    "AAAvHQAAOB0AAEUdAABpHQAAeh0AAJMdAACbHQAApB0AAKwdAAC3HQAAwB0AAMUdAADPHQAA1R0AAN8dAADzHQAABB4AABgeAAAk",
    "HgAANB4AADweAABFHgAAUx4AAGAeAABvHgAAdR4AAHoeAACBHgAAiB4AAJQeAACcHgAAqR4AALQeAADBHgAAyx4AANIeAADYHgAA",
    "5h4AAO8eAAD4HgAABR8AAAsfAAARHwAAGAAAAB0AAAAgAAAAIgAAADEAAAAyAAAAMwAAADQAAAA1AAAANgAAADcAAAA4AAAAOQAA",
    "ADoAAAA7AAAAPAAAAD0AAAA+AAAAPwAAAEAAAABBAAAAQgAAAEMAAABEAAAARQAAAEYAAABHAAAASAAAAEkAAABKAAAASwAAAEwA",
    "AABNAAAATgAAAE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUAAABWAAAAVwAAAFgAAABZAAAAWgAAAFsAAABcAAAAXQAAAF4AAABf",
    "AAAAYAAAAGsAAAB6AAAAfQAAAH4AAAB/AAAAgAAAAIEAAACCAAAAGQAAAAAAAADsIAAAIAAAAAIAAAAAAAAAIQAAAAIAAAAkIAAA",
    "IgAAAAMAAAAAAAAALgAAAAcAAADYHwAALAAAAAoAAAAwHwAAIwAAAAsAAAAAAAAAKAAAAA0AAAAwIQAAIwAAAA8AAAAAAAAAIwAA",
    "ABcAAAAAAAAAIwAAABsAAAAAAAAAJgAAABsAAADsIAAAKwAAABsAAAA4IAAAIwAAABwAAAAAAAAAIwAAACAAAAAAAAAAIwAAACEA",
    "AAAAAAAALwAAACQAAAAkHwAAIwAAACgAAAAAAAAAJwAAACgAAAC4IAAAIwAAACkAAAAAAAAAKwAAACkAAABsHwAAJgAAACoAAADs",
    "IAAALgAAACoAAAAoIQAAKwAAACoAAABsHwAAIwAAACwAAAAAAAAAJQAAACwAAAC4HwAAJwAAACwAAAC4IAAAKwAAACwAAACwHwAA",
    "KwAAACwAAADoHwAALgAAACwAAADAHwAAKwAAACwAAABoIQAALgAAACwAAAB0HwAAKwAAACwAAAA4IAAAKwAAACwAAABsHwAAKwAA",
    "ACwAAADIHwAALQAAACwAAAAcIQAAJAAAAC0AAADAIAAAJgAAAC0AAADsIAAAJwAAAC0AAAC4IAAAKgAAAC0AAAD0IAAAKwAAAC0A",
    "AABoIQAAKwAAAC0AAABsHwAAMAAAAC0AAABgIQAALgAAADAAAADkIAAAawAAADQAAAAAAAAAbAAAADQAAADUIAAAbQAAADQAAADs",
    "IAAAbgAAADQAAABQHwAAbwAAADQAAABEIQAAcAAAADQAAABIIAAAcQAAADQAAACwHwAAdwAAADQAAAAUIQAAdwAAADQAAACEIAAA",
    "cQAAADQAAADgHwAAcQAAADQAAABkHwAAcQAAADQAAAA8IQAAcQAAADQAAADoHwAAcQAAADQAAABYIQAAcwAAADQAAACMHwAAeAAA",
    "ADQAAAD4HwAAeQAAADQAAADIIAAAeQAAADQAAACMIAAAcQAAADQAAACgIAAAcQAAADQAAACoHwAAcQAAADQAAAAEIQAAcQAAADQA",
    "AABQIAAAcQAAADQAAABcHwAAdAAAADQAAAAkIAAAdwAAADQAAABwIAAAdwAAADQAAABAIAAAcQAAADQAAADwHwAAcQAAADQAAADc",
    "IAAAdwAAADQAAACgHwAAcQAAADQAAADQHwAAcQAAADQAAABoIQAAcgAAADQAAAB4IAAAdQAAADQAAACoIAAAdgAAADQAAABYIAAA",
    "cQAAADQAAAA4IAAAcQAAADQAAACYIAAAcwAAADQAAAAsIAAAdQAAADQAAAAEIAAAdwAAADQAAABQIQAAdQAAADQAAAB8HwAAdgAA",
    "ADQAAAA4HwAAcQAAADQAAAD8IAAAcQAAADQAAAAMIQAAegAAADUAAAAAAAAAewAAADUAAABcHwAAewAAADUAAADcIAAAewAAADUA",
    "AABoIQAAewAAADUAAAA4IAAAfAAAADUAAACYHwAAewAAADUAAABsHwAAKwAAADsAAABsHwAACgAsABIBAAAMAAwAFwAAABMALABk",
    "AAAAEwACAGoAAAAUACwAYQAAAB0AAgCQAAAAJQAjAIwAAAAlAA0AjwAAACUANQCSAAAAJQAEAJsAAAAlAAIAowAAACUAFgDCAAAA",
    "JQACAMUAAAAlABsA0gAAACUALQDWAAAAJQA1AN4AAAAlAAIA6wAAACUANQDzAAAAJQACAAgBAAAlAAIAFAEAACcALACmAAAAJwAC",
    "AKgAAAAnAAIAxAAAACcAAgDIAAAAJwACANUAAAAnAAIA9wAAADEAMQBiAAAAMwAzAGYAAAAEAAsApAAAAAQADwDBAAAABgA1AO0A",
    "AAAHAAQA2gAAAAgANwD9AAAACQAGALgAAAAJABgAuQAAAAkAFwC7AAAACwAFALcAAAANAAcAmgAAAA0AAQDAAAAADgA5ABUAAAAO",
    "AAgAswAAAA8AVQDZAAAAFgA/ABUAAAAWAFwA7AAAABYATwDuAAAAFwAJALIAAAAYAAMADwEAABsAQACGAAAAGwA+AJ4AAAAbAAwA",
    "pQAAABsAEQCsAAAAGwABAK4AAAAbAAEArwAAABsADQCwAAAAGwBWALEAAAAbAAEAtAAAABsAFQC8AAAAGwABAL0AAAAbAA4AvgAA",
    "ABsAAQC/AAAAGwABAMAAAAAbACwAywAAABsALQD4AAAAGwAuAPkAAAAbAEYA+gAAABsAQQD7AAAAGwAwAPwAAAAbADEA/gAAABsA",
    "TgD+AAAAGwAuAAIBAAAeAEQAiAAAAB4ACwCpAAAAHgABAKoAAAAeAAEArgAAACAASQCHAAAAIABXAM0AAAAgAEkA7wAAACEACgCt",
    "AAAAIgAvABUAAAAjADgAFQAAACMASgD/AAAAIwAuAAABAAAjAC0AAQEAACQAEADYAAAAJAAsAAMBAAAlACwAFQAAACUAMgCLAAAA",
    "JQBXAI0AAAAlACwAjgAAACUAWgCRAAAAJQAeAJQAAAAlAFcAlgAAACUANACZAAAAJQBFAMYAAAAlAAIAyQAAACUAXQDOAAAAJQBZ",
    "AM8AAAAlAFgA0AAAACUAGADRAAAAJQBHANQAAAAlAFIA1wAAACUAIQDbAAAAJQBFANwAAAAlADMA3wAAACUAMgDgAAAAJQAyAOEA",
    "AAAlADIA4gAAACUAMwDjAAAAJQAyAOQAAAAlADIA5QAAACUAQgDmAAAAJQBXAOcAAAAlAEIA6AAAACUAQgDpAAAAJQBFAOoAAAAl",
    "AB0A8AAAACUAGwDyAAAAJQBIAPQAAAAlACwA9QAAACUARQADAQAAJQBFAAQBAAAlADYABgEAACUANAANAQAAJQAcABEBAAAlAEMA",
    "EwEAACYANgDKAAAAJwA5ABUAAAAnAEsAhQAAACcAOgCfAAAAJwA7AJ8AAAAnADwAnwAAACcAPQCfAAAAJwBMAKAAAAAnAFAAoAAA",
    "ACcAUQCgAAAAJwBTAKAAAAAnAE0AoQAAACcAVAChAAAAKAASAAkBAAApABQApwAAACkAKwC1AAAAKQAYALYAAAApABMAugAAACoA",
    "LAAVAAAAKgATAKsAAAAsAAAAkwAAACwAWwCiAAAALAABAMMAAAAsAAEA0wAAACwAGQDxAAAALABeAAUBAAAsAF0ABwEAACwAGgAK",
    "AQAALAAiAAsBAAAsABgADgEAACwAIAAQAQAALAAjABABAAAtACwAFQAAAC0AJACKAAAALQAlAIoAAAAtACcAigAAAC0AKACKAAAA",
    "LQApAIoAAAAtACoAigAAAC0AJgCdAAAALQABANMAAAAtABgADAEAAC4AAwCcAAAALwAYAAwBAAAwABYAzAAAADIAHwDdAAAAJQAA",
    "ABEAAAAqAAAAFCAAABoAAAAAAAAAozUAAAAAAAAmAAAAEQAAACoAAAAAAAAAHAAAAAAAAABlNgAAAAAAACcAAAARAAAADgAAAAAA",
    "AABpAAAAAAAAAG02AAAAAAAAAAACCgoACgphY3Rpdml0eT0ABQphcHA9AAgKZGV2aWNlPQAICmhpZGRlbj0AAiAgAA8gIEFEIGJ5",
    "IGxhYmVsICcAGyAgQUQgYnkgdGhlIGhpZGUtYWQgYnV0dG9uCgAKICBhbmRyb2lkPQALICBjb2xsYXBzZT0ACCAgZmFpbHM9AAYg",
    "IHNkaz0ALyBhZCByb3cocykgaGlkZGVuLiBMb2cgY29waWVkIHRvIHRoZSBjbGlwYm9hcmQuAA0gYWQocykgaGlkZGVuAAogY2hp",
    "bGRyZW49AAMgaD0ABCBscD0ABSB0b3A9AAUgdmlzPQAEJyB5PQAGPGluaXQ+AAE/AAdBTFBIQV84AAFDAAJDSQAJQ29yZS5qYXZh",
    "AARFWEMgAApFbnRyeS5qYXZhAAFGACZHSVZFIFVQIChubyB0ZXh0IGluIHRoZSBoZWFkZXIpLCBzaG93bgAESElERQABSQADSUxJ",
    "AAFKAAFMAAJMQwADTENDAAJMSQADTElJAARMSUlMAAVMSVNUIAACTEoAAkxMAANMTEkABExMSUkAA0xMTAAETExMSQACTFoAFkxh",
    "bmRyb2lkL2FwcC9BY3Rpdml0eTsANExhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbiRBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrczsA",
    "GUxhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbjsAGkxhbmRyb2lkL2NvbnRlbnQvQ2xpcERhdGE7ACJMYW5kcm9pZC9jb250ZW50L0Ns",
    "aXBib2FyZE1hbmFnZXI7ABlMYW5kcm9pZC9jb250ZW50L0NvbnRleHQ7ACBMYW5kcm9pZC9jb250ZW50L3BtL1BhY2thZ2VJbmZv",
    "OwAjTGFuZHJvaWQvY29udGVudC9wbS9QYWNrYWdlTWFuYWdlcjsAIExhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcCRDb25maWc7ABlM",
    "YW5kcm9pZC9ncmFwaGljcy9CaXRtYXA7ABlMYW5kcm9pZC9ncmFwaGljcy9DYW52YXM7ABlMYW5kcm9pZC9ncmFwaGljcy9NYXRy",
    "aXg7ABhMYW5kcm9pZC9ncmFwaGljcy9QYWludDsAF0xhbmRyb2lkL2dyYXBoaWNzL1JlY3Q7ABhMYW5kcm9pZC9ncmFwaGljcy9S",
    "ZWN0RjsAGkxhbmRyb2lkL29zL0J1aWxkJFZFUlNJT047ABJMYW5kcm9pZC9vcy9CdWlsZDsAE0xhbmRyb2lkL29zL0J1bmRsZTsA",
    "FExhbmRyb2lkL29zL0hhbmRsZXI7ABNMYW5kcm9pZC9vcy9Mb29wZXI7ABhMYW5kcm9pZC9vcy9TeXN0ZW1DbG9jazsAL0xhbmRy",
    "b2lkL3ZpZXcvVmlldyRPbkF0dGFjaFN0YXRlQ2hhbmdlTGlzdGVuZXI7ACNMYW5kcm9pZC92aWV3L1ZpZXckT25DbGlja0xpc3Rl",
    "bmVyOwATTGFuZHJvaWQvdmlldy9WaWV3OwAlTGFuZHJvaWQvdmlldy9WaWV3R3JvdXAkTGF5b3V0UGFyYW1zOwArTGFuZHJvaWQv",
    "dmlldy9WaWV3R3JvdXAkTWFyZ2luTGF5b3V0UGFyYW1zOwAYTGFuZHJvaWQvdmlldy9WaWV3R3JvdXA7ADFMYW5kcm9pZC92aWV3",
    "L1ZpZXdUcmVlT2JzZXJ2ZXIkT25QcmVEcmF3TGlzdGVuZXI7AB9MYW5kcm9pZC92aWV3L1ZpZXdUcmVlT2JzZXJ2ZXI7ABVMYW5k",
    "cm9pZC92aWV3L1dpbmRvdzsAKUxhbmRyb2lkL3dpZGdldC9GcmFtZUxheW91dCRMYXlvdXRQYXJhbXM7ABlMYW5kcm9pZC93aWRn",
    "ZXQvVGV4dFZpZXc7ABZMYW5kcm9pZC93aWRnZXQvVG9hc3Q7AC1MYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhp",
    "ZGUvQ29yZTsALkxhcHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL2FkaGlkZS9FbnRyeTsALExhcHAvcmV2YW5jZWQvZXh0",
    "ZW5zaW9uL2ZhY2Vib29rL2FkaGlkZS9SZWM7ABhMamF2YS9sYW5nL0NoYXJTZXF1ZW5jZTsAEUxqYXZhL2xhbmcvQ2xhc3M7ABJM",
    "amF2YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcvUnVubmFibGU7ABJMamF2YS9sYW5nL1N0cmluZzsAGUxqYXZhL2xhbmcvU3Ry",
    "aW5nQnVpbGRlcjsAEkxqYXZhL2xhbmcvU3lzdGVtOwAVTGphdmEvbGFuZy9UaHJvd2FibGU7ABpMamF2YS9sYW5nL3JlZmxlY3Qv",
    "TWV0aG9kOwAbTGphdmEvdGV4dC9Ob3JtYWxpemVyJEZvcm07ABZMamF2YS90ZXh0L05vcm1hbGl6ZXI7ABJMamF2YS91dGlsL0xv",
    "Y2FsZTsABU1PREVMAANORkMAAk9LAAdSRUxFQVNFAAdSRU5ERVIgAARST09UAApSViBoaWRlcjogABBSVi1ISURFUiAxCnRpbWU9",
    "AAhSZWMuamF2YQAHU0RLX0lOVAABVgACVkYAAlZJAARWSUlJAAVWSUlJSQADVklMAAJWTAAEVkxGRgAFVkxGRkwAA1ZMSQAHVkxJ",
    "SUZGTAAKVkxJSUlJRkZaTAADVkxMAARWTExMAAVWTExMTAABWgACWkwAA1pMSgACW0MAAltGAAJbSQASW0xqYXZhL2xhbmcvQ2xh",
    "c3M7ABNbTGphdmEvbGFuZy9PYmplY3Q7ABNbTGphdmEvbGFuZy9TdHJpbmc7AAJcfAANX19SVl9MQUJFTFNfXwADYWRkAB5hZGRP",
    "bkF0dGFjaFN0YXRlQ2hhbmdlTGlzdGVuZXIAFGFkZE9uUHJlRHJhd0xpc3RlbmVyAAdhZGRWaWV3ACthcHAucmV2YW5jZWQuZXh0",
    "ZW5zaW9uLmZhY2Vib29rLmFkaGlkZS5Db3JlAAZhcHBlbmQABmF0dGFjaAAFYmFkZ2UAB2JhZGdlT24ACWJhZGdlVGV4dAADYm1w",
    "AAxib3R0b21NYXJnaW4ABmNkSXNBZAAHY2hhbmdlZAAGY2hhckF0AAVjbGVhbgAJY2xpcGJvYXJkAAhjb2xsYXBzZQATY29tLmZh",
    "Y2Vib29rLmxpdGhvLgAcY29tLmZhY2Vib29rLmxpdGhvLkxpdGhvVmlldwAEY29weQAMY3JlYXRlQml0bWFwAAdjdXJyZW50ABFj",
    "dXJyZW50VGltZU1pbGxpcwAGZGVsZXRlAARkcmF3AApkcmF3Qml0bWFwAAhkcmF3VGV4dAALZHJhd1RleHRSdW4ABmVxdWFscwAF",
    "ZmFpbHMADGZpbmRWaWV3QnlJZAAPZmluZFZpZXdXaXRoVGFnAAVmaXJzdAAHZm9yTmFtZQACZnkACmdldENoaWxkQXQADWdldENo",
    "aWxkQ291bnQACGdldENsYXNzABVnZXRDb250ZW50RGVzY3JpcHRpb24ADGdldERlY29yVmlldwAJZ2V0SGVpZ2h0AAVnZXRJZAAP",
    "Z2V0TGF5b3V0UGFyYW1zABNnZXRMb2NhdGlvbk9uU2NyZWVuAA1nZXRNYWluTG9vcGVyAAlnZXRNYXRyaXgAEWdldE1lYXN1cmVk",
    "SGVpZ2h0AAlnZXRNZXRob2QAB2dldE5hbWUADmdldFBhY2thZ2VJbmZvABFnZXRQYWNrYWdlTWFuYWdlcgAOZ2V0UGFja2FnZU5h",
    "bWUADWdldFN1cGVyY2xhc3MAEGdldFN5c3RlbVNlcnZpY2UABmdldFRhZwAGZ2V0VG9wABNnZXRWaWV3VHJlZU9ic2VydmVyAA1n",
    "ZXRWaXNpYmlsaXR5AAhnZXRXaWR0aAAJZ2V0V2luZG93AAdoYW5kbGVyAAhoYXNoQ29kZQACaGkABmhpZGRlbgAEaGlkZQAHaGlk",
    "ZSBhZAADaGl0AAdpbnNwZWN0AAdpbnN0YWxsAAppbnZhbGlkYXRlAAZpbnZva2UAB2lzQWxpdmUAB2lzRXhhY3QABmlzTGlzdAAH",
    "aXNMaXRobwAGbGFiZWxzAAhsYXN0TGlzdAAGbGVuZ3RoAAhsaXN0UGFzcwACbG8AA2xvZwAGbG9nUm93AAhtYWtlVGV4dAAJbWFw",
    "UG9pbnRzAAxuZXdQbGFpblRleHQABG5vcm0ABm5vcm1hbAAJbm9ybWFsaXplAANvZmYAEW9uQWN0aXZpdHlDcmVhdGVkABNvbkFj",
    "dGl2aXR5RGVzdHJveWVkABBvbkFjdGl2aXR5UGF1c2VkABFvbkFjdGl2aXR5UmVzdW1lZAAbb25BY3Rpdml0eVNhdmVJbnN0YW5j",
    "ZVN0YXRlABFvbkFjdGl2aXR5U3RhcnRlZAARb25BY3Rpdml0eVN0b3BwZWQAB29uQ2xpY2sACW9uUHJlRHJhdwAWb25WaWV3QXR0",
    "YWNoZWRUb1dpbmRvdwAYb25WaWV3RGV0YWNoZWRGcm9tV2luZG93AARwZW5kAAdwZW5kaW5nAAtwb3N0RGVsYXllZAAicmVnaXN0",
    "ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2FsbGJhY2tzABdyZW1vdmVPblByZURyYXdMaXN0ZW5lcgAGcmVu",
    "ZGVyAAdyZXBsYWNlAAZyZXBvcnQACXJlc3RvcmluZwAHcm93UGFzcwADcnVuAAhydi1oaWRlcgAEc2VlbgAIc2V0QWxwaGEAEnNl",
    "dEJhY2tncm91bmRDb2xvcgAPc2V0TGF5b3V0UGFyYW1zABJzZXRPbkNsaWNrTGlzdGVuZXIACnNldFBhZGRpbmcADnNldFByaW1h",
    "cnlDbGlwAAZzZXRUYWcAB3NldFRleHQADHNldFRleHRDb2xvcgALc2V0VGV4dFNpemUADXNldFZpc2liaWxpdHkABHNob3cAA3Np",
    "ZwAFc3BsaXQABXN0YXJ0AApzdGFydHNXaXRoAAZzdHJlYWsAC3N1YlNlcXVlbmNlAAlzdWJzdHJpbmcAC3RvTG93ZXJDYXNlAAh0",
    "b1N0cmluZwAFdG9hc3QABHRyaW0ADHVwdGltZU1pbGxpcwAHdmFsdWVPZgAHdmVyc2lvbgALdmVyc2lvbk5hbWUABHdhbGsABHdp",
    "blcADOG6qW4gcXXhuqNuZyBjw6FvAAMAAAAJACgAAgAAAAIAAAAsAAIACQAAADYAAgACAAIAAgABAAEANQAQAAAAAwAAAAIAAgAC",
    "AAAAAQAAABsAAAABAAAABgAAAAEAAAAsAAAAAgAAACgAMQAGAAAANgACAAIAAQABABAABAAAAA0AAQABABAAAgAAACsAAwACAAAA",
    "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",
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
        "\"Hide ad\" button, and makes that row invisible and (option) takes its height out of the list. Works at run " +
        "time on what is on the screen, so it does not depend on obfuscated names and works on the Dextricks " +
        "(Android 8) builds. Adds no file to the apk, no resource, no manifest change. Not for stories yet. " +
        "A small badge at the bottom left shows how many ads were hidden; tap it to copy a log.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val collapseSpace by booleanOption(
        name = "Collapse space",
        description = "Takes the height of a hidden ad out of the list so that the next post moves up (negative bottom " +
            "margin, RecyclerView only). Turn off to keep an invisible blank gap instead.",
        default = true,
    )

    val showBadge by booleanOption(
        name = "Show counter badge",
        description = "Shows a small dark 'RV hider: N ad(s) hidden' button at the bottom left of the screen; a tap copies the " +
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
        labelsMethod.replaceInstruction(0, "const-string v0, \"${smaliString(LABELS)}\"")

        if (collapseSpace == false) {
            val m = coreMethods.firstOrNull { it.name == "collapse" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.collapse() not found")
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
                "collapse=${collapseSpace != false}, badge=${showBadge != false}",
        )
    }
}
