package app.revanced.patches.facebook.misc.probe

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.util.Base64
import java.util.logging.Logger

/*
 * Probe native feed (diagnostic)
 *
 * ONE self-contained file. It adds NO file to the apk (no asset, no resource, no manifest change, no library). It adds
 * three small classes (Entry, Core, Rec) to the dex the app already has, and one call at the top of the Application.onCreate()
 * that already exists. Nothing of Facebook's own code is read, renamed or changed, so it works on the obfuscated and on the
 * Dextricks builds alike (Android 8: 490 / 521 / 585, Android 11: the same builds).
 *
 * What it is for: the News Feed and the Stories of the app are NATIVE views, not web pages. To hide "Sponsored" posts and
 * story ads there, we first need to see what the screen is made of. This probe writes that down.
 *
 * What you see: a small dark button "RV probe: tap=save  hold=auto" at the right edge, middle of every screen.
 *   tap        one snapshot now
 *   long press AUTO mode on / off: every 4 s, when a "Sponsored" (or similar) label is drawn on the screen, it saves a
 *              snapshot by itself (at most one per 20 s and 12 per run)
 * A snapshot is a text report, saved as
 *     Android/data/com.facebook.katana/files/rv-probe-<time>.txt     (external files dir of the app)
 * and also copied to the clipboard (first 120000 characters), so it can be pasted into a message.
 * The report has two parts:
 *   [A] every string the screen draws, with its x,y on the window, in drawing order ('>>' marks the label lines)
 *   [B] the visible views: class, id (+ resource name), position and size, content description, TextView text, and the
 *       class of the adapter of every list ("RecyclerView" or similar)
 * The report contains what is on your screen at that moment (names, texts of posts). Read it before you send it; the text of
 * input fields (EditText) is not written in [B].
 *
 * How [A] is made: the window is drawn once into a throw-away bitmap through a Canvas subclass (Rec) that records drawText /
 * drawTextRun instead of drawing text. Nothing is drawn on the real screen and nothing is sent anywhere.
 *
 * Safety: every entry point is inside try/catch (Throwable). Entry starts Core through reflection, so even a verify error in
 * Core could not reach the app. If the probe fails, the app runs as if it were not patched.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * The smali of the three classes is in the zip next to this file (source folder); the Base64 below is exactly its assembly.
 */

private const val ENTRY = "Lapp/revanced/extension/facebook/probe/Entry;"
private const val CORE = "Lapp/revanced/extension/facebook/probe/Core;"
private const val APP_CLASS = "Lcom/facebook/katana/app/FacebookApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"

/** Words (lower case, joined by a vertical bar) that the probe marks with ">>": "Sponsored" in every language, "Suggested for you" ... */
private const val LABELS = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|ad|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|pub|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告|suggested for you|gợi ý cho bạn|paid partnership|hợp tác trả phí|people you may know|những người bạn có thể biết"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val PROBE_DEX = listOf(
    "ZGV4CjAzOADnKE3NPg/VftV/JfwO10cMImf5+/pRmeCQLgAAcAAAAHhWNBIAAAAAAAAAAPAtAAAAAQAAcAAAAD4AAABwBAAAXQAA",
    "AGgFAAAOAAAAxAkAAH0AAAA0CgAAAwAAABwOAAAUIAAAfA4AAHwOAAB+DgAA0w4AAEkPAABVDwAAXA8AAGYPAABvDwAAeQ8AAH0P",
    "AACJDwAAlQ8AAJwPAACqDwAAsw8AALsPAADEDwAAzg8AANsPAADfDwAA4w8AAO4PAAAlEAAAKhAAADAQAAA4EAAAPRAAAEAQAABD",
    "EAAARxAAAFIQAABYEAAAZBAAAGcQAABqEAAAbRAAAHAQAAB0EAAAeRAAAH0QAACCEAAAiBAAAIwQAACQEAAAlRAAAJsQAACgEAAA",
    "phAAAK0QAADFEAAA+xAAABYRAAAyEQAAVhEAAHERAACTEQAAuBEAANkRAAD7EQAAFhIAADESAABMEgAAZhIAAH8SAACZEgAAtRIA",
    "AMkSAADeEgAA9BIAAAkTAAAuEwAAVxMAAGwTAACTEwAArRMAAMQTAADeEwAA+RMAACQUAAA/FAAAVxQAAIUUAAC0FAAA4RQAAPEU",
    "AAANFQAAJxUAAD4VAABRFQAAZhUAAHoVAACQFQAApBUAAL8VAADTFQAA6hUAAAYWAAAaFgAAIRYAACoWAAAzFgAAORYAAEsWAABX",
    "FgAAdxYAAJYWAACoFgAAshYAALsWAADCFgAAxRYAAMkWAADNFgAA0xYAANoWAADeFgAA5BYAAOsWAAD0FgAAABcAAAUXAAALFwAA",
    "EhcAABgXAAAfFwAAJBcAACgXAAArFwAALxcAADMXAAA4FwAAPBcAAEAXAABEFwAASBcAAFwXAABxFwAAhhcAAIoXAACZFwAApBcA",
    "AKkXAACyFwAAuRcAAOUXAADtFwAA9RcAAPsXAAACGAAACxgAABMYAAAaGAAAJRgAACwYAAA0GAAAOhgAAEgYAABRGAAAZBgAAGoY",
    "AAB2GAAAgBgAAI0YAACVGAAAoxgAALQYAAC9GAAAzhgAANoYAADkGAAA8BgAAP8YAAAJGQAAIBkAAC4ZAABDGQAAUBkAAFsZAABi",
    "GQAAdxkAAIYZAACRGQAAnBkAAKUZAAC1GQAAyBkAANgZAADuGQAA/BkAAA4aAAAXGgAAJhoAADAaAAA7GgAARBoAAEkaAABSGgAA",
    "WhoAAGMaAAB0GgAAfBoAAIIaAACKGgAAkBoAAJoaAAClGgAAqBoAALYaAADJGgAA3hoAAPAaAAADGwAAIBsAADMbAABGGwAATxsA",
    "AFwbAABpGwAAchsAAJYbAACnGwAArxsAALgbAAC9GwAAxxsAANIbAADYGwAA3xsAAOMbAADyGwAABhwAABocAAAyHAAAPhwAAE4c",
    "AABWHAAAXxwAAG0cAAB6HAAAgBwAAIccAACOHAAAmhwAAKccAACyHAAAtxwAAMQcAADRHAAA2xwAAOIcAADoHAAA7hwAAPccAAAA",
    "HQAADR0AABQdAAAbAAAAIAAAACEAAAAiAAAAMAAAADEAAAAyAAAAMwAAADQAAAA1AAAANgAAADcAAAA4AAAAOQAAADoAAAA7AAAA",
    "PAAAAD0AAAA+AAAAPwAAAEAAAABBAAAAQgAAAEMAAABEAAAARQAAAEYAAABHAAAASAAAAEkAAABKAAAASwAAAEwAAABNAAAATgAA",
    "AE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUAAABWAAAAVwAAAFgAAABZAAAAWgAAAFsAAABcAAAAXQAAAF4AAABfAAAAYAAAAG0A",
    "AAB+AAAAggAAAIMAAACEAAAAhQAAAIYAAACHAAAAiAAAABwAAAAAAAAAzB4AACEAAAACAAAAAAAAACIAAAADAAAAAAAAAC0AAAAH",
    "AAAAzB0AACsAAAAKAAAALB0AACMAAAALAAAAAAAAACMAAAAMAAAAAAAAACgAAAAOAAAAEB8AACMAAAAQAAAAAAAAACMAAAAYAAAA",
    "AAAAACMAAAAbAAAAAAAAACYAAAAbAAAAzB4AACoAAAAbAAAAHB4AACMAAAAeAAAAAAAAAC4AAAAjAAAAGB0AACMAAAAnAAAAAAAA",
    "ACoAAAAnAAAAgB0AACMAAAApAAAAAAAAACcAAAApAAAAmB4AACMAAAArAAAAAAAAACoAAAArAAAAgB0AAC0AAAAtAAAAAB8AACoA",
    "AAAtAAAAgB0AACMAAAAvAAAAAAAAACUAAAAvAAAAvB0AACYAAAAvAAAAzB4AACcAAAAvAAAAmB4AAC8AAAAvAAAATB0AAC0AAAAv",
    "AAAAZB4AACoAAAAvAAAA3B0AACoAAAAvAAAAcB0AAC0AAAAvAAAAKB8AACoAAAAvAAAAVB8AACoAAAAvAAAAHB4AACoAAAAvAAAA",
    "gB0AACoAAAAvAAAAxB0AACwAAAAvAAAA9B4AACQAAAAwAAAAoB4AACYAAAAwAAAAzB4AACkAAAAwAAAA1B4AACoAAAAwAAAAVB8A",
    "ACoAAAAwAAAAgB0AAC0AAAAzAAAAtB4AAG0AAAA1AAAAAAAAAG4AAAA1AAAAvB4AAG8AAAA1AAAAzB4AAHAAAAA1AAAAWB0AAHEA",
    "AAA1AAAAHB8AAHIAAAA1AAAAtB0AAHcAAAA1AAAA7B4AAHcAAAA1AAAAZB4AAHwAAAA1AAAA5B0AAHIAAAA1AAAA1B0AAHIAAAA1",
    "AAAAeB0AAHIAAAA1AAAACB8AAHIAAAA1AAAA3B0AAHIAAAA1AAAARB8AAHQAAAA1AAAAmB0AAHoAAAA1AAAA7B0AAHsAAAA1AAAA",
    "qB4AAHsAAAA1AAAAbB4AAHIAAAA1AAAAgB4AAHIAAAA1AAAApB0AAHIAAAA1AAAAMB4AAHIAAAA1AAAAJB0AAHIAAAA1AAAAcB0A",
    "AHcAAAA1AAAAUB4AAHgAAAA1AAAAOB8AAHkAAAA1AAAAJB4AAHIAAAA1AAAACB4AAHcAAAA1AAAAMB8AAHIAAAA1AAAAVB8AAHMA",
    "AAA1AAAAWB4AAHUAAAA1AAAAiB4AAHYAAAA1AAAAOB4AAHIAAAA1AAAAHB4AAHIAAAA1AAAAeB4AAHQAAAA1AAAAEB4AAHUAAAA1",
    "AAAA+B0AAH0AAAA1AAAATB8AAHIAAAA1AAAAxB4AAHUAAAA1AAAAiB0AAHYAAAA1AAAANB0AAHIAAAA1AAAA3B4AAHIAAAA1AAAA",
    "5B4AAH4AAAA2AAAAAAAAAH8AAAA2AAAAoB4AAIAAAAA2AAAAcB0AAIAAAAA2AAAAHB4AAIEAAAA2AAAArB0AAIAAAAA2AAAAgB0A",
    "ACoAAAA3AAAAgB0AACoAAAA9AAAAgB0AAAoALwD9AAAADQANAGMAAAAUAC8AYgAAABQAAgBrAAAAFQAvAGEAAAAkADYAkgAAACQA",
    "BACcAAAAJAAXAMEAAAAkAAMAyAAAACQAAgDiAAAAJgACAMIAAAAmAAIAzQAAACYAMADjAAAANAA0AGQAAAAEAAsAowAAAAQADQDA",
    "AAAABgA0ANoAAAAHAAMAzgAAAAgANgDpAAAACQAQAK4AAAAJAA8ArwAAAAkABQC4AAAACQAXALkAAAAJABYAvAAAAAsABAC3AAAA",
    "DAAZALoAAAAOAAcAmwAAAA4AKwDZAAAADwA4ABgAAAAPAAgAtAAAABAAUwDMAAAAFwA+ABgAAAAXAFkA2AAAABcATADbAAAAGAAJ",
    "ALMAAAAbAD0AngAAABsADACkAAAAGwARAKwAAAAbAAEAsAAAABsAAQCxAAAAGwBUALIAAAAbAAYAuwAAABsAAQC+AAAAGwABAL8A",
    "AAAbAC0A5QAAABsAPwDmAAAAGwBAAOcAAAAbAC8A6AAAABsASwDqAAAAHQBCAI0AAAAdAAsAqQAAAB0AAQCqAAAAHgAKAK0AAAAh",
    "AC4AGAAAACIANwAYAAAAIgARAL0AAAAiAEcA6wAAACIALQDsAAAAIgAsAO0AAAAjAA4AywAAACMAKwDuAAAAJAArABgAAAAkAB4A",
    "iwAAACQAVQCOAAAAJAAwAJEAAAAkABsAkwAAACQAMwCUAAAAJAAgAJYAAAAkADIAmgAAACQAWgDFAAAAJAAXAMcAAAAkAEMAygAA",
    "ACQAMQDPAAAAJAAwANAAAAAkADAA0QAAACQAMADSAAAAJAAxANMAAAAkADAA1AAAACQAMADVAAAAJABBANYAAAAkAFcA1wAAACQA",
    "HwDcAAAAJAArAN4AAAAkABwA4QAAACQANQDwAAAAJAAyAPgAAAAkAEQA+QAAACQAHQD8AAAAJQA1AMMAAAAmADgAGAAAACYASACM",
    "AAAAJgA5AJ8AAAAmADoAnwAAACYAOwCfAAAAJgA8AJ8AAAAmAEkAoAAAACYATQCgAAAAJgBOAKAAAAAmAFEAoAAAACYASgChAAAA",
    "JgBSAKEAAAAnAEYAGAAAACcAFwCmAAAAKABFABgAAAAoACsAmAAAACgAUAD+AAAAKQABAMkAAAApABIA8gAAACoAVgDGAAAAKwAU",
    "AKUAAAArACoAtQAAACsAFwC2AAAALAAZAPUAAAAtACsAGAAAAC0AEwCrAAAALwAAAJUAAAAvACIAmQAAAC8AWACiAAAALwBbAKgA",
    "AAAvAAEAyQAAAC8AGADdAAAALwBcAO8AAAAvAFoA8QAAAC8AGgDzAAAALwAjAPYAAAAvABcA+gAAAC8AIQD7AAAALwAkAPsAAAAw",
    "ACsAGAAAADAAJQCQAAAAMAAmAJAAAAAwACcAkAAAADAAKACQAAAAMAApAJAAAAAwABcA9wAAADEAAgCdAAAAMgAXAPcAAAAzABUA",
    "xAAAADMATwDkAAAAJAAAABEAAAAtAAAAZB0AAB0AAAAAAAAALy0AAAAAAAAlAAAAEQAAAC0AAAAAAAAAHwAAAAAAAACrLQAAAAAA",
    "ACYAAAARAAAADwAAAAAAAABqAAAAAAAAALMtAAAAAAAAAABTCgpbQV0gRFJBV04gVEVYVCAgIHgseSB8IHRleHQgICAgICAoPj4g",
    "PSBsb29rcyBsaWtlIGEgU3BvbnNvcmVkIC8gc3VnZ2VzdGVkIGxhYmVsKQoAdApbQl0gVklFVyBUUkVFICAgKGluZGVudCA9IGRl",
    "cHRoKSAgIGNsYXNzICNpZChuYW1lKSBAeCx5IHd4aCAgY2Q9PGNvbnRlbnREZXNjcmlwdGlvbj4gIHRleHQ9PHRleHQ+ICBhZGFw",
    "dGVyPTxjbGFzcz4KAAoKYWN0aXZpdHk9AAUKYXBwPQAICmRldmljZT0ABwp0ZXh0cz0ACAp3aW5kb3c9AAIgIAAKICBhZGFwdGVy",
    "PQAKICBhbmRyb2lkPQAFICBjZD0ADCAgbGFiZWxIaXRzPQAHICBtb2RlPQAGICBzZGs9AAcgIHRleHQ9AAggIHZpZXdzPQALICB3",
    "ZWJ2aWV3cz0AAiAjAAIgQAAJIGRyYXdFcnI9ADUgbGFiZWwocykgb24gc2NyZWVuLCByZXBvcnQgY29waWVkIHRvIHRoZSBjbGlw",
    "Ym9hcmQuCgADIHwgAAQudHh0AAY8aW5pdD4AAz4+IAABPwABQwACQ0kACUNvcmUuamF2YQAERVJSIAAKRW50cnkuamF2YQABRgAB",
    "SQABSgABTAACTEMAA0xDQwACTEkAA0xJSQAETElJTAACTEoAAkxMAANMTEkABExMSUkAA0xMTAAETExMSQAFTExMTEwAFkxhbmRy",
    "b2lkL2FwcC9BY3Rpdml0eTsANExhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbiRBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrczsAGUxh",
    "bmRyb2lkL2FwcC9BcHBsaWNhdGlvbjsAGkxhbmRyb2lkL2NvbnRlbnQvQ2xpcERhdGE7ACJMYW5kcm9pZC9jb250ZW50L0NsaXBi",
    "b2FyZE1hbmFnZXI7ABlMYW5kcm9pZC9jb250ZW50L0NvbnRleHQ7ACBMYW5kcm9pZC9jb250ZW50L3BtL1BhY2thZ2VJbmZvOwAj",
    "TGFuZHJvaWQvY29udGVudC9wbS9QYWNrYWdlTWFuYWdlcjsAH0xhbmRyb2lkL2NvbnRlbnQvcmVzL1Jlc291cmNlczsAIExhbmRy",
    "b2lkL2dyYXBoaWNzL0JpdG1hcCRDb25maWc7ABlMYW5kcm9pZC9ncmFwaGljcy9CaXRtYXA7ABlMYW5kcm9pZC9ncmFwaGljcy9D",
    "YW52YXM7ABlMYW5kcm9pZC9ncmFwaGljcy9NYXRyaXg7ABhMYW5kcm9pZC9ncmFwaGljcy9QYWludDsAF0xhbmRyb2lkL2dyYXBo",
    "aWNzL1JlY3Q7ABhMYW5kcm9pZC9ncmFwaGljcy9SZWN0RjsAGkxhbmRyb2lkL29zL0J1aWxkJFZFUlNJT047ABJMYW5kcm9pZC9v",
    "cy9CdWlsZDsAE0xhbmRyb2lkL29zL0J1bmRsZTsAFExhbmRyb2lkL29zL0hhbmRsZXI7ABNMYW5kcm9pZC9vcy9Mb29wZXI7ACNM",
    "YW5kcm9pZC92aWV3L1ZpZXckT25DbGlja0xpc3RlbmVyOwAnTGFuZHJvaWQvdmlldy9WaWV3JE9uTG9uZ0NsaWNrTGlzdGVuZXI7",
    "ABNMYW5kcm9pZC92aWV3L1ZpZXc7ACVMYW5kcm9pZC92aWV3L1ZpZXdHcm91cCRMYXlvdXRQYXJhbXM7ABhMYW5kcm9pZC92aWV3",
    "L1ZpZXdHcm91cDsAFUxhbmRyb2lkL3ZpZXcvV2luZG93OwAYTGFuZHJvaWQvd2Via2l0L1dlYlZpZXc7ABlMYW5kcm9pZC93aWRn",
    "ZXQvRWRpdFRleHQ7AClMYW5kcm9pZC93aWRnZXQvRnJhbWVMYXlvdXQkTGF5b3V0UGFyYW1zOwAZTGFuZHJvaWQvd2lkZ2V0L1Rl",
    "eHRWaWV3OwAWTGFuZHJvaWQvd2lkZ2V0L1RvYXN0OwAsTGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svcHJvYmUvQ29y",
    "ZTsALUxhcHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL3Byb2JlL0VudHJ5OwArTGFwcC9yZXZhbmNlZC9leHRlbnNpb24v",
    "ZmFjZWJvb2svcHJvYmUvUmVjOwAOTGphdmEvaW8vRmlsZTsAGkxqYXZhL2lvL0ZpbGVPdXRwdXRTdHJlYW07ABhMamF2YS9sYW5n",
    "L0NoYXJTZXF1ZW5jZTsAFUxqYXZhL2xhbmcvQ2hhcmFjdGVyOwARTGphdmEvbGFuZy9DbGFzczsAE0xqYXZhL2xhbmcvSW50ZWdl",
    "cjsAEkxqYXZhL2xhbmcvT2JqZWN0OwAUTGphdmEvbGFuZy9SdW5uYWJsZTsAEkxqYXZhL2xhbmcvU3RyaW5nOwAZTGphdmEvbGFu",
    "Zy9TdHJpbmdCdWlsZGVyOwASTGphdmEvbGFuZy9TeXN0ZW07ABVMamF2YS9sYW5nL1Rocm93YWJsZTsAGkxqYXZhL2xhbmcvcmVm",
    "bGVjdC9NZXRob2Q7ABJMamF2YS91dGlsL0xvY2FsZTsABU1PREVMAAdSRUxFQVNFAAdSR0JfNTY1AARST09UABBSViBwcm9iZSBl",
    "cnJvcjogAApSViBwcm9iZTogAB5SViBwcm9iZTogQVVUTyBPTiAoaG9sZCA9IG9mZikAHVJWIHByb2JlOiB0YXA9c2F2ZSAgaG9s",
    "ZD1hdXRvABBSVi1QUk9CRSAxCnRpbWU9AAhSZWMuamF2YQAHU0RLX0lOVAAFVVRGLTgAAVYAAlZGAAJWSQAEVklJSQAFVklJSUkA",
    "AlZMAARWTEZGAAVWTEZGTAAHVkxJSUZGTAAKVkxJSUlJRkZaTAADVkxMAARWTExJAAVWTExJTAAEVkxMTAAFVkxMTEwAA1ZMWgAC",
    "VloAAVoAAlpDAAJaTAADWkxKAAJbQgACW0MAAltGAAJbSQASW0xqYXZhL2xhbmcvQ2xhc3M7ABNbTGphdmEvbGFuZy9PYmplY3Q7",
    "ABNbTGphdmEvbGFuZy9TdHJpbmc7AAJcfAANX19SVl9MQUJFTFNfXwAJYWRhcHRlck9mAANhZGQAB2FkZFZpZXcABWFsbG93ACph",
    "cHAucmV2YW5jZWQuZXh0ZW5zaW9uLmZhY2Vib29rLnByb2JlLkNvcmUABmFwcGVuZAAGYXR0YWNoAARhdXRvAAVidWlsZAAHY2Fw",
    "dHVyZQAGY2hhckF0AAVjbGVhbgAJY2xpcGJvYXJkAAVjbG9zZQAGY29uY2F0AARjb3B5AAxjcmVhdGVCaXRtYXAAB2N1cnJlbnQA",
    "EWN1cnJlbnRUaW1lTWlsbGlzAARkcmF3AApkcmF3Qml0bWFwAAhkcmF3VGV4dAALZHJhd1RleHRSdW4ABmVxdWFscwAMZmluZFZp",
    "ZXdCeUlkAA9maW5kVmlld1dpdGhUYWcAB2Zvck5hbWUAD2dldEFic29sdXRlUGF0aAAKZ2V0QWRhcHRlcgAIZ2V0Qnl0ZXMACmdl",
    "dENoaWxkQXQADWdldENoaWxkQ291bnQACGdldENsYXNzABVnZXRDb250ZW50RGVzY3JpcHRpb24ADGdldERlY29yVmlldwATZ2V0",
    "RXh0ZXJuYWxGaWxlc0RpcgALZ2V0RmlsZXNEaXIACWdldEhlaWdodAAFZ2V0SWQAE2dldExvY2F0aW9uT25TY3JlZW4ADWdldE1h",
    "aW5Mb29wZXIACWdldE1hdHJpeAAJZ2V0TWV0aG9kAAdnZXROYW1lAA5nZXRQYWNrYWdlSW5mbwARZ2V0UGFja2FnZU1hbmFnZXIA",
    "DmdldFBhY2thZ2VOYW1lABRnZXRSZXNvdXJjZUVudHJ5TmFtZQAMZ2V0UmVzb3VyY2VzABBnZXRTeXN0ZW1TZXJ2aWNlAAdnZXRU",
    "ZXh0AA1nZXRWaXNpYmlsaXR5AAhnZXRXaWR0aAAJZ2V0V2luZG93AAdoYW5kbGVyAANoaXQAB2luc3RhbGwABmludm9rZQAHaXNM",
    "YWJlbAAPaXNMZXR0ZXJPckRpZ2l0AAZsYWJlbHMABGxhc3QABmxlbmd0aAAEbGluZQAIbWFrZVRleHQACW1hcFBvaW50cwABbgAM",
    "bmV3UGxhaW5UZXh0ABFvbkFjdGl2aXR5Q3JlYXRlZAATb25BY3Rpdml0eURlc3Ryb3llZAAQb25BY3Rpdml0eVBhdXNlZAARb25B",
    "Y3Rpdml0eVJlc3VtZWQAG29uQWN0aXZpdHlTYXZlSW5zdGFuY2VTdGF0ZQARb25BY3Rpdml0eVN0YXJ0ZWQAEW9uQWN0aXZpdHlT",
    "dG9wcGVkAAdvbkNsaWNrAAtvbkxvbmdDbGljawALcG9zdERlbGF5ZWQAB3JlY3ljbGUAInJlZ2lzdGVyQWN0aXZpdHlMaWZlY3lj",
    "bGVDYWxsYmFja3MAD3JlbW92ZUNhbGxiYWNrcwAGcmVuZGVyAAdyZXBsYWNlAANydW4ACHJ2LXByb2JlAAlydi1wcm9iZS0ABHNh",
    "dmUABXNhdmVkAAJzYgANc2V0QWNjZXNzaWJsZQASc2V0QmFja2dyb3VuZENvbG9yABJzZXRPbkNsaWNrTGlzdGVuZXIAFnNldE9u",
    "TG9uZ0NsaWNrTGlzdGVuZXIACnNldFBhZGRpbmcADnNldFByaW1hcnlDbGlwAAZzZXRUYWcAB3NldFRleHQADHNldFRleHRDb2xv",
    "cgALc2V0VGV4dFNpemUABHNob3cABXNwbGl0AAVzdGFydAAKc3RhcnRzV2l0aAALc3ViU2VxdWVuY2UACXN1YnN0cmluZwADdGFw",
    "AAt0b0hleFN0cmluZwALdG9Mb3dlckNhc2UACHRvU3RyaW5nAAV0b2FzdAAEdHJlZQAEdHJpbQAHdmFsdWVPZgAHdmVyc2lvbgAL",
    "dmVyc2lvbk5hbWUABXdyaXRlAAF4AAADAAAACQApAAIAAAABAAAAGgAAAAIAAAAvAAIACQAAADgAAgACAAIAAgABAAEANgARAAAA",
    "BAAAAAQAGwAmAC8AAwAAAAIAAgACAAAABAAAAAUALgAZABoAAQAAABsAAAABAAAABgAAAAEAAAAvAAAABgAAADgAAgACAAEAAQAR",
    "AAQAAAAOAAEAAQARAAEAAAAYAAAAAgAAAC4AAwABAAAABAAAAAIAAAAAAAAAAQAAADQAAAACAAAAKQApAAEAAAAFAAAAAQAAAAkA",
    "AAACAAAABAA2AAMAAAAOABAAEQAAAAYAAAAvAAIAAgABAAEAEQABAAAAJwAAAAQAAAAvAAEAAQARAAEAAAAtAAAABAAAABsAMAAC",
    "ADoAAQAAABkAAAAJAAAAKQACAAIAAgACAAEAAQA2ABEAAAACAAAAGwAcAAMAAAApAAEAAQAAAAIAAAAEAC8ABAAAAA4AEgATABEA",
    "AQAAAC4AAAABAAAADwAAAAYAAAApAAIAAgABAAEAEQACAAAAAgACAAEAAAAAAAAABAAAAA4AEgASABEAAgAAAC8AOwABAAAAAQAA",
    "AAEAAAA3AAAAAQAAAAIAAAABAAAAAwAAAAEAAAA5AAAAAQAAADoAAAACAAAABAAWAAMAAAA4AAIAAgAAAAIAAAAtADwAAQAAAAcA",
    "AAADAAAAAgACAA0AAAAEAAAAAgACAAIAAgACAAAAGwAmAAIAAAAnAC8AAwAAABsAMAACAAAAAQAAAA4AAAABAAAANgAAAAEAAAAp",
    "AAAAAAAAAAMAAQACAAAAAAAAAA8AAABwEGMAAgAiABcAcQAUAAAADAFwIBEAEABbIAcADgAAAAQAAQADAAEAAAAAACoAAABuEGQA",
    "AwAMABoBpwASAiMiOwBuMGAAEAIMABIRbiB8ABAAEgEjETwAbjB7ADABDAA5AAUAGgAAABEAbhBkAAAADABuEGEAAAAMABEADQAa",
    "AAAAEQAAAAAAJQABAAEAJgAHAAEAAAAAAAAAAAAXAAAAcQB5AAAACwBTYggAvCAWAiBOMQAAAjoACgBSYAkAEwEMADUQBAASEA8A",
    "EgAPAAAACAACAAUAAAAAAAAASgAAABQAAgACAW4gAAAHAAwAIAEdADgBQAAaAd8AbiAWABAADAI5AjgAHwAdACICIgBwICgAcgBu",
    "ICIAEgAaA2gAbiAqADIAFQMwQW4gLAAyABLzbiArADIAFAMAAADMbiAeADIAEwMMABJkblQhADI0biAfAGIAbiAgAGIAIgMhABLk",
    "FAUVAIAAcEAnAENUbjAjACADDgAMAAQABAAAAAAAAADEAAAAIgAwAHAQcgAAABIhIxE6ABICcUBIAAkSIgMwAHAQcgADABoGaQBu",
    "IHcAYwBxAHkAAAALBG4wdQBDBRoGBQBuIHcAYwBiBgQAbiB3AGMAGgYKAG4gdwBjAGIGAgBuIHcAYwAaBg4AbiB3AGMAYAYDAG4g",
    "dABjABoGBABuIHcAYwBuEAgACAAMBm4gdwBjABoGCABuIHcAYwBxEEkACAAMBm4gdwBjABoGAwBuIHcAYwBuEGQACAAMBm4QYQAG",
    "AAwGbiB3AGMAGgYHAG4gdwBjAG4QHQAJAAoGbiB0AGMAGgb/AG4gdwBjAG4QGAAJAAoGbiB0AGMAGgYNAG4gdwBjAG4gdwCzABoG",
    "BgBuIHcAYwBSpgsAbiB0AGMAGgYMAG4gdwBjAFKmCgBuIHQAYwAaBhAAbiB3AGMAEgdEBgEHbiB0AGMAGgYRAG4gdwBjABIXRAYB",
    "B24gdABjABoGAQBuIHcAYwBUpgwAbiB2AGMAGgYCAG4gdwBjAG4gdgADAG4QeAADAAwAEQAMAAMABAABAAAAAACQAAAAbhABAAoA",
    "DABuECYAAAAMAG4QHQAAAAoBbhAYAAAACgI9AW4APQJsAGIDAQBxMAwAIQMMAyIEJgBwIEsANABxIEMAQAAMBW4QDQADADkLDABS",
    "RgoAOAZUAHAQMQAJAAoGOAZOADgLBQAaBvQAKAMaBpIAbhBpAAUACgc4BwwAGgcUAG4gZgB2AAwGbiBmAFYADAZxQDMACmQMBnEg",
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
val probeNativeFeedPatch = bytecodePatch(
    name = "Probe native feed (diagnostic)",
    description = "ANDROID 8 and ANDROID 11 (all builds). Diagnostic only, hides nothing: adds a small button to every screen; " +
        "tap it while a Sponsored post or story ad is on screen and the app writes down every text it draws and the " +
        "views it is made of (file in Android/data/com.facebook.katana/files + clipboard). Send that report to get a " +
        "native hider written. Adds no file to the apk, no resource, no manifest change.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    apply {
        val log = Logger.getLogger("ProbeNativeFeed")

        if (classDefs[ENTRY] != null) {
            log.info("Probe native feed: Entry is already in the dex, nothing to add")
            return@apply
        }

        // ---- 1. Find the hook (read only) ------------------------------------------------------------------------
        val hookClass = findHookClass()
            ?: throw PatchException(
                "Probe native feed: no Application.onCreate() found ($APP_CLASS or a class extending " +
                    "$ANDROID_APPLICATION). Send the smali of the app's Application class.",
            )

        // ---- 2. Add the three classes into the existing dex ------------------------------------------------------
        val added = try {
            DexBackedDexFile(null, Base64.getDecoder().decode(PROBE_DEX), 0).classes.toList()
        } catch (e: Exception) {
            throw PatchException("Probe native feed: cannot read the embedded classes: $e")
        }
        if (added.size != 3 || added.none { it.type == ENTRY } || added.none { it.type == CORE }) {
            throw PatchException("Probe native feed: the embedded dex does not hold Entry, Core and Rec (${added.map { it.type }})")
        }
        @Suppress("UNCHECKED_CAST")
        val mutableClassDefs = classDefs as MutableSet<ClassDef>
        for (def in added) {
            if (!mutableClassDefs.add(def)) {
                throw PatchException("Probe native feed: could not add ${def.type} to the dex")
            }
        }

        // ---- 3. Put the label list into Core.labels() ------------------------------------------------------------
        val core = added.first { it.type == CORE }
        val labelsMethod = classDefs.getOrReplaceMutable(core).methods
            .firstOrNull { it.name == "labels" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: throw PatchException("Probe native feed: Core.labels() not found")
        labelsMethod.replaceInstruction(0, "const-string v0, \"${smaliString(LABELS)}\"")

        // ---- 4. One call at the very top of the existing Application.onCreate() ----------------------------------
        val onCreate = classDefs.getOrReplaceMutable(hookClass).methods.first {
            it.name == "onCreate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty() &&
                it.implementation != null &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        onCreate.addInstructions(0, "invoke-static {p0}, $ENTRY->install(Landroid/app/Application;)V")

        log.info("Probe native feed: hooked ${hookClass.type}->onCreate(), ${added.size} classes added")
    }
}
