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
 *      buttons: a content description "Hide ad" / "Ẩn quảng cáo" (= an ad), or "Hide post" / "Ẩn bài viết" and no video surface
 *      (the same two texts in about 40 other languages, see BUTTONS_AD / BUTTONS_POST: only English and Vietnamese are from real screens).
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
 *      or sounding under the invisible row. Version 5 repeats the telling in EVERY frame in which the row is still hidden (the decompiled
 *      Litho clears the visibility items of the row once and works them out again from the row's rectangle the next time the list moves;
 *      the collapsed row's rectangle still overlaps the screen: that was the sound of a hidden ad video that kept playing while you stood
 *      still on the next post), and, after the last frame in which something was hidden, twice more 120 ms and 240 ms later (two extra
 *      frames that this asks for, then nothing: a still screen costs nothing). When RecyclerView recycles a hidden row Litho is told
 *      "not visible" once more and is NOT told "visible" (a view that has left the window counts as visible for Litho and its video would
 *      wake up in RecyclerView's cache); the "told" mark stays on the view and is lifted, "visible" is told, as soon as the view shows
 *      something that is not hidden by this code and is not waiting to be judged. It is told "visible" again when a row is shown. If the
 *      method is not found, or is found twice, this step is skipped for good (nothing else changes). An error in ONE call only loses
 *      that call: the next frame tries again (version 2 gave up for good at the first error, and from then on hidden ads could sound again).
 *      The log (tap on the badge) says "HINT method ...", "HINT none" or "HINT exc ..." (the first 10 errors).
 *   8. (option "Hide again when scrolling back up", OFF by default, experimental) when a row is judged an ad, the ITEM is
 *      remembered (up to 32 items): the identity of the Litho ComponentTree that the
 *      LithoView shows (the field that holds it has an obfuscated name but a fixed type, com.facebook.litho.ComponentTree, so it is
 *      found by its type; Litho's RecyclerBinder keeps one tree per item) together with the height of the row. When the user scrolls
 *      back UP and RecyclerView binds that item to a view again, the row is hidden at once, in the frame in which it comes into the
 *      visible part of the list, although its header is still above the list. Such a row is only made invisible and collapsed;
 *      Litho is NOT told "not visible" at once but 48 ms later, if the row is still bound then, or as soon as its header has been
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
 *   - Other languages: the texts of the two header buttons and the Sponsored / Suggested / paid partnership labels of the languages
 *     other than English and Vietnamese are the usual translations, not checked on a screen of that language. A wrong or missing word
 *     means that nothing is hidden in that language (the patch then finds no feed post); it can never make it hide something else.
 *   - Litho can also be told by a LithoVisibilityEventsController instead (a tree that has one ignores the hint): such a row is not quiet.
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
private const val LABELS_SPONSORED = "sponsored|مُموَّل|спонсорирано|sponzorováno|gesponsert|anzeige|χορηγούμενη|publicidad|sponsoroitu|sponsorisé|ממומן|bersponsor|sponsorizzato|広告|apmaksāta reklāma|gesponsord|sponsorowane|patrocinado|реклама|sponsorlu|спонсорована|được tài trợ|赞助内容|贊助|إعلان|reklama|werbung|διαφήμιση|anuncio|mainos|publicité|מודעה|iklan|inserzione|pubblicità|reklāma|advertentie|anúncio|reklam|quảng cáo|广告|廣告|스폰서|광고|ได้รับการสนับสนุน|โฆษณา|प्रायोजित|विज्ञापन|szponzorált|hirdetés|sponsorizat|reclamă|sponsrad|annons|sponsoreret|annonce|sponset|annonse|sponzorované|sponzorirano|sponzorisano|oglas|স্পনসর্ড|স্পনসরড|বিজ্ঞাপন|ditaja|patrocinat|sponsitud|remiama|حمایتشده|حمایت شده|اسپانسرڈ|imedhaminiwa|geborg|スポンサー|спонзорисано|χορηγούμενο|gesponsorde inhoud|sponsored content|sponsrat innehåll|sponsoroitu sisältö|contenu sponsorisé|contenido patrocinado|conteúdo patrocinado|gesponserter beitrag|contenuto sponsorizzato"

/** "Suggested for you" in many languages. Same rules. Added when the option "Hide suggested and partnership posts" is on. */
private const val LABELS_SUGGESTED = "suggested for you|recommended for you|مقترح لك|مقترحات لك|предложения за вас|navrhované pro vás|vorschläge für dich|für dich empfohlen|προτεινόμενα για εσάς|sugerencias para ti|sugerido para ti|ehdotuksia sinulle|suggestion pour vous|suggestions pour vous|הצעות עבורך|disarankan untuk anda|contenuti suggeriti per te|suggerimenti per te|おすすめ|ieteikumi jums|voorgesteld voor jou|proponowane dla ciebie|sugestões para ti|sugestões para você|рекомендуем вам|предлагаемое для вас|senin için önerilenler|рекомендовано для вас|gợi ý cho bạn|đề xuất cho bạn|为你推荐|推荐给你|為你推薦|推薦給你|회원님을 위한 추천|회원님을 위한 추천 게시물|แนะนำสำหรับคุณ|आपके लिए सुझाए गए|आपके लिए सुझाव|neked javasolt|sugerat pentru tine|föreslaget för dig|foreslået til dig|foreslått for deg|navrhované pre vás|predloženo za vas|predlagano zate|disyorkan untuk anda|iminumungkahi para sa iyo"

/** "Paid partnership ..." labels: a star at the end means that the drawn text only has to START with it. Added when the option
 *  "Hide suggested and partnership posts" is on. */
private const val LABELS_PARTNERSHIP = "paid partnership*|mối quan hệ tài trợ*|mối quan hệ đối tác trả phí*|quan hệ đối tác trả phí*|hợp tác trả phí*|colaboración pagada*|colaboración de pago*|partenariat rémunéré*|bezahlte partnerschaft*|parceria paga*|parceria paga com*|partnership a pagamento*|collaborazione a pagamento*|kemitraan berbayar*|płatne partnerstwo*|betaald partnerschap*|платное партнерство*|платное партнёрство*|ücretli iş birliği*|ücretli ortaklık*|betald partnerskap*|betalt partnerskab*|betalt samarbeid*|maksettu yhteistyö*"

/** Content descriptions of the "hide this ad" button of a post header ("Hide ad", "Ẩn quảng cáo" ...) in many languages: the proof that a
 *  row is an ad of the feed. Lower case, composed, whole string, joined by a vertical bar. Only English and Vietnamese are from real
 *  screens; the other languages are the usual translations, unverified (a wrong word means: nothing is hidden in that language). */
private const val BUTTONS_AD = "hide ad|ẩn quảng cáo|hide this ad|ocultar anuncio|ocultar anúncio|ocultar publicidad|masquer la publicité|masquer la pub|masquer l'annonce|masquer cette publicité|anzeige ausblenden|werbeanzeige ausblenden|werbung ausblenden|nascondi inserzione|nascondi annuncio|nascondi pubblicità|sembunyikan iklan|reklamı gizle|скрыть рекламу|скрыть объявление|сховати рекламу|ukryj reklamę|advertentie verbergen|dölj annons|skjul annonce|skjul annonse|piilota mainos|skrýt reklamu|skryť reklamu|hirdetés elrejtése|ascunde reclama|απόκρυψη διαφήμισης|скриване на рекламата|sakrij oglas|sakrij reklamu|הסתר מודעה|إخفاء الإعلان|إخفاء الاعلان|ซ่อนโฆษณา|広告を非表示|広告を非表示にする|광고 숨기기|隐藏广告|屏蔽广告|隱藏廣告|隱藏此廣告|विज्ञापन छिपाएं|विज्ञापन छुपाएं|বিজ্ঞাপন লুকান|itago ang ad|sembunyikan iklan ini|amaga l'anunci|peida reklaam|slėpti skelbimą|paslēpt reklāmu"

/** Same for the "hide this post" button ("Hide post", "Ẩn bài viết" ...). */
private const val BUTTONS_POST = "hide post|ẩn bài viết|hide this post|ocultar publicación|ocultar publicação|masquer la publication|beitrag ausblenden|nascondi post|nascondi il post|sembunyikan postingan|sembunyikan kiriman|gönderiyi gizle|скрыть публикацию|сховати публікацію|ukryj post|ukryj wpis|bericht verbergen|dölj inlägg|skjul opslag|skjul innlegg|piilota julkaisu|skrýt příspěvek|skryť príspevok|bejegyzés elrejtése|ascunde postarea|απόκρυψη δημοσίευσης|скриване на публикацията|sakrij objavu|הסתר פוסט|הסתר פרסום|إخفاء المنشور|ซ่อนโพสต์|投稿を非表示|投稿を非表示にする|게시물 숨기기|隐藏帖子|隐藏动态|隱藏貼文|隱藏帖子|पोस्ट छिपाएं|पोस्ट छुपाएं|পোস্ট লুকান|itago ang post|amaga la publicació|peida postitus|slėpti įrašą|paslēpt ierakstu"

/** Assembled Entry, Core and Rec classes (one dex file), Base64. */
private val ADHIDE_DEX = listOf(
    "ZGV4CjAzOACrCIWxc8oswRUZN1ZET5p83Fs75uziFAOMSwAAcAAAAHhWNBIAAAAAAAAAAOxKAAB0AQAAcAAAAEUAAABABgAAbQAA",
    "AFQHAAA2AAAAcAwAALcAAAAgDgAAAwAAANgTAABUNwAAOBQAADgUAAA6FAAAPhQAAEoUAABRFAAAWxQAAGUUAABpFAAAehQAAJcU",
    "AACjFAAAsBQAALoUAADKFAAA3hQAAOYUAADxFAAA+RQAAAUVAAAKFQAAExUAABkVAABHFQAAThUAAFUVAABbFQAAYxUAAGYVAABv",
    "FQAAiBUAAIsVAACPFQAAmhUAAKAVAACsFQAArxUAALYVAADeFQAA5BUAAP0VAAAIFgAAFhYAAC4WAAAxFgAANRYAADoWAAA+FgAA",
    "QxYAAEYWAABJFgAATRYAAFIWAABWFgAAWxYAAGEWAABoFgAAbBYAAHAWAAB1FgAAexYAAIAWAACGFgAAihYAAKIWAADYFgAA8xYA",
    "AA8XAAAzFwAAThcAAHAXAACVFwAAtxcAANIXAADtFwAACBgAACIYAAA7GAAAVRgAAHEYAACFGAAAmhgAALAYAADFGAAA3xgAAPsY",
    "AAAXGQAASBkAAG0ZAACCGQAAqRkAANYZAADwGQAACxoAAD4aAABfGgAAdhoAAKEaAAC8GgAA1BoAAAMbAAAzGwAAYRsAAHYbAACQ",
    "GwAAoxsAALUbAADJGwAA3xsAAPMbAAAOHAAAIhwAADkcAABLHAAAZhwAAIIcAACfHAAAtxwAAMscAADSHAAA1xwAANscAADmHAAA",
    "7xwAAPgcAAAhHQAAJx0AADMdAABFHQAATx0AAFgdAABjHQAAcR0AAHcdAAB6HQAAfh0AAIIdAACHHQAAjR0AAJQdAACZHQAAnR0A",
    "AKMdAACqHQAArx0AALgdAADEHQAAyR0AAM8dAADWHQAA2x0AAN8dAADiHQAA5x0AAOsdAADwHQAA9R0AAPkdAAD9HQAAAR4AABUe",
    "AAAqHgAAPx4AAFseAAB4HgAAih4AAI4eAACcHgAAqx4AALseAADAHgAAxx4AANIeAADXHgAA9x4AAA0fAAAWHwAAQx8AAEsfAABT",
    "HwAAWh8AAGMfAABuHwAAdB8AAHkfAACHHwAAjR8AAJYfAACeHwAApR8AALAfAAC6HwAAzx8AAPEfAAAPIAAAFSAAACMgAAAsIAAA",
    "PyAAAEcgAABOIAAAVCAAAGAgAABqIAAAdyAAAH8gAACGIAAAlCAAAKUgAACsIAAAtSAAAL0gAADBIAAAxiAAANEgAADdIAAA7CAA",
    "APYgAAANIQAAICEAADQhAABHIQAAVSEAAGAhAABnIQAAeCEAAIEhAACWIQAApSEAALAhAADDIQAAziEAANwhAADlIQAA9SEAAAgi",
    "AAAYIgAAKyIAADYiAABFIgAATyIAAF4iAABwIgAAeCIAAIAiAACJIgAAniIAAK0iAAC3IgAAwiIAAMgiAADRIgAA2yIAAN8iAADn",
    "IgAA7SIAAPMiAAD8IgAABiMAAA8jAAAWIwAAGyMAAC0jAAA1IwAAPiMAAEcjAABTIwAAWyMAAGQjAABtIwAAeSMAAIEjAACKIwAA",
    "kiMAAJwjAACkIwAArCMAALYjAAC6IwAAvyMAAMcjAADRIwAA3CMAAOUjAADqIwAA8iMAAPojAAADJAAACyQAABMkAAAZJAAAISQA",
    "AC8kAAA2JAAAPCQAAEQkAABPJAAAVCQAAFskAABuJAAAgyQAAJUkAACoJAAAxSQAANgkAADrJAAA9CQAAP8kAAAXJQAAMSUAADcl",
    "AABAJQAASSUAAFYlAABjJQAAhyUAAJElAACbJQAArCUAAMUlAADNJQAA1iUAAN4lAADpJQAA8iUAAPklAAACJgAADCYAABEmAAAb",
    "JgAAISYAADAmAAA6JgAATiYAAF8mAABzJgAAfyYAAI8mAACXJgAAoCYAAK4mAAC7JgAAyiYAANAmAADVJgAA3SYAAOQmAADrJgAA",
    "9yYAAP8mAAAMJwAAFycAAB0nAAAoJwAAMicAAD8nAABJJwAAUCcAAFknAABiJwAAbCcAAHQnAAB8JwAAhicAAI4nAACUJwAAmScA",
    "AKcnAACwJwAAuScAAMYnAADMJwAA0icAAB0AAAAiAAAAKgAAAC8AAAA+AAAAPwAAAEAAAABBAAAAQgAAAEMAAABEAAAARQAAAEYA",
    "AABHAAAASAAAAEkAAABKAAAASwAAAEwAAABNAAAATgAAAE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUAAABWAAAAVwAAAFgAAABZ",
    "AAAAWgAAAFsAAABcAAAAXQAAAF4AAABfAAAAYAAAAGEAAABiAAAAYwAAAGQAAABlAAAAZgAAAGcAAABoAAAAaQAAAGoAAABrAAAA",
    "bAAAAG0AAABuAAAAbwAAAHAAAABxAAAAcgAAAHMAAAB0AAAAhAAAAJYAAACbAAAAnAAAAJ0AAACeAAAAnwAAAKAAAAChAAAAogAA",
    "AB4AAAAAAAAAtCkAACoAAAACAAAAAAAAACsAAAACAAAAtCkAACwAAAACAAAAgCkAAC0AAAACAAAAFCgAAC4AAAACAAAA7CgAAC0A",
    "AAACAAAAACkAAC8AAAADAAAAAAAAADsAAAAHAAAAkCgAADkAAAAKAAAA6CcAADAAAAALAAAAAAAAADUAAAANAAAA+CkAADAAAAAP",
    "AAAAAAAAADAAAAAXAAAAAAAAADAAAAAdAAAAAAAAADMAAAAdAAAAtCkAADgAAAAdAAAAACkAADAAAAAeAAAAAAAAADAAAAAhAAAA",
    "AAAAADAAAAAjAAAAAAAAADAAAAAkAAAAAAAAADwAAAAnAAAA3CcAAD0AAAArAAAAKCoAADAAAAAsAAAAAAAAADQAAAAsAAAAgCkA",
    "ADAAAAAtAAAAAAAAADgAAAAtAAAAJCgAADMAAAAvAAAAtCkAADgAAAAvAAAAACkAADsAAAAvAAAA8CkAADgAAAAvAAAAJCgAADAA",
    "AAAxAAAAAAAAADIAAAAxAAAAcCgAADQAAAAxAAAAgCkAADgAAAAxAAAAaCgAADgAAAAxAAAAoCgAADsAAAAxAAAAeCgAADgAAAAx",
    "AAAAMCoAADsAAAAxAAAALCgAADgAAAAxAAAAACkAADgAAAAxAAAAJCgAADgAAAAxAAAAgCgAADoAAAAxAAAA5CkAADEAAAAyAAAA",
    "iCkAADMAAAAyAAAAtCkAADQAAAAyAAAAgCkAADcAAAAyAAAAvCkAADgAAAAyAAAAMCoAADgAAAAyAAAAJCgAAD0AAAAyAAAAKCoA",
    "ADsAAAA3AAAArCkAAIQAAAA7AAAAAAAAAIUAAAA7AAAAnCkAAIYAAAA7AAAAtCkAAIcAAAA7AAAAgCkAAIgAAAA7AAAACCgAAIkA",
    "AAA7AAAABCoAAIoAAAA7AAAAECkAAIsAAAA7AAAAaCgAAJEAAAA7AAAA3CkAAJEAAAA7AAAATCkAAIsAAAA7AAAAmCgAAIsAAAA7",
    "AAAAHCgAAIsAAAA7AAAAECoAAIsAAAA7AAAAoCgAAIsAAAA7AAAAICoAAI0AAAA7AAAARCgAAJIAAAA7AAAAuCgAAJMAAAA7AAAA",
    "kCkAAJMAAAA7AAAAVCkAAIsAAAA7AAAAaCkAAIsAAAA7AAAAYCgAAIsAAAA7AAAAzCkAAIsAAAA7AAAAGCkAAIsAAAA7AAAAFCgA",
    "AI4AAAA7AAAA7CgAAJEAAAA7AAAAOCkAAJQAAAA7AAAAxCgAAJEAAAA7AAAACCkAAIsAAAA7AAAAqCgAAIsAAAA7AAAApCkAAJEA",
    "AAA7AAAAWCgAAIsAAAA7AAAAiCgAAIsAAAA7AAAAMCoAAIwAAAA7AAAAQCkAAI8AAAA7AAAAcCkAAJAAAAA7AAAAICkAAIsAAAA7",
    "AAAAACkAAIsAAAA7AAAAYCkAAI0AAAA7AAAA9CgAAI8AAAA7AAAAzCgAAJEAAAA7AAAAGCoAAJUAAAA7AAAAKCoAAI8AAAA7AAAA",
    "NCgAAJAAAAA7AAAA8CcAAIsAAAA7AAAAxCkAAIsAAAA7AAAA1CkAAJYAAAA8AAAAAAAAAJcAAAA8AAAAgCkAAJgAAAA8AAAAFCgA",
    "AJgAAAA8AAAApCkAAJoAAAA8AAAAsCgAAJgAAAA8AAAAACkAAJkAAAA8AAAAUCgAAJgAAAA8AAAAJCgAADAAAABAAAAAAAAAADgA",
    "AABCAAAAJCgAADAAAABDAAAAAAAAADAAAABEAAAAAAAAAAoAMQBwAQAADAAMABsAAAATADEAeQAAABMAAgCAAAAAFAAxAHUAAAAf",
    "AAIAtwAAACgAQgCpAAAAKAAmALIAAAAoADEAtQAAACgADQC2AAAAKAA8ALkAAAAoAAQAwwAAACgAIQDGAAAAKAACAMwAAAAoABYA",
    "+QAAACgAAgD8AAAAKAA8AP8AAAAoADwAAAEAACgAAgABAQAAKAA3AAIBAAAoADwABQEAACgAHQAQAQAAKAAyABUBAAAoAD8AGgEA",
    "ACgAAgAgAQAAKAA8ACMBAAAoADwAJwEAACgAAgAoAQAAKAACADUBAAAoAEIANgEAACgAPABBAQAAKAACAFsBAAAoAAIAXgEAACgA",
    "PABfAQAAKAA8AGQBAAAoAC0AZQEAACgAPABmAQAAKAA2AGcBAAAoADYAaAEAACgAAgBpAQAAKAACAHIBAAAoAAIAcwEAACoAMQDP",
    "AAAAKgACANIAAAAqAAIA+wAAACoAAgADAQAAKgACABQBAAAqAAIASAEAACoAMgBsAQAAKwArACMAAAArAC0AgwAAADUALQCDAAAA",
    "OAA4AHYAAAA6ADoAfAAAAAQADwDNAAAABAAUAPcAAAAGAD0AOQEAAAcACAAiAQAACAA/AE8BAAAJAAoA6QAAAAkAHwDqAAAACQAe",
    "APAAAAALAAkA6AAAAA0ACwDCAAAADQABAPYAAAAOAEEAGQAAAA4ADADjAAAADwBfABgBAAAWAEcAGQAAABYAZwA4AQAAFgBYADwB",
    "AAAXAA0A4gAAABgABwBtAQAAHQBIAKwAAAAdAEYAxwAAAB0AEADOAAAAHQABANQAAAAdABcA2AAAAB0AAQDdAAAAHQABAN4AAAAd",
    "ABEA3wAAAB0AAQDgAAAAHQBgAOEAAAAdAAEA5AAAAB0AEgDsAAAAHQABAO4AAAAdABsA8QAAAB0AAQDyAAAAHQATAPQAAAAdAAEA",
    "9QAAAB0AAQD2AAAAHQAzAAgBAAAdADgAEQEAAB0ANgAZAQAAHQA0AEoBAAAdADUASwEAAB0ATwBMAQAAHQBJAE0BAAAdADgATgEA",
    "AB0AOQBQAQAAHQBXAFABAAAdADUAVAEAACAATACuAAAAIAAPANUAAAAgAAEA1gAAACAAAQDdAAAAIwBSAK0AAAAjAGEACgEAACMA",
    "UgA9AQAAJAAOANwAAAAlADcAGQAAACYAQAAZAAAAJgBTAFEBAAAmADUAUgEAACYANABTAQAAJwAVABcBAAAnADMAVQEAACgAMwAZ",
    "AAAAKAAfAKoAAAAoADoAsQAAACgAYQCzAAAAKAAzALQAAAAoAGUAuAAAACgAJQC7AAAAKABhAL0AAAAoADwAwQAAACgASgDRAAAA",
    "KABOAPgAAAAoAE4A/QAAACgATQD+AAAAKAAFAAYBAAAoAGgACwEAACgAZAANAQAAKABjAA4BAAAoAB8ADwEAACgAUAATAQAAKABb",
    "ABYBAAAoADYAGwEAACgANQAcAQAAKAADAB0BAAAoAGIAHgEAACgAYwAfAQAAKABhACEBAAAoACgAJAEAACgATgAlAQAAKAA7ACkB",
    "AAAoADoAKgEAACgAOgArAQAAKAA6ACwBAAAoADsALQEAACgAOgAuAQAAKAA6AC8BAAAoAEoAMAEAACgAYQAxAQAAKABKADIBAAAo",
    "AEoAMwEAACgATgA0AQAAKAAfADcBAAAoAGEAOgEAACgASgA7AQAAKAAkAD4BAAAoACIAQAEAACgASgBCAQAAKABKAEMBAAAoAFEA",
    "RAEAACgAUQBFAQAAKAAzAEYBAAAoAE4AVQEAACgATgBWAQAAKABhAFcBAAAoAD4AWQEAACgAMwBgAQAAKAA8AGMBAAAoAAQAagEA",
    "ACgAIwBvAQAAKABLAHEBAAApAD4ABwEAACoAQQAZAAAAKgBUAKsAAAAqAEIAyAAAACoAQwDIAAAAKgBEAMgAAAAqAEUAyAAAACoA",
    "VQDJAAAAKgBZAMkAAAAqAFoAyQAAACoAXQDJAAAAKgBWAMoAAAAqAF4AygAAACsAFgBuAQAALAAYAFwBAAAtABoA0AAAAC0AawDZ",
    "AAAALQBsANoAAAAtADIA5QAAAC0AHwDnAAAALQAZAO8AAAAtAGYADAEAAC4AAgCoAAAALwAzABkAAAAvABkA1wAAADEAAAC6AAAA",
    "MQBmAMsAAAAxAAEA+gAAADEAAQASAQAAMQAgAD8BAAAxAGoAWAEAADEAaABaAQAAMQAhAF0BAAAxACkAYQEAADEAHwBrAQAAMQAn",
    "AG4BAAAxACoAbgEAADIAMwAZAAAAMgArALAAAAAyACwAsAAAADIALgCwAAAAMgAvALAAAAAyADAAsAAAADIAMQCwAAAAMgAtAMUA",
    "AAAyAAEAEgEAADIAHwBiAQAAMwAHAMQAAAAzAAYABAEAADQAHwBiAQAANgAcANMAAAA2AAEA5gAAADYAGQDzAAAANgBcAEkBAAA3",
    "ABkA2wAAADcAAQDmAAAANwAfAOcAAAA3AGkA6wAAADcAGQDtAAAANwAdAAkBAAA5ACYAJgEAACgAAAARAAAALwAAANwoAAAfAAAA",
    "AAAAAEdJAAAAAAAAKQAAABEAAAAvAAAAAAAAACEAAAAAAAAAkUoAAAAAAAAqAAAAEQAAAA4AAAAAAAAAfwAAAAAAAACaSgAAAAAA",
    "AAAAAgoKAAoKYWN0aXZpdHk9AAUKYXBwPQAICmRldmljZT0ACApoaWRkZW49AAIgIAAPICBBRCBieSBsYWJlbCAnABsgIEFEIGJ5",
    "IHRoZSBoaWRlLWFkIGJ1dHRvbgoACiAgYW5kcm9pZD0ACyAgY29sbGFwc2U9AAggIGZhaWxzPQAOICBoaW50LWVycm9ycz0AEiAg",
    "cmVtZW1iZXJlZC1oaXRzPQAGICBzZGs9AAkgIHhmYWlscz0ABiBiYW5kPQAKIGNoaWxkcmVuPQADIGg9AAcgaGlkZGVuAAQgbHA9",
    "ACwgcm93KHMpIGhpZGRlbi4gTG9nIGNvcGllZCB0byB0aGUgY2xpcGJvYXJkLgAFIHRvcD0ABSB2aXM9AAQnIHk9AAY8aW5pdD4A",
    "AT8AB0FMUEhBXzgAF0JBQ0sgT04gYWZ0ZXIgdGhlIHJlc3QKAAFDAAJDSQAJQ29yZS5qYXZhAARFWEMgAApFbnRyeS5qYXZhAAFG",
    "AAVGQUxTRQAmR0lWRSBVUCAobm8gdGV4dCBpbiB0aGUgaGVhZGVyKSwgc2hvd24ABEhJREUAF0hJREUgYnkgcmVtZW1iZXJlZCBp",
    "dGVtAAlISU5UIGV4YyAADEhJTlQgbWV0aG9kIAAWSElOVCBub25lLCBjYW5kaWRhdGVzPQABSQACSUkAA0lJSQACSUwAA0lMSQAB",
    "SgABTAACTEMAA0xDQwACTEkAA0xJSQAETElJTAAFTElTVCAAAkxKAAJMTAADTExJAARMTElJAANMTEwABExMTEkAAkxaABZMYW5k",
    "cm9pZC9hcHAvQWN0aXZpdHk7ADRMYW5kcm9pZC9hcHAvQXBwbGljYXRpb24kQWN0aXZpdHlMaWZlY3ljbGVDYWxsYmFja3M7ABlM",
    "YW5kcm9pZC9hcHAvQXBwbGljYXRpb247ABpMYW5kcm9pZC9jb250ZW50L0NsaXBEYXRhOwAiTGFuZHJvaWQvY29udGVudC9DbGlw",
    "Ym9hcmRNYW5hZ2VyOwAZTGFuZHJvaWQvY29udGVudC9Db250ZXh0OwAgTGFuZHJvaWQvY29udGVudC9wbS9QYWNrYWdlSW5mbzsA",
    "I0xhbmRyb2lkL2NvbnRlbnQvcG0vUGFja2FnZU1hbmFnZXI7ACBMYW5kcm9pZC9ncmFwaGljcy9CaXRtYXAkQ29uZmlnOwAZTGFu",
    "ZHJvaWQvZ3JhcGhpY3MvQml0bWFwOwAZTGFuZHJvaWQvZ3JhcGhpY3MvQ2FudmFzOwAZTGFuZHJvaWQvZ3JhcGhpY3MvTWF0cml4",
    "OwAYTGFuZHJvaWQvZ3JhcGhpY3MvUGFpbnQ7ABdMYW5kcm9pZC9ncmFwaGljcy9SZWN0OwAYTGFuZHJvaWQvZ3JhcGhpY3MvUmVj",
    "dEY7ABpMYW5kcm9pZC9vcy9CdWlsZCRWRVJTSU9OOwASTGFuZHJvaWQvb3MvQnVpbGQ7ABNMYW5kcm9pZC9vcy9CdW5kbGU7ABRM",
    "YW5kcm9pZC9vcy9IYW5kbGVyOwATTGFuZHJvaWQvb3MvTG9vcGVyOwAYTGFuZHJvaWQvb3MvU3lzdGVtQ2xvY2s7ABpMYW5kcm9p",
    "ZC92aWV3L1N1cmZhY2VWaWV3OwAaTGFuZHJvaWQvdmlldy9UZXh0dXJlVmlldzsAL0xhbmRyb2lkL3ZpZXcvVmlldyRPbkF0dGFj",
    "aFN0YXRlQ2hhbmdlTGlzdGVuZXI7ACNMYW5kcm9pZC92aWV3L1ZpZXckT25DbGlja0xpc3RlbmVyOwATTGFuZHJvaWQvdmlldy9W",
    "aWV3OwAlTGFuZHJvaWQvdmlldy9WaWV3R3JvdXAkTGF5b3V0UGFyYW1zOwArTGFuZHJvaWQvdmlldy9WaWV3R3JvdXAkTWFyZ2lu",
    "TGF5b3V0UGFyYW1zOwAYTGFuZHJvaWQvdmlldy9WaWV3R3JvdXA7ABlMYW5kcm9pZC92aWV3L1ZpZXdQYXJlbnQ7ADFMYW5kcm9p",
    "ZC92aWV3L1ZpZXdUcmVlT2JzZXJ2ZXIkT25QcmVEcmF3TGlzdGVuZXI7AB9MYW5kcm9pZC92aWV3L1ZpZXdUcmVlT2JzZXJ2ZXI7",
    "ABVMYW5kcm9pZC92aWV3L1dpbmRvdzsAKUxhbmRyb2lkL3dpZGdldC9GcmFtZUxheW91dCRMYXlvdXRQYXJhbXM7ABlMYW5kcm9p",
    "ZC93aWRnZXQvVGV4dFZpZXc7ABZMYW5kcm9pZC93aWRnZXQvVG9hc3Q7AC1MYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9v",
    "ay9hZGhpZGUvQ29yZTsALkxhcHAvcmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL2FkaGlkZS9FbnRyeTsALExhcHAvcmV2YW5j",
    "ZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL2FkaGlkZS9SZWM7ABNMamF2YS9sYW5nL0Jvb2xlYW47ABhMamF2YS9sYW5nL0NoYXJTZXF1",
    "ZW5jZTsAEUxqYXZhL2xhbmcvQ2xhc3M7ABBMamF2YS9sYW5nL01hdGg7ABJMamF2YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcv",
    "UnVubmFibGU7ABJMamF2YS9sYW5nL1N0cmluZzsAGUxqYXZhL2xhbmcvU3RyaW5nQnVpbGRlcjsAEkxqYXZhL2xhbmcvU3lzdGVt",
    "OwAVTGphdmEvbGFuZy9UaHJvd2FibGU7ABBMamF2YS9sYW5nL1ZvaWQ7ABlMamF2YS9sYW5nL3JlZmxlY3QvRmllbGQ7ABpMamF2",
    "YS9sYW5nL3JlZmxlY3QvTWV0aG9kOwAbTGphdmEvdGV4dC9Ob3JtYWxpemVyJEZvcm07ABZMamF2YS90ZXh0L05vcm1hbGl6ZXI7",
    "ABJMamF2YS91dGlsL0xvY2FsZTsABU1PREVMAANORkMAAk9LAAlSRUxBWU9VVCAAB1JFTEVBU0UAB1JFTkRFUiAAJ1JFU1RJTkcg",
    "Zm9yIDEwIHMgKDYgZmFpbHVyZXMgaW4gYSByb3cpCgAEUk9PVAAKUlYgaGlkZXI6IAAQUlYtSElERVIgMQp0aW1lPQAIUmVjLmph",
    "dmEAB1NES19JTlQACVRSRUUgZXhjIAAMVFJFRSBmaWVsZHM9AARUWVBFAAFWAAJWRgACVkkAA1ZJSQAEVklJSQAFVklJSUkAA1ZJ",
    "TAACVkwABFZMRkYABVZMRkZMAANWTEkAB1ZMSUlGRkwAClZMSUlJSUZGWkwAA1ZMTAAEVkxMTAAFVkxMTEwAA1ZMWgACVloAAVoA",
    "A1pJSQACWkwAA1pMSgADWkxMAAJbQwACW0YAAltJABJbTGphdmEvbGFuZy9DbGFzczsAE1tMamF2YS9sYW5nL09iamVjdDsAE1tM",
    "amF2YS9sYW5nL1N0cmluZzsAGltMamF2YS9sYW5nL3JlZmxlY3QvRmllbGQ7ABtbTGphdmEvbGFuZy9yZWZsZWN0L01ldGhvZDsA",
    "EFtoaWRlLWFkIGJ1dHRvbl0AAlx8AAxfX1JWX0FEQlROX18ADV9fUlZfTEFCRUxTX18ADl9fUlZfUE9TVEJUTl9fAANhYnMABWFk",
    "QXJyAAlhZEJ1dHRvbnMAA2FkZAAeYWRkT25BdHRhY2hTdGF0ZUNoYW5nZUxpc3RlbmVyABRhZGRPblByZURyYXdMaXN0ZW5lcgAH",
    "YWRkVmlldwArYXBwLnJldmFuY2VkLmV4dGVuc2lvbi5mYWNlYm9vay5hZGhpZGUuQ29yZQAGYXBwZW5kAAZhdHRhY2gABWJhZGdl",
    "AAdiYWRnZU9uAAliYWRnZVRleHQABGJhbmQAA2JtcAAMYm90dG9tTWFyZ2luAARjZEluAAdjaGFuZ2VkAAZjaGFyQXQABWNsZWFu",
    "AAljbGlwYm9hcmQACGNvbGxhcHNlABNjb20uZmFjZWJvb2subGl0aG8uACBjb20uZmFjZWJvb2subGl0aG8uQ29tcG9uZW50VHJl",
    "ZQAcY29tLmZhY2Vib29rLmxpdGhvLkxpdGhvVmlldwAEY29weQAMY3JlYXRlQml0bWFwAAdjdXJyZW50ABFjdXJyZW50VGltZU1p",
    "bGxpcwAGZGVsZXRlAAVkaXJ0eQAEZHJhdwAKZHJhd0JpdG1hcAAIZHJhd1RleHQAC2RyYXdUZXh0UnVuAAZlcXVhbHMABWZhaWxz",
    "AAxmaW5kVmlld0J5SWQAD2ZpbmRWaWV3V2l0aFRhZwAFZmlyc3QAB2Zvck5hbWUABmZvcmdldAACZnkAA2dldAAJZ2V0Qm90dG9t",
    "AApnZXRDaGlsZEF0AA1nZXRDaGlsZENvdW50AAhnZXRDbGFzcwAVZ2V0Q29udGVudERlc2NyaXB0aW9uABFnZXREZWNsYXJlZEZp",
    "ZWxkcwASZ2V0RGVjbGFyZWRNZXRob2RzABFnZXREZWNsYXJpbmdDbGFzcwAMZ2V0RGVjb3JWaWV3AAlnZXRIZWlnaHQABWdldElk",
    "AA9nZXRMYXlvdXRQYXJhbXMAB2dldExlZnQAE2dldExvY2F0aW9uT25TY3JlZW4ADWdldE1haW5Mb29wZXIACWdldE1hdHJpeAAR",
    "Z2V0TWVhc3VyZWRIZWlnaHQACWdldE1ldGhvZAAMZ2V0TW9kaWZpZXJzAAdnZXROYW1lAA5nZXRQYWNrYWdlSW5mbwARZ2V0UGFj",
    "a2FnZU1hbmFnZXIADmdldFBhY2thZ2VOYW1lABFnZXRQYXJhbWV0ZXJUeXBlcwAJZ2V0UGFyZW50AA1nZXRSZXR1cm5UeXBlAAhn",
    "ZXRSaWdodAANZ2V0U3VwZXJjbGFzcwAQZ2V0U3lzdGVtU2VydmljZQAGZ2V0VGFnAAZnZXRUb3AAB2dldFR5cGUAE2dldFZpZXdU",
    "cmVlT2JzZXJ2ZXIADWdldFZpc2liaWxpdHkACGdldFdpZHRoAAlnZXRXaW5kb3cABGdpdmUAB2hhbmRsZXIACGhhc2hDb2RlAAJo",
    "aQAGaGlkZGVuAARoaWRlAARoaW50AAdoaW50QmFkAAhoaW50RG9uZQAHaGludEV4YwAFaGludE0AA2hpdAAQaWRlbnRpdHlIYXNo",
    "Q29kZQAGaW5UYWlsAAdpbnNwZWN0AAdpbnN0YWxsAAppbnZhbGlkYXRlAAZpbnZva2UAB2lzQWxpdmUAB2lzRXhhY3QACmlzSW5z",
    "dGFuY2UABmlzTGlzdAAHaXNMaXRobwAGbGFiZWxzAAhsYXN0TGlzdAAGbGF5b3V0AAZsZW5ndGgACGxpc3RQYXNzAAJsbwADbG9n",
    "AAZsb2dSb3cACG1ha2VUZXh0AAltYXBQb2ludHMAB21lYXN1cmUAA21lbQAGbWVtQWRkAAZtZW1EZWwAB21lbUZpbmQABm1lbUhh",
    "cwAGbWVtSGl0AARtZW1OAAZtZW1vcnkADG5ld1BsYWluVGV4dAAFbm9SZWwABG5vcm0ABm5vcm1hbAAJbm9ybWFsaXplAANvZmYA",
    "BW9mZkF0ABFvbkFjdGl2aXR5Q3JlYXRlZAATb25BY3Rpdml0eURlc3Ryb3llZAAQb25BY3Rpdml0eVBhdXNlZAARb25BY3Rpdml0",
    "eVJlc3VtZWQAG29uQWN0aXZpdHlTYXZlSW5zdGFuY2VTdGF0ZQARb25BY3Rpdml0eVN0YXJ0ZWQAEW9uQWN0aXZpdHlTdG9wcGVk",
    "AAdvbkNsaWNrAAlvblByZURyYXcAFm9uVmlld0F0dGFjaGVkVG9XaW5kb3cAGG9uVmlld0RldGFjaGVkRnJvbVdpbmRvdwAEcGVu",
    "ZAAHcGVuZGluZwAHcG9zdEFycgALcG9zdEJ1dHRvbnMAC3Bvc3REZWxheWVkACJyZWdpc3RlckFjdGl2aXR5TGlmZWN5Y2xlQ2Fs",
    "bGJhY2tzAAhyZWxheW91dAAIcmVtZW1iZXIAD3JlbW92ZUNhbGxiYWNrcwAXcmVtb3ZlT25QcmVEcmF3TGlzdGVuZXIABnJlbmRl",
    "cgAHcmVwbGFjZQAGcmVwb3J0AAlyZXN0b3JpbmcAB3Jvd0ZhaWwABXJvd09rAAdyb3dQYXNzAAhyb3dQYXNzMAADcnVuAAhydi1o",
    "aWRlcgAEc2VlbgANc2V0QWNjZXNzaWJsZQAIc2V0QWxwaGEAEnNldEJhY2tncm91bmRDb2xvcgAPc2V0TGF5b3V0UGFyYW1zABJz",
    "ZXRPbkNsaWNrTGlzdGVuZXIACnNldFBhZGRpbmcADnNldFByaW1hcnlDbGlwAAZzZXRUYWcAB3NldFRleHQADHNldFRleHRDb2xv",
    "cgALc2V0VGV4dFNpemUADXNldFZpc2liaWxpdHkABHNob3cAA3NpZwAGc21vb3RoAAVzcGxpdAAFc3RhcnQACnN0YXJ0c1dpdGgA",
    "BnN0cmVhawALc3ViU2VxdWVuY2UACXN1YnN0cmluZwAEdGFpbAAJdGFpbEZyYW1lAAh0YWlsUG9zdAALdG9Mb3dlckNhc2UACHRv",
    "U3RyaW5nAAV0b2FzdAAHdHJlZUJhZAAHdHJlZUNscwAIdHJlZURvbmUABnRyZWVGMQAGdHJlZUYyAAh0cmVlSGl0cwAGdHJlZUlk",
    "AAR0cmltAAN0eHQADHVwdGltZU1pbGxpcwAHdmFsdWVPZgAHdmVyc2lvbgALdmVyc2lvbk5hbWUABHdhbGsABHdpblcABnhmYWls",
    "cwAAAAMAAAAJACwAAgAAAAIAAAAxAAIACQAAAD0AAgACAAIAAgABAAEAPAAQAAAAAwAAAAIAAgACAAAAAQAAAB0AAAABAAAABgAA",
    "AAEAAAAxAAAAAgAAACwAOAAGAAAAPQACAAIAAQABABAABAAAAA0AAQABABAAAgAAADAAAwACAAAAIAAdAAEAAAAXAAAAAQAAAAQA",
    "AAACAAAAAAAAAAIAAAAdACoAAQAAADoAAAABAAAAIgAAAAIAAAAsACwAAQAAAAUAAAABAAAACQAAAAEAAAAeAAAAAgAAACwAQgAD",
    "AAAADQAPABAAAAACAAAAHQA8AAYAAAAxAAIAAgABAAEAEAAFAAAABQAiADAAHAAbAAAAAgAAAB0AAgAEAAAAMQABAAEAEAABAAAA",
    "LwAAAAIAAAAdAD8AAgAAAAIALwABAAAAHAAAAAkAAAAsAAIAAgACAAIAAQABADwAEAAAAAIAAAAdAB4AAwAAACwAAQABAAAAAgAA",
    "AAQAMQAEAAAADQARABIAEAABAAAAMAAAAAEAAAAOAAAABgAAACwAAgACAAEAAQAQAAIAAAACAAIAAQAAAAAAAAAEAAAADQARABEA",
    "EAABAAAAAQAAAAEAAAAgAAAAAgAAADEAQAABAAAAAgAAAAEAAAADAAAAAQAAAD4AAAABAAAAGwAAAAEAAAA/AAAAAgAAAAQAFQAD",
    "AAAAPQACAAIAAAACAAAALwBBAAMAAAACAAIADAAAAAQAAAACAAIAAgACAAEAAAAHAAAAAgAAADEAHQABAAAADQAAAAEAAAA8AAAA",
    "AQAAACwAAAAAAAAABAABAAIAAAAAAAAAMgAAAHAQkQADACIAFgBxABEAAAAMAXAgDgAQAFswDgAiADIAcBCfAAAAWzAWABMAQAAj",
    "AD8AWzAXABoCpABxAEAAAAAMAG4gmAAgAAwAWzAGAHEAZwAAAAwAbiCYACAADABbMB0ADgABAAAAAAAAAAAAAAADAAAAGgClABEA",
    "AAAIAAIABQAAAAAAAABRAAAAFAACAAIBbiAAAAcADAAgASAAOAFHABoBRwFuIBUAEAAMAiAjJgA4AwoAHwImAFtiBwBwEEMABgAO",
    "AB8AIAAiAiYAcCA5AHIAbiAuABIAFQMgQW4gPAAyABLzbiA7ADIAFAMAAACZbiApADIAEwMMABJEblQsADI0biArAGIAIgMlABLk",
    "FAVTAIAAcEA4AENUbjAwACADW2IHAHAQQwAGAA4AAAABAAAAAAAAAAAAAAACAAAAEhAPAAQAAQACAAAAAAAAACAAAABUMAcAOAAd",
    "ACIBMgBwEJ8AAQAaAn0AbiCkACEAUjIPAG4goQAhABoCEwBuIKQAIQBuEKgAAQAMAW4gOgAQAA4ABwACAAIAAAAAAAAAJwAAADgG",
    "JQBxEJ0ABQAMAG4QlgAAAAoBEjI0IRoAEwIwADYhFgBxEFkAAAAMACFhEgI1Eg4ARgMGAtgCAgFuIJQAMAAKBDgE9v8SFA8EEgQP",
    "BAAABQABAAMAAAAAAAAAIAAAAHEQnQAEAAwAEwEKABMCIABuMJcAEAIMABMBDQBuMJcAEAIMAG4QlgAAAAoBEwPIADcxBwASAW4w",
    "mgAQAwwAEQABAAAAAAAAAAAAAAACAAAAEhAPAAYAAgADAAEAAAAAACIAAABuEJYABQAKABQBwNQBADcQBwASAG4wmgAFAQwFGgC8",
    "AG4gBwAEAAwAHwAIABoBRwFxIAMAUQAMAW4gBAAQAA4ADQAo/gAAAAAfAAEAAQAgAAQAAgACAAAAAAAAAAoAAABwIHcAMgAKADgA",
    "BQBwIFQAAgAOAAcAAwADAAAAAAAAACcAAABwMFoAVAYSQEQBBgA4ASAAEgFLAQYAbhAaAAUADAAgAR8AOAEVAB8AHwASMUQBBgFZ",
    "AQUAbiAqAAUAbhAeAAUADAFbQQwAEhFcQQoADgAAAAgAAwADAAAAAAAAAGMAAAASQG4gLwAGABMBCgASEksCBwFVUCEAOQAFABIg",
    "WVAgABMBCwBEAgcBEiMzMhAAcQASAAAACwOEMxMEDQBEBAcEsUMTBDAANEMJABISSwIHARIDcDBLAGUDcQBGAAAACgA4ADIAbhAd",
    "AAYACgE9ASwAbhAaAAYADAAgAh8AOAIkAB8AHwASQkQDBwI5AwoAUgMFABI0SwMHBBITSwMHAnsRUgMFADITEABZAQUAbiAqAAYA",
    "bhAeAAYADANbUwwAEhNcUwoADgAAAA8AAwADAAEAAAAAALEAAABVwBAAOQCPAFTAEwA5AG8AVcERADkBhwASEVzBEQAaAcAAcRCJ",
    "AAEADAFuEIsAAQAMAiEjEgQSBRIANTQyAEYGAgTYBAQBbhCxAAYACgfdCAcIOQj0/90IBwE4CPD/bhC0AAYADAdiCDMAM4fo/24Q",
    "swAGAAwHIXgSKTOY4P9iCTIAEgpGCgcKM5rZ/xIaRgoHCjOa1P/YBQUBB2Aoz1TGFgASFzJ1EAAaBykAbiCkAHYAbiChAFYAEwcK",
    "AG4goAB2ACgwW8ATABoHKABuIKQAdgBuELIAAAAMB24gpAB2ABMHCgBuIKAAdgBuELAAAAAMAW4gjwDRAAoCOAIUABIiIyJBAHEQ",
    "hwAOAAwDEgRNAwIEYgMxABIUTQMCBG4wtQDQAg4ADQBSwRIA2AEBAVnBEgATAgoANiH2/1TBFgAaAicAbiCkACEAbhCrAAAADABu",
    "IKQAAQATAAoAbiCgAAEAKOEAAAQAAACNAAEAAQCSAQsAAwADAAAAAAAAAK8AAABuECQACQAKADwABAASAA8AbhAYAAkACgHaAgAC",
    "2wICBTUhBAASEA8AFAMBoPB/biAgADkADAMgND8AOAQRAB8DPwATBAwARAQDBBI1NFQIADgKBAASIA8AEhAPAFSBCQA4AQgAbhAK",
    "AAEACgIyAg4A2gIAAtsCAgViAwEAcTAJACADDAFbgQkAIgIqAHAgewASANoDAAnbAwNkWSMuANoDABnbAwNkWSMsAHEgagApAAwE",
    "bhCWAAQACgM4AxUAcCBsAJgAVIUWABoGegBuIKQAZQBuIKQARQATBgoAbiCgAGUAKARwIG0AmABUJTAAbhCoAAUADAVbhQgAVIQW",
    "AFIjLQA9Ax0AGgUHAG4gpABUAFQlKgBuIKQAVAAaBRgAbiCkAFQAUiUrAG4goQBUABMFCgBuIKAAVAASIA8AOAoJABoFCABuIKQA",
    "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",
    "MgAaAAAAEQANAG4QqwAAAAwAEQAAAAAAAwABAAEABgAGAAIAAwAAAAAAAACkAAAAIgAyAHAQnwAAABoBfgBuIKQAEABxAKkAAAAL",
    "Am4wogAgAxoBBABuIKQAEABiAQQAbiCkABAAGgEJAG4gpAAQAGIBAgBuIKQAEAAaAQ4AbiCkABAAYAEDAG4goQAQABoBAwBuIKQA",
    "EABuEAYABQAMAW4gpAAQABoBBgBuIKQAEABxEHgABQAMAW4gpAAQABoBAgBuIKQAEABuEJIABQAMAW4QjQABAAwBbiCkABAAGgEF",
    "AG4gpAAQAFJBDwBuIKEAEAAaAQsAbiCkABAAUkENAG4goQAQABoBDwBuIKQAEABSQSkAbiChABAAGgEMAG4gpAAQAFJBEgBuIKEA",
    "EAAaAQ0AbiCkABAAUkEnAG4goQAQABoBCgBuIKQAEABxAEYAAAAKAW4gpQAQABoBAQBuIKQAEABUQRYAbiCjABAAbhCoAAAADAAR",
    "AAYAAgACAAAAAAAAABwAAABSQA0A2AAAAVlADQAUAAGg8H9uICAABQAMASASPwA4AgwAHwE/ABMCDABEAwEC2AMDAUsDAQIOAAYA",
    "AgACAAAAAAAAABYAAAASAFlADQAUAAGg8H9uICAABQAMASASPwA4AgkAHwE/ABMCDAASA0sDAQIOAAcAAwADAAAAAAAAACoAAABw",
    "MG8AVAYUAAGg8H9uICAABgAMASASPwA4Ah0AHwE/ABMACwBEAgEAEjMzMhQAEwIKAEQCAQI5Ag4AEnJEAgECOQIJABICSwIBABIS",
    "cDBLAGQCDgAOAAMAAwAAAAAAAADnAQAAFAABoPB/biAgAA0ADAEgEj8AOQIJABMCDgAjIT8AbjAtAA0BHwE/AFWyHgA4AgYAcDBx",
    "ANsBDgBxABIAAAALAoQiEiREAwEEOQMEAEsCAQQSUyMzPwBwMHIA2wMSBEQFAwTaBQUfEhREBgMEsGUSJEQGAwQSFEQHAQQSGDOH",
    "FQA5BgwAbhAYAA0ACggTCQgARAkBCTKYHgASB0sHAQQSJEsCAQQoFxIoM4cVABIIRAgBCDJYDAASCEsFAQgSR0sHAQQSJEsCAQQo",
    "BXAwSgDbAQ4AEghbuAgAOQZdABJIModaAG4QFgANAAoIPQhUAG4QIQANAAoIbhAzAAwACgk1mEoAcCBXANsACgg4CEQAElhECQEI",
    "OQkIABIZSwkBCG4gEwC9ABJIEhlLCAEJEghLBQEIEihLAgEIbhAYAA0ACggTCQgASwgBCRMICwBECQEIOQkJABIpSwkBCBMIDQBL",
    "AgEIcDBKANsBUrgnANgICAFZuCcAUrgPANgICAFZuA8AcBBDAAsAGggmAHAwUgCLDQ4AOQYkABJIRAgDCDkIBwASOEQIAwg5CBoA",
    "EkgzhwYAcDBKANsBDgASKEsCAQgSeEQJAQg4CQoAEglLCQEIFQqAP24gKACtAA4AElhECQEIOQkIABIZSwkBCG4gEwC9AG4QGAAN",
    "AAoIEwkIAEsIAQk5BrMAbhAhAA0ACghuECQADQAKCdoKCRnbCgpksIpuEDMADAAKBD0KiQA1SIcAOghyADZKcAASBHAwTADbBAoE",
    "EigyhJIAEhgzhDsAEwgJAEQJAQg5CRMASwIBCBJIM4cGAHAwSgDbAQ4AcDBaANsBEjgSFEsIAQQOAJEJAgkTCggANakDAA4AcDBx",
    "ANsBEkgzhwUAcCBIANsAEhgSFEsIAQQSBEsFAQQTBAkAEghLCAEEGgh3AHAwUgCLDQ4AEkgzhwYAcDBKANsBDgASKEQIAQiRCAII",
    "EwQsATdIEgBwMHEA2wESGBIUSwgBBBIESwUBBBoIJABwMFIAiw0OAHAwZgDbAVK4HADYCAgBWbgcAA4AEmRECAEEkQgCCBMJUAA0",
    "mAwASwIBBBIEcDBMANsECgQSKDKEGQASKEsCAQgTCAkAEglLCQEIEkgzhwYAcDBKANsBDgBwMFoA2wESOBIUSwgBBA4AEkgyhxkA",
    "OAYGABoIowBbuAgAcCBpANsAGgglAHAwUgCLDVK4DwDYCAgBWbgPAHAQQwALABMICwBECQEIEiozqQUAEglLCQEIcDBKANsBEwgJ",
    "ABIJSwkBCBIoEhRLCAEEEgRLBQEEDgAAAAcAAwADAAAAAAAAABQAAAATAAsARAEGADgBDAASAksCBgASIjIhBgASEnAwSwBUAnAw",
    "SQBUBg4ACAADAAMAAAAAAAAAXQAAADgGXAASEEQBBwDYAQEBSwEHABMC0Ac2IVEAIGIaADkCBgAgYhkAOAIFABJCSwAHAm4QFwAG",
    "AAwCOAIpAFRTBgBxIEQAMgAKAzgDBgASJEsABwQoDFRTHQBxIEQAMgAKAzgDBQASNEsABwRxEJ0AAgAMAm4QlQACAAoCEgNEBAcD",
    "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",
    "DQAo/gAAAAAxAAEAAQAyAAMAAQABAAEAAAAAABUAAABUIAsAOAAQABIRXCEUAG4QAQAAAAwAbhA3AAAADABuECUAAAAOAA0AKP4A",
    "AAcAAAALAAEAAQATAAYAAQADAAEAAAAAACEAAAAaAK8AcRCJAAAADAAaAVkBEhIjIkAAEgMcBAYATQQCA24wjAAQAgwBEgISEyMz",
    "QQASBE0FAwRuMLUAIQMOAA0AKP4AAAAAAAAeAAEAAQAfAAMAAgACAAAAAAAAAAsAAABwIAsAIQAiADIAcBCfAAAAWxAwAA4AAAAK",
    "AAQAAgAAAAAAAABPAAAAOQcDAA4AbhAMAAYADAASISMRPgASAksIAQISEksJAQJuIA0AEABEAgEChyJSYywANjI2AFJkLwDYBAQB",
    "WWQvAFJjLgA0MiwAcRBFAAcADANUZDAAbhCnAAQACgUTACwBNQUKAG4gpAA0ABMAfABuIKAABABxEE0AAwAKBDgEEABSZC0A2AQE",
    "AVlkLQBUZSoAOQUGAFtjKgBZYisADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAABAAEAAAAAAAAAAAAAQAAAA4AAAAFAAUAAAAAAAAA",
    "AAABAAAADgAAAAUABQAAAAAAAAAAAAEAAAAOAAAACQAHAAQAAQAAAAAACgAAAHIwiABDBQwAcEB8AAJ2DgANACj+AAAAAAcAAQAB",
    "AAgABgAFAAQAAQAAAAAABgAAAHBAfAAhQw4ADQAo/gAAAAADAAEAAQAEAAkABwAEAAEAAAAAAAoAAABuMJoAQwUMAHBAfAACdg4A",
    "DQAo/gAAAAAHAAEAAQAIAAkABwAEAAEAAAAAAAoAAABxMJ4AQwUMAHBAfAACdg4ADQAo/gAAAAAHAAEAAQAIAAwACgAEAAEAAAAA",
    "AAoAAAByMIgAQwUMAHBAfAACmA4ADQAo/gAAAAAHAAEAAQAIAAwACgAEAAEAAAAAAAoAAABxMJ4AQwUMAHBAfAACmA4ADQAo/gAA",
    "AAAHAAEAAQAIACQvDAYCAQIBAgECAQIBAgECAQIBEgECAQIBAgECAQIBAgECARIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgEC",
    "AQIBAgECAQIBAj+CgAS8VAEJsFUBAshVAQn8VgECkFcBCeBXAQnAWAEJkFkBCqRZAQKEWgECqFoBAohbAQLgXAEC4F8BCdBiAQr0",
    "YwEKlGUBCcxlAQLkZQEChGgBAqxpAQL4aQECuGoBAvBqAQK8awEJ9GsBCYhsAQKwbQwC9G0BCaBuAQK4bgECgHABCqxwAQLgcAEC",
    "uHMBAoB0AQK8dAECoHUCAoB9AQK4fQEJhH8BCZh/AQK8fwEKiIABAQK8gAEBCqCDAQEC5IMBWwHUhAEBAeiEAQEB/IQBAQHghQEB",
    "AeSGAQEB+IYBAQGMhwEBAaCHAQEBkIgBAQHIiwEBAdyLAQsB4IwBAAABAHoJqI0BAAcCCioBAQEBAQEBAQEBAQERe4GABIiOAQEC",
    "sI4BfQHgjwEBAfSPAQEBiJABAQGckAEBAbCQAQEB4JABAQGIkQEBAbiRAQEB6JEBAQGYkgEAAA0AAAAAAAAAAQAAAAAAAAABAAAA",
    "dAEAAHAAAAACAAAARQAAAEAGAAADAAAAbQAAAFQHAAAEAAAANgAAAHAMAAAFAAAAtwAAACAOAAAGAAAAAwAAANgTAAACIAAAdAEA",
    "ADgUAAABEAAAPgAAANwnAAADEAAAAQAAADgqAAABIAAASAAAADwqAAAAIAAAAwAAAEdJAAAAEAAAAQAAAOxKAAA=",
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
        name = "Hide suggested and partnership",
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

        for ((name, text) in listOf("adButtons" to BUTTONS_AD, "postButtons" to BUTTONS_POST)) {
            val m = coreMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
                ?: throw PatchException("Hide native feed ads: Core.$name() not found")
            m.replaceInstruction(0, "const-string v0, \"${smaliString(text)}\"")
        }

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
