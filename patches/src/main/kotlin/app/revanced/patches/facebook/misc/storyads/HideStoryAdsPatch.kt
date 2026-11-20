package app.revanced.patches.facebook.misc.storyads

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
 * Hide story ads for android 8 app
 *
 * ONE self-contained file. It adds NO file to the apk (no asset, no resource, no manifest change, no library). It adds three
 * small classes (Entry, Core, Rec) to the dex the app already has, and one call at the top of the Application.onCreate() that
 * already exists. Nothing of Facebook's own code is read, renamed or changed, so it works on the obfuscated and on the Dextricks
 * builds alike (Android 8: 490 / 521 / 585, Android 11: the same builds).
 *
 * Why it works at run time: on Android 8 almost all of the app's code is invisible to the patcher, but what a story DRAWS is
 * always there. The probe samples of three story ads (StoryViewerActivity, build 521.0.0.42.97) showed that the header of a
 * sponsored story (a Litho ComponentHost of window width and about 8% of it in height, at the top of the story, with the page
 * picture, the page name and the time line) draws the label "Được tài trợ" ("Sponsored" ...) as a separate string on the second
 * line, while an ordinary story draws the time there. This patch reads exactly that.
 *
 * What it does (only while an activity whose class name contains "StoryViewer" is on the screen; nothing is done on any other
 * screen):
 *   1. before a frame is drawn (ViewTreeObserver.OnPreDrawListener, at most every 30 ms) it looks for the header strip of the
 *      story that is on the screen: a com.facebook.litho.ComponentHost that is at least 90% of the window wide, 7% .. 40% of its
 *      width tall, at the left edge of the window and in the upper fifth of it (the stories next to it in the pager are outside
 *      the window and are not taken);
 *   2. it draws that one strip ONCE into a small throw-away bitmap through a Canvas subclass (Rec) that records the strings
 *      drawn between 9% and 25% of the strip width from its top (the page name is above that band) and checks them against
 *      the Sponsored labels (whole string, lower case, Unicode composed, no-break spaces dropped);
 *   3. when a label is drawn the story is an ad and a quick tap (DOWN at once, UP 16 ms later) is sent to the story viewer on
 *      the right side of the screen (85% of the width, 45% of the height): the viewer goes to the next story. After a skip the
 *      patch waits 0.3 s and then looks again, but the SAME header strip is judged again only 1.2 s after the tap (the viewer
 *      may need that long to react); a DIFFERENT header strip (the next story, if it is an ad too) is judged after 0.3 s. After 3
 *      skips in a row without a normal story in between it rests for 5 s, so a story that does not go away can never make a
 *      loop of taps.
 *   4. the time of drawing the strip is measured; if it takes more than 5 ms six times in a row (a slow phone) the looks are
 *      made every 120 ms instead of every 30 ms, so that the story video does not stutter because of the patch.
 *
 * Nothing is hidden by changing views and nothing of the viewer is touched: the viewer is only asked, like a finger would, to
 * show the next story. The tap is the one thing that could be wrong on a version whose viewer reacts differently (for
 * example when the ad has a button at that place): then the skip simply does not happen (and stops after 3 tries).
 *
 * Costs: the tree walk is a few dozen views at most every 30 ms; the header strip (about 1080 x 200) is drawn once per look into
 * one ALPHA_8 bitmap of window width x strip height, kept between looks.
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. If drawing the strip fails 6 times the patch switches itself off for that run.
 *
 * Limits (honest):
 *   - The story is seen for the time it takes to find the label (the label has to be drawn first, then the next look comes at most
 *     30 ms later) and for the time of the tap (16 ms) and of the viewer's own change to the next story. The patch cannot be faster
 *     than the moment the label is drawn: if the app counts an ad as seen at the very moment it is shown, the patch cannot undo
 *     that, it can only make the ad stay on the screen for a shorter time.
 *   - If the finger is on the screen at that moment, the extra tap is sent on top of it.
 *   - Only story ads whose header says Sponsored in one of the languages of the list are recognised. Reels ads and ads between
 *     the feed stories tray are another patch.
 *   - Not run on a real device by the author when this file was written: it was checked with a verifier of the register / type
 *     rules and with a simulation that runs this very smali; see the README.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * The smali of the three classes is in the zip next to this file (source folder); the Base64 below is exactly its assembly.
 */

private const val ENTRY = "Lapp/revanced/extension/facebook/storyads/Entry;"
private const val CORE = "Lapp/revanced/extension/facebook/storyads/Core;"
private const val APP_CLASS = "Lcom/facebook/katana/app/FacebookApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"

/** "Sponsored" in every language, lower case, Unicode composed, joined by a vertical bar. Compared with the WHOLE drawn string. */
private const val LABELS_SPONSORED = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告|스폰서|광고|ได้รับการสนับสนุน|โฆษณา|प्रायोजित|विज्ञापन|szponzorált|hirdetés|sponsorizat|reclamă|sponsrad|annons|sponsoreret|annonce|sponset|annonse|sponzorované|sponzorirano|sponzorisano|oglas|স্পনসর্ড|স্পনসরড|বিজ্ঞাপন|ditaja|patrocinat|sponsitud|remiama|حمایتشده|حمایت شده|اسپانسرڈ|imedhaminiwa|geborg|スポンサー|спонзорисано|χορηγούμενο|gesponsorde inhoud|sponsored content|sponsrat innehåll|sponsoroitu sisältö|contenu sponsorisé|contenido patrocinado|conteúdo patrocinado|gesponserter beitrag|contenuto sponsorizzato"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val STORYADS_DEX = listOf(
    "ZGV4CjAzOADs7rPcwTvvEvckDp5fvxvSxuE7Yp0ItGJwJAAAcAAAAHhWNBIAAAAAAAAAANAjAAC+AAAAcAAAADEAAABoAwAAQAAA",
    "ACwEAAAbAAAALAcAAF8AAAAECAAAAwAAAPwKAAAUGQAAXAsAAFwLAABeCwAAZgsAAHILAAB7CwAAfgsAAIILAACNCwAAkwsAAJ8L",
    "AACiCwAApQsAAKkLAACsCwAArwsAALMLAAC4CwAAvAsAAMELAADHCwAA0AsAANQLAADaCwAA4QsAAOYLAADsCwAABAwAADoMAABV",
    "DAAAcAwAAJIMAACtDAAAyAwAAOMMAAD9DAAAFg0AADANAABFDQAAWw0AAHANAACKDQAApg0AALsNAADVDQAACA4AACkOAABADgAA",
    "WA4AAIkOAAC7DgAA6w4AAAUPAAAYDwAALA8AAEIPAABWDwAAcQ8AAIUPAACcDwAAuA8AANUPAADtDwAAARAAAAYQAAAMEAAAJBAA",
    "AC4QAABREAAAXhAAAGEQAABlEAAAaRAAAG8QAAB2EAAAfxAAAIsQAACQEAAAlhAAAJ0QAACgEAAApBAAAKkQAACtEAAAsRAAALUQ",
    "AADJEAAA3hAAAPMQAAD3EAAABhEAAAsRAAAhEQAAUBEAAFgRAABdEQAAZREAAGwRAACOEQAAmBEAAJ4RAACsEQAAtREAAMkRAADN",
    "EQAA0xEAAN8RAADpEQAA9hEAAP4RAAAFEgAACxIAABISAAAbEgAAHxIAACsSAAA6EgAARBIAAFISAABdEgAAchIAAIESAACMEgAA",
    "lxIAAKASAAC1EgAAxBIAAM4SAADZEgAA4hIAAOkSAADtEgAA8hIAAAQTAAANEwAAFRMAAB4TAAAnEwAANhMAADoTAABCEwAASBMA",
    "AFATAABUEwAAWRMAAF8TAABpEwAAdBMAAHoTAACEEwAAihMAAJUTAACbEwAAoxMAALYTAADLEwAA3RMAAPATAAANFAAAIBQAADMU",
    "AAA+FAAASxQAAFQUAAB4FAAAiRQAAKIUAACqFAAAsxQAALgUAAC+FAAAyRQAAM8UAADUFAAA2RQAAN8UAADoFAAA7xQAAPYUAAAC",
    "FQAACRUAABYVAAAhFQAALhUAADgVAAA/FQAARhUAAEwVAABRFQAAVRUAAGMVAAAEAAAACQAAAAoAAAAMAAAAGQAAABoAAAAbAAAA",
    "HAAAAB0AAAAeAAAAHwAAACAAAAAhAAAAIgAAACMAAAAkAAAAJQAAACYAAAAnAAAAKAAAACkAAAAqAAAAKwAAACwAAAAtAAAALgAA",
    "AC8AAAAwAAAAMQAAADIAAAAzAAAANAAAADUAAAA2AAAANwAAADgAAAA5AAAAOgAAADsAAAA8AAAAPQAAAEQAAABOAAAAUQAAAFIA",
    "AABTAAAAVAAAAFUAAABWAAAABQAAAAAAAAC0FgAACgAAAAIAAAAAAAAACwAAAAIAAACsFQAADAAAAAMAAAAAAAAAEgAAAAkAAAD8",
    "FgAADQAAAAsAAAAAAAAADQAAABEAAAAAAAAAEwAAABMAAABsFQAADQAAABQAAAAAAAAAEAAAABQAAAC0FgAAFgAAABQAAACoFgAA",
    "DQAAABcAAAAAAAAADQAAABgAAAAAAAAAGAAAABkAAAB8FQAAEQAAAB0AAAB0FgAADQAAAB4AAAAAAAAAFAAAAB4AAADwFQAAFwAA",
    "AB8AAADoFgAADQAAACEAAAAAAAAADwAAACEAAABsFgAAEQAAACEAAAB0FgAAFwAAACEAAADIFQAAFAAAACEAAAAgFwAAFwAAACEA",
    "AAD4FQAAFAAAACEAAACsFQAAFAAAACEAAADwFQAAFAAAACEAAACQFgAAFQAAACEAAADcFgAADgAAACIAAAB8FgAAEAAAACIAAAC0",
    "FgAAFAAAACIAAADwFQAAFwAAACUAAACgFgAARAAAACkAAAAAAAAARQAAACkAAAC0FgAARgAAACkAAABEFgAASwAAACkAAADUFgAA",
    "RgAAACkAAADEFgAARgAAACkAAADQFQAARgAAACkAAAAYFwAASAAAACkAAAAcFgAATAAAACkAAADwFgAATQAAACkAAACEFgAATQAA",
    "ACkAAAA4FgAARgAAACkAAABUFgAARgAAACkAAAAoFgAARgAAACkAAACYFgAARwAAACkAAAAAFgAASQAAACkAAABcFgAASgAAACkA",
    "AADYFQAARgAAACkAAABMFgAASAAAACkAAACIFQAASQAAACkAAAAIFwAASQAAACkAAAAMFgAASgAAACkAAACUFQAARgAAACkAAAC8",
    "FgAARgAAACkAAADMFgAATgAAACoAAAAAAAAATwAAACoAAABEFgAATwAAACoAAADAFQAATwAAACoAAAAgFwAATwAAACoAAACsFQAA",
    "UAAAACoAAAAwFgAATwAAACoAAADwFQAAFAAAADAAAADwFQAACAAIAAMAAAAaAAkAXQAAABoAAgBiAAAAGgAEAGQAAAAaABMAZgAA",
    "ABoAAgBsAAAAGgAQAH8AAAAaAAIAgAAAABoAAgCJAAAAGgACAIsAAAAaACIAjgAAABoAAgCSAAAAGgACAKsAAAAaAAIArAAAABoA",
    "AgCuAAAAGgAqALIAAAAaAAIAuAAAABoAEwC7AAAAHAAhAG4AAAAcAAIAcAAAABwAAgCBAAAAHAACAIIAAAAcAAIAjQAAABwAAgCo",
    "AAAAHAAiALoAAAAmACYAPgAAACgAKAA/AAAABAA6AGUAAAAEAAwAfgAAAAYAJACiAAAACQAEAGMAAAAJAAEAdQAAAAkAAQB9AAAA",
    "CgAmAAEAAAAKAAUAeAAAAAsANgCRAAAAEAAsAAEAAAAQAD0AoAAAABAAMQCjAAAAEQAGAHcAAAASAAMAvAAAABMABwCXAAAAEwAg",
    "AKEAAAATACEAqQAAABQAKwBnAAAAFAABAHUAAAAUADcAdgAAABQACwB7AAAAFAABAHwAAAAUAAEAfQAAABUACQBxAAAAFQABAHIA",
    "AAAXAC0AWgAAABcAOACGAAAAFwAtAKQAAAAYAAgAdAAAABkADQCQAAAAGQAgAKoAAAAaACAAAQAAABoAFgBfAAAAGgAKAG0AAAAa",
    "AD4AhwAAABoAOQCIAAAAGgASAIoAAAAaACIAjwAAABoAGQCUAAAAGgAiAJYAAAAaACMAmAAAABoAIgCZAAAAGgAiAJoAAAAaACIA",
    "mwAAABoAIwCcAAAAGgAiAJ0AAAAaACIAngAAABoAOACfAAAAGgAVAKUAAAAaACAApwAAABoAIgCtAAAAGgAlALAAAAAaADgAtwAA",
    "ABsAJQCEAAAAHAAmAAEAAAAcAC4AWQAAABwAJwBoAAAAHAAoAGgAAAAcACkAaAAAABwAKgBoAAAAHAAvAGkAAAAcADIAaQAAABwA",
    "MwBpAAAAHAA0AGkAAAAcADAAagAAABwANQBqAAAAHQAOALMAAAAeABAAbwAAAB4AHwB5AAAAHgASAHoAAAAfACAAAQAAAB8ADwBz",
    "AAAAIQAAAF4AAAAhADsAYQAAACEAPABrAAAAIQABAIwAAAAhABMApgAAACEAPwCvAAAAIQA+ALEAAAAhABQAtAAAACEAGgC1AAAA",
    "IQASALkAAAAhABgAvQAAACEAGwC9AAAAIgAgAAEAAAAiABwAXAAAACIAHQBcAAAAIgAeAFwAAAAiAAEAjAAAACIAEgC2AAAAIwAC",
    "AIMAAAAjAAMAkwAAACQAEgC2AAAAJQARAIUAAAAnABcAlQAAABoAAAARAAAAHwAAALQVAAAGAAAAAAAAAAMjAAAAAAAAGwAAABEA",
    "AAAfAAAAAAAAAAgAAAAAAAAAgyMAAAAAAAAcAAAAEQAAAAoAAAAAAAAAQQAAAAAAAACLIwAAAAAAAAAABjxpbml0PgAKQUQgbGFi",
    "ZWwgJwAHQUxQSEFfOAABQwACQ0kACUNvcmUuamF2YQAERVhDIAAKRW50cnkuamF2YQABRgABSQACSUwAAUoAAUwAAkxDAANMQ0MA",
    "AkxJAANMSUkABExJSUwAB0xKSklGRkkAAkxMAARMTElJAAVMTElJSQADTExMAARMTExJABZMYW5kcm9pZC9hcHAvQWN0aXZpdHk7",
    "ADRMYW5kcm9pZC9hcHAvQXBwbGljYXRpb24kQWN0aXZpdHlMaWZlY3ljbGVDYWxsYmFja3M7ABlMYW5kcm9pZC9hcHAvQXBwbGlj",
    "YXRpb247ABlMYW5kcm9pZC9jb250ZW50L0NvbnRleHQ7ACBMYW5kcm9pZC9ncmFwaGljcy9CaXRtYXAkQ29uZmlnOwAZTGFuZHJv",
    "aWQvZ3JhcGhpY3MvQml0bWFwOwAZTGFuZHJvaWQvZ3JhcGhpY3MvQ2FudmFzOwAZTGFuZHJvaWQvZ3JhcGhpY3MvTWF0cml4OwAY",
    "TGFuZHJvaWQvZ3JhcGhpY3MvUGFpbnQ7ABdMYW5kcm9pZC9ncmFwaGljcy9SZWN0OwAYTGFuZHJvaWQvZ3JhcGhpY3MvUmVjdEY7",
    "ABNMYW5kcm9pZC9vcy9CdW5kbGU7ABRMYW5kcm9pZC9vcy9IYW5kbGVyOwATTGFuZHJvaWQvb3MvTG9vcGVyOwAYTGFuZHJvaWQv",
    "b3MvU3lzdGVtQ2xvY2s7ABpMYW5kcm9pZC92aWV3L01vdGlvbkV2ZW50OwATTGFuZHJvaWQvdmlldy9WaWV3OwAYTGFuZHJvaWQv",
    "dmlldy9WaWV3R3JvdXA7ADFMYW5kcm9pZC92aWV3L1ZpZXdUcmVlT2JzZXJ2ZXIkT25QcmVEcmF3TGlzdGVuZXI7AB9MYW5kcm9p",
    "ZC92aWV3L1ZpZXdUcmVlT2JzZXJ2ZXI7ABVMYW5kcm9pZC92aWV3L1dpbmRvdzsAFkxhbmRyb2lkL3dpZGdldC9Ub2FzdDsAL0xh",
    "cHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL3N0b3J5YWRzL0NvcmU7ADBMYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNl",
    "Ym9vay9zdG9yeWFkcy9FbnRyeTsALkxhcHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL3N0b3J5YWRzL1JlYzsAGExqYXZh",
    "L2xhbmcvQ2hhclNlcXVlbmNlOwARTGphdmEvbGFuZy9DbGFzczsAEkxqYXZhL2xhbmcvT2JqZWN0OwAUTGphdmEvbGFuZy9SdW5u",
    "YWJsZTsAEkxqYXZhL2xhbmcvU3RyaW5nOwAZTGphdmEvbGFuZy9TdHJpbmdCdWlsZGVyOwASTGphdmEvbGFuZy9TeXN0ZW07ABVM",
    "amF2YS9sYW5nL1Rocm93YWJsZTsAGkxqYXZhL2xhbmcvcmVmbGVjdC9NZXRob2Q7ABtMamF2YS90ZXh0L05vcm1hbGl6ZXIkRm9y",
    "bTsAFkxqYXZhL3RleHQvTm9ybWFsaXplcjsAEkxqYXZhL3V0aWwvTG9jYWxlOwADTkZDAARST09UABZSVjogc3RvcnkgYWQgc2tp",
    "cHBlZCAoAAhSZWMuamF2YQAhU0xPVyByZW5kZXIsIGxvb2tpbmcgZXZlcnkgMTIwIG1zAAtTdG9yeVZpZXdlcgABVgACVkkAAlZM",
    "AARWTEZGAAVWTEZGTAAHVkxJSUZGTAAKVkxJSUlJRkZaTAADVkxMAARWTExMAAVWTExMTAABWgACWkwAA1pMSgACW0MAAltGAAJb",
    "SQASW0xqYXZhL2xhbmcvQ2xhc3M7ABNbTGphdmEvbGFuZy9PYmplY3Q7ABNbTGphdmEvbGFuZy9TdHJpbmc7AAJcfAANX19SVl9M",
    "QUJFTFNfXwADYWRkABRhZGRPblByZURyYXdMaXN0ZW5lcgAtYXBwLnJldmFuY2VkLmV4dGVuc2lvbi5mYWNlYm9vay5zdG9yeWFk",
    "cy5Db3JlAAZhcHBlbmQAA2JtcAAGY2hhckF0AAVjbGVhbgAgY29tLmZhY2Vib29rLmxpdGhvLkNvbXBvbmVudEhvc3QACGNvbnRh",
    "aW5zAARjb29sAAxjcmVhdGVCaXRtYXAAB2N1cnJlbnQAEmRpc3BhdGNoVG91Y2hFdmVudAACZG4ABGRyYXcACmRyYXdCaXRtYXAA",
    "CGRyYXdUZXh0AAtkcmF3VGV4dFJ1bgAGZXF1YWxzAAVmYWlscwAEZmluZAAFZmlyc3QAB2Zvck5hbWUAAmZ5AApnZXRDaGlsZEF0",
    "AA1nZXRDaGlsZENvdW50AAhnZXRDbGFzcwAMZ2V0RGVjb3JWaWV3AAlnZXRIZWlnaHQAE2dldExvY2F0aW9uT25TY3JlZW4ADWdl",
    "dE1haW5Mb29wZXIACWdldE1hdHJpeAAJZ2V0TWV0aG9kAAdnZXROYW1lABNnZXRWaWV3VHJlZU9ic2VydmVyAA1nZXRWaXNpYmls",
    "aXR5AAhnZXRXaWR0aAAJZ2V0V2luZG93AAdoYW5kbGVyAAVoZWF2eQACaGkAA2hpdAAQaWRlbnRpdHlIYXNoQ29kZQAHaW5zdGFs",
    "bAAGaW52b2tlAAdpc0FsaXZlAAdpc0V4YWN0AA1pc1N0b3J5Vmlld2VyAAJpdgAGbGFiZWxzAARsYXN0AAZsZW5ndGgAAmxvAANs",
    "b2cABGxvb2sACG1ha2VUZXh0AAltYXBQb2ludHMABG1pc3MACG5hbm9UaW1lAARub3JtAAlub3JtYWxpemUABG5vdGUABm9idGFp",
    "bgARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0cm95ZWQAEG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1",
    "bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9uQWN0aXZpdHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAAJb25Q",
    "cmVEcmF3AAtwb3N0RGVsYXllZAAHcmVjeWNsZQAicmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2Fs",
    "bGJhY2tzABdyZW1vdmVPblByZURyYXdMaXN0ZW5lcgAGcmVuZGVyAAdyZXBsYWNlAANydW4ABHNlZW4ACXNldFNvdXJjZQAEc2hv",
    "dwADc2tIAANza1QABHNraXAAB3NraXBwZWQABXNwbGl0AAVzdGFydAAKc3RhcnRzV2l0aAAFc3RvcnkAC3N1YlNlcXVlbmNlAAlz",
    "dWJzdHJpbmcAC3RvTG93ZXJDYXNlAAh0b1N0cmluZwAFdG9hc3QABXRyaWVzAAR0cmltAAN0eHQAAnVwAAx1cHRpbWVNaWxsaXMA",
    "B3ZhbHVlT2YABgAAAAMAAwACAAEAAQACAAMAAAAHAB0AAgAAAAQAAAAhAAEAAQAMAAkAAAArAAIAAgACAAIAAQABACoADAAAAAEA",
    "AAAfAAAAAwAAAAUAFgAgAAAAAQAAABMAAAACAAAAFAAcAAEAAAAGAAAACQAAAB0AAgACAAIAAgABAAEAKgAMAAAAAQAAACEAAAAC",
    "AAAAHQAmAAMAAAAdAAEAAQAAAAYAAAArAAIAAgABAAEADAAEAAAACQABAAEADAABAAAAEQAAAAIAAAAgAAMABAAAAAkADQAOAAwA",
    "AQAAAAQAAAABAAAAIAAAAAEAAAAKAAAABgAAAB0AAgACAAEAAQAMAAIAAAAAAAAAAgAAAAIAAgABAAAAAAAAAAQAAAAJAA0ADQAM",
    "AAEAAAAoAAAAAQAAABYAAAACAAAAIQAuAAQAAAAUAAIAAgACAAEAAAACAAAAAQAAACwAAAABAAAABQAAAAEAAAAtAAAAAgAAAAQA",
    "DwADAAAAKwACAAIAAAACAAAAHwAvAAMAAAAJAAsADAAAAAMAAAACAAIACAAAAAYAAAAhAAIAAgABAAEADAABAAAACQAAAAEAAAAd",
    "AAAAAAAAAAMAAQACAAAAAAAAABoAAABwEEYAAgAiABAAcQAMAAAADAFwIAkAEABbIAYAIgAiAHAQVAAAAFsgCgATAB4AWSAIAA4A",
    "BQABAAMAAAAAAAAAIAAAAHEQUgAEAAwAEwEKABMCIABuMEwAEAIMABMBDQBuMEwAEAIMAG4QSwAAAAoBEwPIADcxBwASAW4wTwAQ",
    "AwwAEQAOAAUABQAAAAAAAABuAAAAOApsABMAKAA1DWgAbhAVAAoACgA5AGIAbhBHAAoADABuEEUAAAAMABoBYABuIEoAEAAKADgA",
    "NgBuEBYACgAKANoBC1rbAQFkNBAsAG4QEgAKAAoA2gELB9sBAWQ0ECIA2gELKNsBAWQ2EBwAEiAjAC0AbiATAAoAEgFEAQAB2wIL",
    "AnsjNzEOADUhDAASEUQBAAE6AQcA2wIMBTYhAwARCiCgFQA4ABoAHwoVAG4QGAAKAAoBEgLYBA0BNRIPAG4gFwAqAAwD2AICAXBU",
    "IQA5ywwDOAP0/xEDEgARAAkAAQADAAAAAAAAAEoAAAA5CAQAEgAPAHEQJgAIAAwBbhBLAAEACgI4AjwAEwNQADYyOABxACQAAAAM",
    "AxoEVwBuIE0AQwAMAyE0EgU1RSoARgYDBdgFBQFuEEsABgAKAtgCAv89AhYAbiBIACYACgATByoAM3AOABIAbjBPAAYCDAZuIE4A",
    "YQAKADgA4f8oB24gSgBhAAoAOADa/xIQDwASAA8AAwABAAIAAAAAAAAADwAAAG4QRwACAAwAbhBFAAAADAAaAUMAbiBJABAACgAP",
    "AAAAAQAAAAAAAAAAAAAAAwAAABoAWAARAAAADgACAAUAAAAAAAAAzAAAAG4QAQANAAwAbhAcAAAADABuEBYAAAAKAT0BvwBuEBIA",
    "AAAKAhIDcFMhAAwhDAM5AwkAUsQLANgEBAFZxAsADgASBFnECwBxEFoAAwAKBFLFDAAzVAsAUsUJAFLGDQCxZRMGsAQ0ZZkAbhAS",
    "AAMACgQ9BJMAVMUBADgFDgBuEAUABQAKBjMWCABuEAQABQAKBjJGCgBiBgAAcTADAEEGDAVbxQEAIgYcAHAgNgBWANoHAQnbBwdk",
    "WWcWANoHARnbBwdkWWcUAHEAWwAAAAsIcSAwAGMADAdxAFsAAAALCryKhKEUAkBLTAA2IQYAEgFZwQcAKB5SwQcA2AEBAVnBBwAS",
    "YjQhFgBSwQgAEwJ4ADIhEABZwggAVMQKABoFQgBuIFcAVAATBQoAbiBVAFQAbhBLAAcACgc9BwkAUsQFANgEBAFZxAUADgBSZxUA",
    "PQciAFTECgAaBQIAbiBXAFQAVGUSAG4gVwBUABMFJwBuIFUAVAATBQoAbiBVAFQAcRBaAAMACgVZxQwAcCAyANwADgASBFnEEAAO",
    "AAcAAQACAAAAAAAAAEwAAABuEFEABgAMAGIBGQBxIF4AEAAMAG4QSwAAAAoBIgIiAHAQVAACABIDNRMpAG4gSAAwAAoE2AMDARMF",
    "oAAzVAUAEwQgACgXEwULIDRUFAATBS4gN1QIABQF//4AADJU5v8oCRMFDyA3VOH/EwUoIDVU3f9uIFUAQgAo2G4QWQACAAwAbhBR",
    "AAAADABiARoAbiBQABAADAARAAUAAgADAAAAAAAAACcAAABxADQAAAAKADgAIgAiACIAcBBUAAAAGgFAAG4gVwAQAFIxDgBuIFYA",
    "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",
    "LBcAAAAgAAADAAAAAyMAAAAQAAABAAAA0CMAAA==",
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
val hideStoryAdsPatch = bytecodePatch(
    name = "Hide story ads for android 8 app",
    description = "ANDROID 8 and ANDROID 11 (all builds). Skips Sponsored stories: while the story viewer is open it reads what " +
        "the app draws in the story header (the \"Sponsored\" label in 40+ languages, e.g. \"Được tài trợ\") and, when it is an " +
        "ad, sends the same quick tap on the right side of the screen that a finger would, so the viewer goes to the next story at once (it looks about 30 times a second). " +
        "Works at run time on what is on the screen, so it does not depend on obfuscated names and works on the Dextricks " +
        "(Android 8) builds. Adds no file to the apk, no resource, no manifest change. Does nothing outside the story viewer. " +
        "Option: a small toast \"RV: story ad skipped (N)\".",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val showToast by booleanOption(
        name = "Show toast skipped",
        description = "Shows a short \"RV: story ad skipped (N)\" message each time an ad story is skipped, so you can see that " +
            "it works. Turn it off when you have tested it.",
        default = false,
    )

    apply {
        val log = Logger.getLogger("HideStoryAds")

        if (classDefs[ENTRY] != null) {
            log.info("Hide story ads: Entry is already in the dex, nothing to add")
            return@apply
        }

        // ---- 1. Find the hook (read only) ------------------------------------------------------------------------
        val hookClass = findHookClass()
            ?: throw PatchException(
                "Hide story ads: no Application.onCreate() found ($APP_CLASS or a class extending " +
                    "$ANDROID_APPLICATION). Send the smali of the app's Application class.",
            )

        // ---- 2. Add the three classes into the existing dex ------------------------------------------------------
        val added = try {
            DexBackedDexFile(null, Base64.getDecoder().decode(STORYADS_DEX), 0).classes.toList()
        } catch (e: Exception) {
            throw PatchException("Hide story ads: cannot read the embedded classes: $e")
        }
        if (added.size != 3 || added.none { it.type == ENTRY } || added.none { it.type == CORE }) {
            throw PatchException("Hide story ads: the embedded dex does not hold Entry, Core and Rec (${added.map { it.type }})")
        }
        @Suppress("UNCHECKED_CAST")
        val mutableClassDefs = classDefs as MutableSet<ClassDef>
        for (def in added) {
            if (!mutableClassDefs.add(def)) {
                throw PatchException("Hide story ads: could not add ${def.type} to the dex")
            }
        }

        // ---- 3. Label list and the option go into Core -----------------------------------------------------------
        val core = added.first { it.type == CORE }
        val coreMethods = classDefs.getOrReplaceMutable(core).methods
        val labelsMethod = coreMethods
            .firstOrNull { it.name == "labels" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: throw PatchException("Hide story ads: Core.labels() not found")
        labelsMethod.replaceInstruction(0, "const-string v0, \"${smaliString(LABELS_SPONSORED)}\"")

        if (showToast == false) {
            val m = coreMethods.firstOrNull { it.name == "toast" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide story ads: Core.toast() not found")
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
            "Hide story ads: hooked ${hookClass.type}->onCreate(), ${added.size} classes added, toast=${showToast != false}",
        )
    }
}
