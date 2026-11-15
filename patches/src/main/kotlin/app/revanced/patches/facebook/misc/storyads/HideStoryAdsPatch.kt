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
 *   1. before a frame is drawn (ViewTreeObserver.OnPreDrawListener, at most every 120 ms) it looks for the header strip of the
 *      story that is on the screen: a com.facebook.litho.ComponentHost that is at least 90% of the window wide, 7% .. 40% of its
 *      width tall, at the left edge of the window and in the upper fifth of it (the stories next to it in the pager are outside
 *      the window and are not taken);
 *   2. it draws that one strip ONCE into a small throw-away bitmap through a Canvas subclass (Rec) that records the strings
 *      drawn between 9% and 25% of the strip width from its top (the page name is above that band) and checks them against
 *      the Sponsored labels (whole string, lower case, Unicode composed, no-break spaces dropped);
 *   3. when a label is drawn the story is an ad and a quick tap (DOWN, then UP 40 ms later) is sent to the story viewer on the
 *      right side of the screen (85% of the width, 45% of the height): the viewer goes to the next story. A cool-down of 1.2 s
 *      follows each skip (the next story needs time to appear); after 3 skips in a row without a normal story in between it rests
 *      for 5 s, so a story that does not go away can never make a loop of taps.
 *
 * Nothing is hidden by changing views and nothing of the viewer is touched: the viewer is only asked, like a finger would, to
 * show the next story. The tap is the one thing that could be wrong on a version whose viewer reacts differently (for
 * example when the ad has a button at that place): then the skip simply does not happen (and stops after 3 tries).
 *
 * Costs: the tree walk is a few dozen views at most every 120 ms; the header strip (about 1080 x 200) is drawn once per look into
 * one ALPHA_8 bitmap of window width x strip height, kept between looks.
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. If drawing the strip fails 6 times the patch switches itself off for that run.
 *
 * Limits (honest):
 *   - The story is seen for the time it takes to find the label (a few frames, at most about 150 ms) before it is skipped.
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
private const val LABELS_SPONSORED = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val STORYADS_DEX = listOf(
    "ZGV4CjAzOABBl5tcdMSnYachl+pJdkXfT2fhYXgR0DQAIwAAcAAAAHhWNBIAAAAAAAAAAGAiAAC1AAAAcAAAADAAAABEAwAAPwAA",
    "AAQEAAAXAAAA+AYAAF0AAACwBwAAAwAAAJgKAAAIGAAA+AoAAPgKAAD6CgAAAgsAAA4LAAAXCwAAGgsAAB4LAAApCwAALwsAADsL",
    "AAA+CwAAQQsAAEQLAABHCwAASwsAAFALAABUCwAAWQsAAF8LAABoCwAAbAsAAHILAAB5CwAAfgsAAIQLAACcCwAA0gsAAO0LAAAI",
    "DAAAKgwAAEUMAABgDAAAewwAAJUMAACuDAAAyAwAAN0MAADzDAAACA0AACINAAA+DQAAUw0AAG0NAACgDQAAwQ0AANgNAADwDQAA",
    "IQ4AAFMOAACDDgAAnQ4AALAOAADEDgAA2g4AAO4OAAAJDwAAIA8AADwPAABZDwAAcQ8AAIUPAACKDwAAkA8AAKgPAACyDwAAvw8A",
    "AMIPAADGDwAAyg8AANAPAADXDwAA4A8AAOwPAADxDwAA9w8AAP4PAAABEAAABRAAAAoQAAAOEAAAEhAAABYQAAAqEAAAPxAAAFQQ",
    "AABYEAAAZxAAAGwQAACCEAAAsRAAALkQAAC+EAAAxhAAAM0QAADvEAAA+RAAAP8QAAANEQAAFhEAACoRAAAuEQAANBEAAEARAABK",
    "EQAAVxEAAF8RAABmEQAAbBEAAHMRAAB8EQAAgBEAAIwRAACbEQAApREAALMRAAC+EQAA0xEAAOIRAADtEQAA+BEAAAESAAAWEgAA",
    "JRIAAC8SAAA6EgAAQxIAAEcSAABMEgAAVRIAAF0SAABmEgAAbxIAAH4SAACGEgAAjBIAAJQSAACYEgAAnRIAAKMSAACtEgAAuBIA",
    "AL4SAADEEgAAzxIAANUSAADdEgAA8BIAAAUTAAAXEwAAKhMAAEcTAABaEwAAbRMAAHgTAACFEwAAjhMAALITAADDEwAA3BMAAOQT",
    "AADtEwAA8hMAAPgTAAADFAAACRQAAA8UAAAYFAAAHxQAACYUAAAyFAAAORQAAEYUAABRFAAAXhQAAGgUAABvFAAAdhQAAHwUAACB",
    "FAAAhRQAAJMUAAAEAAAACQAAAAoAAAALAAAAGAAAABkAAAAaAAAAGwAAABwAAAAdAAAAHgAAAB8AAAAgAAAAIQAAACIAAAAjAAAA",
    "JAAAACUAAAAmAAAAJwAAACgAAAApAAAAKgAAACsAAAAsAAAALQAAAC4AAAAvAAAAMAAAADEAAAAyAAAAMwAAADQAAAA1AAAANgAA",
    "ADcAAAA4AAAAOQAAADoAAAA7AAAAQQAAAEsAAABOAAAATwAAAFAAAABRAAAAUgAAAFMAAAAFAAAAAAAAAOQVAAAKAAAAAgAAAAAA",
    "AAALAAAAAwAAAAAAAAARAAAACQAAACwWAAAMAAAACwAAAAAAAAAMAAAAEQAAAAAAAAASAAAAEwAAAJwUAAAMAAAAFAAAAAAAAAAP",
    "AAAAFAAAAOQVAAAVAAAAFAAAANgVAAAMAAAAFwAAAAAAAAAMAAAAGAAAAAAAAAAXAAAAGQAAAKwUAAAQAAAAHQAAAKQVAAAMAAAA",
    "HgAAAAAAAAATAAAAHgAAACAVAAAWAAAAHwAAABgWAAAMAAAAIQAAAAAAAAAOAAAAIQAAAJwVAAAQAAAAIQAAAKQVAAAWAAAAIQAA",
    "APgUAAATAAAAIQAAAFAWAAAWAAAAIQAAACgVAAATAAAAIQAAANwUAAATAAAAIQAAACAVAAATAAAAIQAAAMAVAAAUAAAAIQAAAAwW",
    "AAANAAAAIgAAAKwVAAAPAAAAIgAAAOQVAAATAAAAIgAAACAVAAAWAAAAJAAAANAVAABBAAAAKAAAAAAAAABCAAAAKAAAAOQVAABD",
    "AAAAKAAAAHQVAABIAAAAKAAAAAQWAABDAAAAKAAAAPQVAABDAAAAKAAAAAAVAABDAAAAKAAAAEgWAABFAAAAKAAAAEwVAABJAAAA",
    "KAAAACAWAABKAAAAKAAAALQVAABKAAAAKAAAAGgVAABDAAAAKAAAAIQVAABDAAAAKAAAAFgVAABDAAAAKAAAAMgVAABEAAAAKAAA",
    "ADAVAABGAAAAKAAAAIwVAABHAAAAKAAAAAgVAABDAAAAKAAAAHwVAABFAAAAKAAAALgUAABGAAAAKAAAADgWAABGAAAAKAAAADwV",
    "AABHAAAAKAAAAMQUAABDAAAAKAAAAOwVAABDAAAAKAAAAPwVAABLAAAAKQAAAAAAAABMAAAAKQAAAHQVAABMAAAAKQAAAPAUAABM",
    "AAAAKQAAAFAWAABMAAAAKQAAANwUAABNAAAAKQAAAGAVAABMAAAAKQAAACAVAAATAAAALwAAACAVAAAIAAgAAwAAABoACQBaAAAA",
    "GgACAF8AAAAaAAQAYQAAABoAEwBjAAAAGgACAGkAAAAaABAAfAAAABoAAgCFAAAAGgAiAIgAAAAaAAIAjAAAABoAAgClAAAAGgAp",
    "AKkAAAAaAAIArwAAABoAEwCyAAAAHAAhAGsAAAAcAAIAbQAAABwAAgB9AAAAHAACAH4AAAAcAAIAhwAAABwAAgChAAAAHAAiALEA",
    "AAAlACUAPAAAACcAJwA9AAAABAA5AGIAAAAEAAsAewAAAAYAIwCbAAAACQADAGAAAAAJAAEAcgAAAAkAAQB6AAAACgAlAAEAAAAK",
    "AAQAdQAAAAsANQCLAAAAEAArAAEAAAAQADwAmQAAABAAMACcAAAAEQAFAHQAAAASAAIAswAAABMABgCQAAAAEwAfAJoAAAATACAA",
    "ogAAABQAKgBkAAAAFAABAHIAAAAUADYAcwAAABQACgB4AAAAFAABAHkAAAAUAAEAegAAABUACABuAAAAFQABAG8AAAAXACwAVwAA",
    "ABcANwCBAAAAFwAsAJ0AAAAYAAcAcQAAABkADACKAAAAGQAfAKMAAAAaAB8AAQAAABoAFQBcAAAAGgAJAGoAAAAaAD0AggAAABoA",
    "OACDAAAAGgARAIQAAAAaACEAiQAAABoAGACNAAAAGgAhAI8AAAAaACIAkQAAABoAIQCSAAAAGgAhAJMAAAAaACEAlAAAABoAIgCV",
    "AAAAGgAhAJYAAAAaACEAlwAAABoANwCYAAAAGgAUAJ4AAAAaAB8AoAAAABoAIQCkAAAAGgAkAKcAAAAaADcArgAAABsAJAB/AAAA",
    "HAAlAAEAAAAcAC0AVgAAABwAJgBlAAAAHAAnAGUAAAAcACgAZQAAABwAKQBlAAAAHAAuAGYAAAAcADEAZgAAABwAMgBmAAAAHAAz",
    "AGYAAAAcAC8AZwAAABwANABnAAAAHQANAKoAAAAeAA8AbAAAAB4AHgB2AAAAHgARAHcAAAAfAB8AAQAAAB8ADgBwAAAAIQAAAFsA",
    "AAAhADoAXgAAACEAOwBoAAAAIQABAIYAAAAhABIAnwAAACEAPgCmAAAAIQA9AKgAAAAhABMAqwAAACEAGQCsAAAAIQARALAAAAAh",
    "ABcAtAAAACEAGgC0AAAAIgAfAAEAAAAiABsAWQAAACIAHABZAAAAIgAdAFkAAAAiAAEAhgAAACIAEQCtAAAAIwARAK0AAAAkABAA",
    "gAAAACYAFgCOAAAAGgAAABEAAAAfAAAA5BQAAAYAAAAAAAAAmyEAAAAAAAAbAAAAEQAAAB8AAAAAAAAACAAAAAAAAAATIgAAAAAA",
    "ABwAAAARAAAACgAAAAAAAAA/AAAAAAAAABsiAAAAAAAAAAAGPGluaXQ+AApBRCBsYWJlbCAnAAdBTFBIQV84AAFDAAJDSQAJQ29y",
    "ZS5qYXZhAARFWEMgAApFbnRyeS5qYXZhAAFGAAFJAAFKAAFMAAJMQwADTENDAAJMSQADTElJAARMSUlMAAdMSkpJRkZJAAJMTAAE",
    "TExJSQAFTExJSUkAA0xMTAAETExMSQAWTGFuZHJvaWQvYXBwL0FjdGl2aXR5OwA0TGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uJEFj",
    "dGl2aXR5TGlmZWN5Y2xlQ2FsbGJhY2tzOwAZTGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uOwAZTGFuZHJvaWQvY29udGVudC9Db250",
    "ZXh0OwAgTGFuZHJvaWQvZ3JhcGhpY3MvQml0bWFwJENvbmZpZzsAGUxhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcDsAGUxhbmRyb2lk",
    "L2dyYXBoaWNzL0NhbnZhczsAGUxhbmRyb2lkL2dyYXBoaWNzL01hdHJpeDsAGExhbmRyb2lkL2dyYXBoaWNzL1BhaW50OwAXTGFu",
    "ZHJvaWQvZ3JhcGhpY3MvUmVjdDsAGExhbmRyb2lkL2dyYXBoaWNzL1JlY3RGOwATTGFuZHJvaWQvb3MvQnVuZGxlOwAUTGFuZHJv",
    "aWQvb3MvSGFuZGxlcjsAE0xhbmRyb2lkL29zL0xvb3BlcjsAGExhbmRyb2lkL29zL1N5c3RlbUNsb2NrOwAaTGFuZHJvaWQvdmll",
    "dy9Nb3Rpb25FdmVudDsAE0xhbmRyb2lkL3ZpZXcvVmlldzsAGExhbmRyb2lkL3ZpZXcvVmlld0dyb3VwOwAxTGFuZHJvaWQvdmll",
    "dy9WaWV3VHJlZU9ic2VydmVyJE9uUHJlRHJhd0xpc3RlbmVyOwAfTGFuZHJvaWQvdmlldy9WaWV3VHJlZU9ic2VydmVyOwAVTGFu",
    "ZHJvaWQvdmlldy9XaW5kb3c7ABZMYW5kcm9pZC93aWRnZXQvVG9hc3Q7AC9MYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9v",
    "ay9zdG9yeWFkcy9Db3JlOwAwTGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svc3RvcnlhZHMvRW50cnk7AC5MYXBwL3Jl",
    "dmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9zdG9yeWFkcy9SZWM7ABhMamF2YS9sYW5nL0NoYXJTZXF1ZW5jZTsAEUxqYXZhL2xh",
    "bmcvQ2xhc3M7ABJMamF2YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcvUnVubmFibGU7ABJMamF2YS9sYW5nL1N0cmluZzsAGUxq",
    "YXZhL2xhbmcvU3RyaW5nQnVpbGRlcjsAFUxqYXZhL2xhbmcvVGhyb3dhYmxlOwAaTGphdmEvbGFuZy9yZWZsZWN0L01ldGhvZDsA",
    "G0xqYXZhL3RleHQvTm9ybWFsaXplciRGb3JtOwAWTGphdmEvdGV4dC9Ob3JtYWxpemVyOwASTGphdmEvdXRpbC9Mb2NhbGU7AANO",
    "RkMABFJPT1QAFlJWOiBzdG9yeSBhZCBza2lwcGVkICgACFJlYy5qYXZhAAtTdG9yeVZpZXdlcgABVgACVkkAAlZMAARWTEZGAAVW",
    "TEZGTAAHVkxJSUZGTAAKVkxJSUlJRkZaTAADVkxMAARWTExMAAVWTExMTAABWgACWkwAA1pMSgACW0MAAltGAAJbSQASW0xqYXZh",
    "L2xhbmcvQ2xhc3M7ABNbTGphdmEvbGFuZy9PYmplY3Q7ABNbTGphdmEvbGFuZy9TdHJpbmc7AAJcfAANX19SVl9MQUJFTFNfXwAD",
    "YWRkABRhZGRPblByZURyYXdMaXN0ZW5lcgAtYXBwLnJldmFuY2VkLmV4dGVuc2lvbi5mYWNlYm9vay5zdG9yeWFkcy5Db3JlAAZh",
    "cHBlbmQAA2JtcAAGY2hhckF0AAVjbGVhbgAgY29tLmZhY2Vib29rLmxpdGhvLkNvbXBvbmVudEhvc3QACGNvbnRhaW5zAARjb29s",
    "AAxjcmVhdGVCaXRtYXAAB2N1cnJlbnQAEmRpc3BhdGNoVG91Y2hFdmVudAACZG4ABGRyYXcACmRyYXdCaXRtYXAACGRyYXdUZXh0",
    "AAtkcmF3VGV4dFJ1bgAGZXF1YWxzAAVmYWlscwAEZmluZAAFZmlyc3QAB2Zvck5hbWUAAmZ5AApnZXRDaGlsZEF0AA1nZXRDaGls",
    "ZENvdW50AAhnZXRDbGFzcwAMZ2V0RGVjb3JWaWV3AAlnZXRIZWlnaHQAE2dldExvY2F0aW9uT25TY3JlZW4ADWdldE1haW5Mb29w",
    "ZXIACWdldE1hdHJpeAAJZ2V0TWV0aG9kAAdnZXROYW1lABNnZXRWaWV3VHJlZU9ic2VydmVyAA1nZXRWaXNpYmlsaXR5AAhnZXRX",
    "aWR0aAAJZ2V0V2luZG93AAdoYW5kbGVyAAJoaQADaGl0AAdpbnN0YWxsAAZpbnZva2UAB2lzQWxpdmUAB2lzRXhhY3QADWlzU3Rv",
    "cnlWaWV3ZXIABmxhYmVscwAEbGFzdAAGbGVuZ3RoAAJsbwADbG9nAARsb29rAAhtYWtlVGV4dAAJbWFwUG9pbnRzAARtaXNzAARu",
    "b3JtAAlub3JtYWxpemUABG5vdGUABm9idGFpbgARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0cm95ZWQAEG9uQWN0",
    "aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9uQWN0aXZpdHlTdGFy",
    "dGVkABFvbkFjdGl2aXR5U3RvcHBlZAAJb25QcmVEcmF3AAtwb3N0RGVsYXllZAAHcmVjeWNsZQAicmVnaXN0ZXJBY3Rpdml0eUxp",
    "ZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2FsbGJhY2tzABdyZW1vdmVPblByZURyYXdMaXN0ZW5lcgAGcmVuZGVyAAdyZXBsYWNl",
    "AANydW4ABHNlZW4ACXNldFNvdXJjZQAEc2hvdwAEc2tpcAAHc2tpcHBlZAAFc3BsaXQABXN0YXJ0AApzdGFydHNXaXRoAAVzdG9y",
    "eQALc3ViU2VxdWVuY2UACXN1YnN0cmluZwALdG9Mb3dlckNhc2UACHRvU3RyaW5nAAV0b2FzdAAFdHJpZXMABHRyaW0AA3R4dAAC",
    "dXAADHVwdGltZU1pbGxpcwAHdmFsdWVPZgAGAAAAAwADAAIAAQABAAIAAwAAAAcAHQACAAAABAAAACEAAQABAAwACQAAACoAAgAC",
    "AAIAAgABAAEAKQAMAAAAAQAAAB8AAAADAAAABQAWACAAAAABAAAAEwAAAAIAAAAUABwAAQAAAAYAAAAJAAAAHQACAAIAAgACAAEA",
    "AQApAAwAAAABAAAAIQAAAAIAAAAdACUAAwAAAB0AAQABAAAABgAAACoAAgACAAEAAQAMAAQAAAAJAAEAAQAMAAEAAAARAAAAAgAA",
    "ACAAAwAEAAAACQANAA4ADAABAAAABAAAAAEAAAAgAAAAAQAAAAoAAAAGAAAAHQACAAIAAQABAAwAAgAAAAAAAAACAAAAAgACAAEA",
    "AAAAAAAABAAAAAkADQANAAwAAQAAACcAAAABAAAAFgAAAAIAAAAhAC0ABAAAABQAAgACAAIAAQAAAAIAAAABAAAAKwAAAAEAAAAF",
    "AAAAAQAAACwAAAACAAAABAAPAAMAAAAqAAIAAgAAAAIAAAAfAC4AAwAAAAkACwAMAAAAAwAAAAIAAgAIAAAABgAAACEAAgACAAEA",
    "AQAMAAEAAAAJAAAAAQAAAB0AAAAAAAAAAwABAAIAAAAAAAAAFgAAAHAQRgACACIAEABxAAwAAAAMAXAgCQAQAFsgBgAiACIAcBBU",
    "AAAAWyAIAA4ABQABAAMAAAAAAAAAIAAAAHEQUgAEAAwAEwEKABMCIABuMEwAEAIMABMBDQBuMEwAEAIMAG4QSwAAAAoBEwPIADcx",
    "BwASAW4wTwAQAwwAEQAOAAUABQAAAAAAAABuAAAAOApsABMAKAA1DWgAbhAVAAoACgA5AGIAbhBHAAoADABuEEUAAAAMABoBXQBu",
    "IEoAEAAKADgANgBuEBYACgAKANoBC1rbAQFkNBAsAG4QEgAKAAoA2gELB9sBAWQ0ECIA2gELKNsBAWQ2EBwAEiAjACwAbiATAAoA",
    "EgFEAQAB2wILAnsjNzEOADUhDAASEUQBAAE6AQcA2wIMBTYhAwARCiCgFQA4ABoAHwoVAG4QGAAKAAoBEgLYBA0BNRIPAG4gFwAq",
    "AAwD2AICAXBUIQA5ywwDOAP0/xEDEgARAAkAAQADAAAAAAAAAEoAAAA5CAQAEgAPAHEQJgAIAAwBbhBLAAEACgI4AjwAEwNQADYy",
    "OABxACQAAAAMAxoEVABuIE0AQwAMAyE0EgU1RSoARgYDBdgFBQFuEEsABgAKAtgCAv89AhYAbiBIACYACgATByoAM3AOABIAbjBP",
    "AAYCDAZuIE4AYQAKADgA4f8oB24gSgBhAAoAOADa/xIQDwASAA8AAwABAAIAAAAAAAAADwAAAG4QRwACAAwAbhBFAAAADAAaAUAA",
    "biBJABAACgAPAAAAAQAAAAAAAAAAAAAAAwAAABoAVQARAAAACgACAAUAAAAAAAAAhQAAAG4QAQAJAAwAbhAcAAAADABuEBYAAAAK",
    "AT0BeABuEBIAAAAKAhIDcFMhAAghDAM5AwkAUoQJANgEBAFZhAkADgASBFmECQBuEBIAAwAKBD0EXQBUhQEAOAUOAG4QBQAFAAoG",
    "MxYIAG4QBAAFAAoGMkYKAGIGAABxMAMAQQYMBVuFAQAiBhwAcCA2AFYA2gcBCdsHB2RZZxIA2gcBGdsHB2RZZxAAcSAwAGMADAdu",
    "EEsABwAKBz0HCQBShAUA2AQEAVmEBQAOAFJnEQA9BxwAVIQIABoFAgBuIFcAVABUZQ4AbiBXAFQAEwUnAG4gVQBUABMFCgBuIFUA",
    "VABwIDIAmAAOABIEWYQMAA4AAAAHAAEAAgAAAAAAAABMAAAAbhBRAAYADABiARUAcSBcABAADABuEEsAAAAKASICIgBwEFQAAgAS",
    "AzUTKQBuIEgAMAAKBNgDAwETBaAAM1QFABMEIAAoFxMFCyA0VBQAEwUuIDdUCAAUBf/+AAAyVOb/KAkTBQ8gN1Th/xMFKCA1VN3/",
    "biBVAEIAKNhuEFkAAgAMAG4QUQAAAAwAYgEWAG4gUAAQAAwAEQAFAAIAAwAAAAAAAAAnAAAAcQA0AAAACgA4ACIAIgAiAHAQVAAA",
    "ABoBPgBuIFcAEABSMQoAbiBWABAAEwEpAG4gVQAQAG4QWQAAAAwAEgFxMB0ABAEMAG4QHgAAAA4AAAAEAAIAAgABAAAAAAAMAAAA",
    "biARADIAGgAAABEADQBuEFoAAAAMABEAAAAAAAMAAQABAAYADAACAAgAAAAAAAAAZAAAAHEADQAAAAsAhAZSpwwAEjQ0RwgAEwSI",
    "E7BkWaQCAA4A2AcHAVmnDAATBLAEsGRZpAIAUqQKANgEBAFZpAoAbhABAAsADARuEBwABAAMBG4QFgAEAAoG2gYGVdsGBmRuEBIA",
    "BAAKB9oHBy3bBwdkcQANAAAACwISBIJlgnYSB3cIDgAAAAwIEwkCEG4gEACYAFuoBAAWCNj/vIISFHcIDgAAAAwIEwkCEG4gEACY",
    "AFuoDQBUpAYAFggBAG5ACgCkmHAgJwC6AA4AAgABAAIAAAAAAAAACQAAACIAGgBwEB8AAABuIAIAAQAOAAAAAQAAAAAAAAAAAAAA",
    "AgAAABIQDwADAAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAABQACAAIAAgAAAAAAOwAAAFQwBgBuIAsAMAAS",
    "AlQwBABbMgQAVDENAFsyDQA4AAUAbhAPAAAAOAEFAG4QDwABAG4QAQAEAAwAbhAcAAAADABuEBQAAAAMAG4QGgAAAAoBOAEFAG4g",
    "GwAwAFQwAwAzQAcAEgBbMAMAXDALAA4ADQAo4A0AKPMAAA4AAAAKAAEAGAAAABUAAwACADcAOQAAAAYAAgACAAEAAAAAAD0AAABb",
    "RQMAEgBZQAwAWUAJAHEADQAAAAsAhADYAQD/WUECANABGPxZQQcAcRAjAAUACgBcQAsAOAAaAG4QAQAFAAwAbhAcAAAADABuEBQA",
    "AAAMAW4QGgABAAoCOAIIAG4gGwBBAG4gGQBBAA4ADQBSQQUA2AEBAVlBBQAo+AAAFAAAACAAAQABADUAAwADAAAAAAAAAAAAAQAA",
    "AA4AAAACAAIAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAABQABAAIAAQAAAAAAQwAAAFJABQASUTYQIgBVQAsA",
    "OAAeAFRAAwA4ABoAcQANAAAACwGEEVJCBwCRAgECEwN4ADQyDQBSQgIAkQIBAjoCBwBZQQcAcCAlAAQAEhAPAA0AUkEFANgBAQFZ",
    "QQUAVEEIABoCBwBuIFcAIQBuEFoAAAAMAG4gVwABABMACgBuIFUAAQASEA8AAAAAAAAAJwABAAEAJwAFAAEABAABAAAAAAA7AAAA",
    "VEAEAFRBDQBUQgMAOAAlABIDW0MEADgCFABVQwsAOAMQAG4gAAACAG4QDwAAAFRDBgAWACgAbkAKAEMQDgBuEA8AAAA4ARYAEgNb",
    "Qw0AbhAPAAEADgA4AQ0AEgNbQw0AOAIFAG4gAAASAG4QDwABAA4ADQAOAAAAAAAAADkAAQABADkABgABAAMAAQAAAAAAIQAAABoA",
    "WABxEEMAAAAMABoBpwASEiMiLQASAxwEBgBNBAIDbjBEABACDAESAhITIzMuABIETQUDBG4wWwAhAw4ADQAo/gAAAAAAAB4AAQAB",
    "AB8AAwACAAIAAAAAAAAACwAAAHAgBgAhACIAIgBwEFQAAABbEBQADgAAAAoABAACAAAAAAAAAE8AAAA5BwMADgBuEAcABgAMABIh",
    "IxErABICSwgBAhISSwkBAm4gCAAQAEQCAQKHIlJjEAA2MjYAUmQTANgEBAFZZBMAUmMSADQyLABxECAABwAMA1RkFABuEFgABAAK",
    "BRMALAE1BQoAbiBXADQAEwB8AG4gVQAEAHEQIgADAAoEOAQQAFJkEQDYBAQBWWQRAFRlDgA5BQYAW2MOAFliDwAOAAAABQAFAAAA",
    "AAAAAAAAAQAAAA4AAAAEAAQAAAAAAAAAAAABAAAADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAABQAFAAAAAAAAAAAAAQAAAA4AAAAJ",
    "AAcABAABAAAAAAAKAAAAcjBCAEMFDABwQDcAAnYOAA0AKP4AAAAABwABAAEACAAGAAUABAABAAAAAAAGAAAAcEA3ACFDDgANACj+",
    "AAAAAAMAAQABAAQACQAHAAQAAQAAAAAACgAAAG4wTwBDBQwAcEA3AAJ2DgANACj+AAAAAAcAAQABAAgACQAHAAQAAQAAAAAACgAA",
    "AHEwUwBDBQwAcEA3AAJ2DgANACj+AAAAAAcAAQABAAgADAAKAAQAAQAAAAAACgAAAHIwQgBDBQwAcEA3AAKYDgANACj+AAAAAAcA",
    "AQABAAgADAAKAAQAAQAAAAAACgAAAHEwUwBDBQwAcEA3AAKYDgANACj+AAAAAAcAAQABAAgADQ0JAQIBAgECAQIBAgESAQIBEgEC",
    "AQIBAgECAQIfgoAE3CwBCZgtAQLoLQEJ1C8BCvgwAQmoMQECwDEBCdwzAQKENQkK5DUCApg2AQnwNwEJlDgoAag4AQG8OAEB0DgB",
    "AfA5AQGIOwEBnDsBAbA7AQHEOwIB6DwAAAEANQn8PQAHAgoOAQEBAQEBAQEBAQEBETaBgATcPgEChD84AbRAAQHIQAEB3EABAfBA",
    "AQGEQQEBtEEBAdxBAQGMQgEBvEIBAexCAA0AAAAAAAAAAQAAAAAAAAABAAAAtQAAAHAAAAACAAAAMAAAAEQDAAADAAAAPwAAAAQE",
    "AAAEAAAAFwAAAPgGAAAFAAAAXQAAALAHAAAGAAAAAwAAAJgKAAACIAAAtQAAAPgKAAABEAAAKgAAAJwUAAADEAAAAQAAAFgWAAAB",
    "IAAAIwAAAFwWAAAAIAAAAwAAAJshAAAAEAAAAQAAAGAiAAA=",
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
        "ad, sends the same quick tap on the right side of the screen that a finger would, so the viewer goes to the next story. " +
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
