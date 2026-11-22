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
 *      or sounding under the invisible row. The telling is repeated while the row is still hidden, but at most once in 48 ms (version 5 did
 *      it in EVERY frame, and that was too much work: every telling makes Litho send its events, "invisible" now and "visible" again at its
 *      next recount; the decompiled Litho clears the visibility items of the row once and works them out again from the row's rectangle
 *      the next time the list moves; the collapsed row's rectangle still overlaps the screen), and, after the last frame in which something
 *      was hidden, twice more 120 ms and 240 ms later (two extra frames that this asks for, then nothing: a still screen costs nothing).
 *      Version 6 also MOVES the hidden row 100000 px to the right (View.setTranslationX; the row is INVISIBLE, so the user sees nothing
 *      different; it is put back at 0 whenever the row is shown, recycled or the patch rests). View.getGlobalVisibleRect / getLocalVisibleRect,
 *      which is what "is this view on the screen" questions of Litho and of the video autoplay use, map the box by the view's translation and
 *      clip it to the parents and the window: a moved row is "not visible" there, as if it had been scrolled away. The video of 22/11 showed
 *      a hidden ad's music starting 0.1 s after the scrolling stopped and playing for the whole clip: no frame, so no telling, and the
 *      INVISIBLE flag is not looked at by those questions. When RecyclerView recycles a hidden row Litho is told
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
 *   9. (option "No depend language", OFF by default, only tried in a simulation) a post that has the two header buttons and no video, whose
 *      first header line is exactly one short label followed by a dot and shows no time and no number, is hidden although its words are not
 *      in the lists, after two looks, and never when the same buttons were seen on an ordinary post; see the option text. When an ad was
 *      confirmed by shape and exactly one of its two button texts is not known from ordinary posts, that text is counted as the "hide ad"
 *      button of the language; after two such ads a row WITH a video surface (never drawn, so it cannot be read) that has this text on one
 *      of its two header buttons is hidden too, unless an ordinary post shows a plain time line with that text (then it is forgotten).
 *
 * Costs (version 6): the tree walk is a few dozen views per frame; the kind of every content description (hide-ad button / hide-post
 * button / neither) is worked out once and kept in a cache of 2048 places (version 5 normalised every text of every row in every frame:
 * about 200 normalisations per frame in the simulation, now less than 1); the telling of Litho is at most once in 48 ms; the time of
 * every pass is counted (nanoTime) and shown in the report (average, longest, how many were shorter than 0.5 / 1 / 2 / 4 / 8 ms or longer,
 * and how many tellings of Litho were made). A row is drawn into the bitmap once or twice per judgement (not every
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
 *   - Litho can also be told by a LithoVisibilityEventsController instead (a tree that has one ignores the hint): such a row is only quiet
 *     if the app's own visible-rect questions see the moved row (see 7). If the app decides what is visible from the positions of the
 *     list's rows without asking the views (not through getGlobalVisibleRect / getLocationOnScreen), nothing here can reach it.
 *   - Version 6's two changes (the cache and the 48 ms telling, and moving the hidden rows) were checked only in the simulation of
 *     the feed, which models Litho and the autoplay as read from the decompiled code and from the video of 22/11; they have not been run
 *     on a real device. The report has the line "sound-playing-now=" (Android says whether any audio is playing when the badge is tapped).
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
    "ZGV4CjAzOAARk54VzH2fByVVMmVtQERJaZUfGE4izCJkXgAAcAAAAHhWNBIAAAAAAAAAAMRdAAC3AQAAcAAAAEcAAABMBwAAeAAA",
    "AGgIAABMAAAACA4AANEAAABoEAAAAwAAAPAWAAAURwAAUBcAAFAXAABSFwAAVhcAAGIXAABpFwAAcxcAAH0XAACqFwAArhcAAL8X",
    "AAAYGAAANRgAAG8YAACGGAAAkhgAAJ4YAACrGAAAtRgAAMUYAADYGAAA5BgAAO8YAAAMGQAAIBkAACgZAABQGQAAZhkAAHcZAACC",
    "GQAAihkAAJYZAACbGQAApBkAAKoZAADYGQAA3xkAAOYZAADsGQAA9BkAAPcZAAAAGgAAGRoAABwaAAAgGgAAKxoAADEaAAA9GgAA",
    "QBoAAEcaAABvGgAAdRoAAI4aAACZGgAApxoAAL8aAADCGgAAxhoAAMsaAADPGgAA1BoAANkaAADcGgAA3xoAAOMaAADoGgAA7BoA",
    "APEaAAD3GgAA/hoAAAIbAAAGGwAACxsAABEbAAAWGwAAHBsAACAbAAA4GwAAbhsAAIkbAAClGwAAyRsAAOQbAAAGHAAAKxwAAE0c",
    "AABoHAAAgxwAAJ4cAAC4HAAA0RwAAOscAAAJHQAAJR0AADkdAABOHQAAZB0AAHkdAACTHQAArx0AAMsdAAD8HQAAIR4AADYeAABd",
    "HgAAih4AAKQeAAC/HgAA8h4AABMfAAAqHwAAVR8AAHAfAACIHwAAtx8AAOcfAAAVIAAAKiAAAEQgAABbIAAAbiAAAIAgAACUIAAA",
    "qiAAAL4gAADZIAAA7SAAAAQhAAAWIQAAMSEAAE0hAABqIQAAgiEAAJYhAACdIQAAoiEAAKYhAACxIQAAuiEAAMMhAADsIQAA8iEA",
    "AP4hAAAQIgAAGiIAACMiAAAuIgAAPCIAAEIiAABFIgAASSIAAE0iAABSIgAAWCIAAF8iAABkIgAAaCIAAG4iAAB1IgAAeiIAAIMi",
    "AACPIgAAlCIAAJoiAAChIgAApiIAAKoiAACtIgAAsSIAALUiAAC6IgAAviIAAMMiAADIIgAAziIAANIiAADWIgAA2iIAAO4iAAAD",
    "IwAAGCMAADQjAABRIwAAiCMAAJojAACeIwAArCMAALsjAADLIwAA0CMAANcjAADiIwAA6SMAAPEjAAD7IwAAAiQAAAskAAAQJAAA",
    "FyQAABwkAAA8JAAAUiQAAFskAACIJAAAkCQAAJgkAACfJAAApiQAAK8kAAC6JAAAwCQAAMUkAADTJAAA2SQAAN8kAADnJAAA8CQA",
    "APgkAAD/JAAACiUAABQlAAApJQAASyUAAGklAABvJQAAfSUAAIUlAACOJQAAoSUAAKklAACwJQAAtiUAAMIlAADMJQAA2SUAAOEl",
    "AADoJQAA9iUAAAcmAAAOJgAAFyYAAB8mAAAjJgAAKCYAADMmAAA/JgAATiYAAFgmAABvJgAAgiYAAJYmAACpJgAAtyYAAMImAADJ",
    "JgAA2iYAAOMmAAD4JgAABycAABInAAAlJwAAMCcAAD4nAABHJwAAVycAAGonAAB6JwAAjScAAJgnAACnJwAAsScAAMAnAADSJwAA",
    "2icAAOInAADzJwAA/CcAABEoAAAgKAAAKigAADUoAAA7KAAARCgAAE4oAABSKAAAWigAAGAoAABmKAAAbygAAHkoAACCKAAAiSgA",
    "AJAoAACVKAAApygAAK8oAAC4KAAAwSgAAM0oAADVKAAA3igAAOcoAADwKAAA/CgAAAYpAAAOKQAAFykAACYpAAA0KQAAOikAAEAp",
    "AABIKQAAUikAAFopAABmKQAAbikAAHgpAAB8KQAAgSkAAIkpAACTKQAAnikAAKcpAACsKQAAtCkAALwpAADFKQAAzSkAANUpAADb",
    "KQAA4ykAAO0pAAD7KQAA/ykAAAcqAAAOKgAAFCoAABwqAAAnKgAALSoAADIqAAA5KgAATCoAAGEqAABzKgAAhioAAKMqAAC2KgAA",
    "ySoAANIqAADdKgAA9SoAAA8rAAAXKwAAISsAACcrAAAvKwAANisAAD0rAABIKwAAUCsAAFYrAABcKwAAZSsAAG4rAAB7KwAAiCsA",
    "AKwrAAC0KwAAvisAAMgrAADZKwAA8isAAPorAAADLAAACywAABYsAAAaLAAAHiwAACcsAAAuLAAANywAAEEsAABGLAAAUCwAAFQs",
    "AABaLAAAaSwAAHMsAACHLAAAmCwAAKwsAAC4LAAAyCwAANAsAADZLAAA5ywAAPQsAAAFLQAAFC0AABotAAAfLQAAJy0AAC4tAAA0",
    "LQAAOy0AAEctAABPLQAAXC0AAGktAAB1LQAAfy0AAIwtAACXLQAAni0AAKQtAACqLQAAsC0AALstAADFLQAAzC0AANktAADjLQAA",
    "6i0AAPMtAAD8LQAABi4AAA4uAAAWLgAAIC4AACguAAAuLgAAMy4AAEEuAABKLgAAUy4AAGAuAABmLgAAbC4AAHIuAAApAAAALgAA",
    "ADYAAAA8AAAASwAAAEwAAABNAAAATgAAAE8AAABQAAAAUQAAAFIAAABTAAAAVAAAAFUAAABWAAAAVwAAAFgAAABZAAAAWgAAAFsA",
    "AABcAAAAXQAAAF4AAABfAAAAYAAAAGEAAABiAAAAYwAAAGQAAABlAAAAZgAAAGcAAABoAAAAaQAAAGoAAABrAAAAbAAAAG0AAABu",
    "AAAAbwAAAHAAAABxAAAAcgAAAHMAAAB0AAAAdQAAAHYAAAB3AAAAeAAAAHkAAAB6AAAAewAAAHwAAAB9AAAAfgAAAH8AAACAAAAA",
    "gQAAAIIAAACDAAAAkwAAAKUAAACtAAAArgAAAK8AAACwAAAAsQAAALIAAACzAAAAtAAAACoAAAAAAAAAdDAAAC4AAAABAAAAAAAA",
    "ADYAAAACAAAAAAAAADcAAAACAAAAdDAAADgAAAACAAAAKDAAADkAAAACAAAAtC4AADoAAAACAAAAjC8AADsAAAACAAAAGC8AADkA",
    "AAACAAAAsC8AADkAAAACAAAA8DAAADkAAAACAAAAoC8AADwAAAADAAAAAAAAAEgAAAAHAAAAMC8AAEYAAAAKAAAAiC4AAD0AAAAL",
    "AAAAAAAAAEIAAAANAAAAuDAAAD0AAAAPAAAAAAAAAD0AAAAYAAAAAAAAAD0AAAAeAAAAAAAAAEAAAAAeAAAAdDAAAEUAAAAeAAAA",
    "oC8AAD0AAAAfAAAAAAAAAD0AAAAiAAAAAAAAAD0AAAAkAAAAAAAAAD0AAAAlAAAAAAAAAEkAAAAoAAAAfC4AAEoAAAAsAAAA6DAA",
    "AD0AAAAtAAAAAAAAAEEAAAAtAAAAKDAAAD0AAAAvAAAAAAAAAEUAAAAvAAAAxC4AAEAAAAAxAAAAdDAAAEUAAAAxAAAAoC8AAEgA",
    "AAAxAAAAsDAAAEUAAAAxAAAAxC4AAD0AAAAzAAAAAAAAAD8AAAAzAAAAEC8AAEEAAAAzAAAAKDAAAEUAAAAzAAAACC8AAEUAAAAz",
    "AAAAQC8AAEgAAAAzAAAAGC8AAEUAAAAzAAAA8DAAAEgAAAAzAAAAzC4AAEUAAAAzAAAAoC8AAEUAAAAzAAAAxC4AAEUAAAAzAAAA",
    "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",
    "FAEAAB4AAgAVAQAAHgA3ACgBAAAeADwANwEAAB4AOgBAAQAAHgA4AIIBAAAeADkAgwEAAB4AVACEAQAAHgBNAIUBAAAeADwAhgEA",
    "AB4APQCIAQAAHgBcAIgBAAAeADgAjAEAAB4AOQCNAQAAIQBQAMgAAAAhABMA8wAAACEAAgD0AAAAIQACAPsAAAAkAFcAxwAAACQA",
    "ZwAqAQAAJABXAHIBAAAlABIA+gAAACYAOwAlAAAAJwBEACUAAAAnAFgAiQEAACcAOQCKAQAAJwA4AIsBAAAoABkAPgEAACgANwCO",
    "AQAAKQA3ACUAAAApACMAvQAAACkAAwC/AAAAKQA5AMAAAAApAGkAwQAAACkAOQDCAAAAKQA+AMsAAAApAGcAzgAAACkANwDPAAAA",
    "KQBRANMAAAApAG4A1AAAACkACQDVAAAAKQApANgAAAApAGcA2gAAACkAQADeAAAAKQBOAO8AAAApAFMAFwEAACkAUwAcAQAAKQBS",
    "AB0BAAApAAYAJgEAACkAcQAsAQAAKQBtAC8BAAApAGsAMAEAACkAIwA1AQAAKQBnADgBAAApAFUAOgEAACkAYQA9AQAAKQA6AEIB",
    "AAApADkAQwEAACkABABEAQAAKQBqAEUBAAApAGsARgEAACkAZwBIAQAAKQBnAEwBAAApACwATgEAACkAUwBPAQAAKQA/AFQBAAAp",
    "AD4AVQEAACkAPgBWAQAAKQA+AFcBAAApAD8AWAEAACkAPgBZAQAAKQA+AFoBAAApAE4AWwEAACkAZwBcAQAAKQBOAF0BAAApAE4A",
    "XgEAACkAaQBfAQAAKQA5AGABAAApAHIAZQEAACkAZwBnAQAAKQBTAGgBAAApACMAawEAACkAbABuAQAAKQBnAG8BAAApAE4AcAEA",
    "ACkAKABzAQAAKQAmAHUBAAApAE4AeQEAACkATgB6AQAAKQBWAHsBAAApAFYAfAEAACkANwB9AQAAKQBTAI4BAAApAFMAjwEAACkA",
    "ZwCQAQAAKQBzAJIBAAApAEIAkwEAACkABwCXAQAAKQAIAJgBAAApADcAoQEAACkAOQCiAQAAKQBAAKUBAAApAAUArAEAACkAJwCx",
    "AQAAKQBPALMBAAAqAEIAJwEAACsARQAlAAAAKwBZAMUAAAArAEYA5gAAACsARwDmAAAAKwBIAOYAAAArAEkA5gAAACsAWgDnAAAA",
    "KwBeAOcAAAArAGAA5wAAACsAYwDnAAAAKwBbAOgAAAArAGQA6AAAACsAXwBRAQAALAAaALABAAAtAAIAOQEAAC0AHACaAQAALgBo",
    "ACsBAAAuAGgALgEAAC4AaAAyAQAALwAeAO4AAAAvAHYA9wAAAC8AdwD4AAAALwA2AAMBAAAvACMABQEAAC8AHQANAQAALwBvAC0B",
    "AAAwAAMAuwAAADEANwAlAAAAMQAdAPUAAAAzAAAA1wAAADMAbwDpAAAAMwACABkBAAAzAAIAOQEAADMAJAB0AQAAMwB1AJEBAAAz",
    "AHEAlAEAADMAJQCbAQAAMwAtAKMBAAAzACMArQEAADMAKwCwAQAAMwAuALABAAA0ADcAJQAAADQALwDKAAAANAAwAMoAAAA0ADIA",
    "ygAAADQAMwDKAAAANAA0AMoAAAA0ADUAygAAADQAMQDjAAAANAACADkBAAA0ACMApAEAADUACwDiAAAANQAKACQBAAA1AAsASQEA",
    "ADYAIwCkAQAAOAAgAPEAAAA4AAIABAEAADgAHQASAQAAOABiAIEBAAA5AB0A+QAAADkAAgAEAQAAOQAjAAUBAAA5AHQACQEAADkA",
    "HQALAQAAOQAhACkBAAA7ACoAUAEAACkAAAARAAAAMQAAAHwvAAArAAAAAAAAAItbAAAAAAAAKgAAABEAAAAxAAAAAAAAAC0AAAAA",
    "AAAAWl0AAAAAAAArAAAAEQAAAA4AAAAAAAAAjgAAAAAAAABkXQAAAAAAAAAAAgoKAAoKYWN0aXZpdHk9AAUKYXBwPQAICmRldmlj",
    "ZT0ACApoaWRkZW49ACsKdGltZS1iZWZvcmUtZnJhbWVzIChtaWNyb3NlY29uZHMpOiBmcmFtZXM9AAIgIAAPICBBRCBieSBsYWJl",
    "bCAnAFcgIEFEIGJ5IHN0cnVjdHVyZSAobm8ga25vd24gbGFiZWwgb3IgYnV0dG9uOiBvbmUgbGFiZWwgYW5kIGEgZG90LCBubyB0",
    "aW1lOyBzZWVuIHR3aWNlKQoAGyAgQUQgYnkgdGhlIGhpZGUtYWQgYnV0dG9uCgA4ICBhIHZpZGVvIHN1cmZhY2UgaXMgaW4gdGhp",
    "cyByb3c6IGl0cyBzb3VuZCBoYXMgdG8gc3RvcAoAFSAgYWQtYnV0dG9ucy1sZWFybmVkPQAKICBhbmRyb2lkPQAKICBhdmVyYWdl",
    "PQALICBjb2xsYXBzZT0ACCAgZmFpbHM9AA4gIGhpbnQtZXJyb3JzPQARICBsaXRoby10ZWxsaW5ncz0ACiAgbG9uZ2VzdD0ACSAg",
    "bm9sYW5nPQAbICBvcmRpbmFyeS1idXR0b25zLWxlYXJuZWQ9ABIgIHJlbWVtYmVyZWQtaGl0cz0ABiAgc2RrPQAmICBzaG9ydGVy",
    "LXRoYW4tMC41bXMvMS8yLzQvOG1zL2xvbmdlcj0AFCAgc291bmQtcGxheWluZy1ub3c9AA8gIHN0cnVjdC1oaWRlcz0ACSAgeGZh",
    "aWxzPQAGIGJhbmQ9AAogY2hpbGRyZW49AAMgaD0AByBoaWRkZW4ABCBscD0ALCByb3cocykgaGlkZGVuLiBMb2cgY29waWVkIHRv",
    "IHRoZSBjbGlwYm9hcmQuAAUgdG9wPQAFIHZpcz0ABCcgeT0ABjxpbml0PgABPwAHQUxQSEFfOAAXQkFDSyBPTiBhZnRlciB0aGUg",
    "cmVzdAoAAUMAAkNJAAlDb3JlLmphdmEABEVYQyAACkVudHJ5LmphdmEAAUYABUZBTFNFACZHSVZFIFVQIChubyB0ZXh0IGluIHRo",
    "ZSBoZWFkZXIpLCBzaG93bgAESElERQAXSElERSBieSByZW1lbWJlcmVkIGl0ZW0ACUhJTlQgZXhjIAAMSElOVCBtZXRob2QgABZI",
    "SU5UIG5vbmUsIGNhbmRpZGF0ZXM9AAFJAAJJSQADSUlJAAJJTAADSUxJAANJTEwAAUoAAUwAAkxDAANMQ0MAAkxJAANMSUkABExJ",
    "SUwABUxJU1QgAAJMSgACTEwAA0xMSQAETExJSQADTExMAARMTExJAAJMWgAWTGFuZHJvaWQvYXBwL0FjdGl2aXR5OwA0TGFuZHJv",
    "aWQvYXBwL0FwcGxpY2F0aW9uJEFjdGl2aXR5TGlmZWN5Y2xlQ2FsbGJhY2tzOwAZTGFuZHJvaWQvYXBwL0FwcGxpY2F0aW9uOwAa",
    "TGFuZHJvaWQvY29udGVudC9DbGlwRGF0YTsAIkxhbmRyb2lkL2NvbnRlbnQvQ2xpcGJvYXJkTWFuYWdlcjsAGUxhbmRyb2lkL2Nv",
    "bnRlbnQvQ29udGV4dDsAIExhbmRyb2lkL2NvbnRlbnQvcG0vUGFja2FnZUluZm87ACNMYW5kcm9pZC9jb250ZW50L3BtL1BhY2th",
    "Z2VNYW5hZ2VyOwAgTGFuZHJvaWQvZ3JhcGhpY3MvQml0bWFwJENvbmZpZzsAGUxhbmRyb2lkL2dyYXBoaWNzL0JpdG1hcDsAGUxh",
    "bmRyb2lkL2dyYXBoaWNzL0NhbnZhczsAGUxhbmRyb2lkL2dyYXBoaWNzL01hdHJpeDsAGExhbmRyb2lkL2dyYXBoaWNzL1BhaW50",
    "OwAXTGFuZHJvaWQvZ3JhcGhpY3MvUmVjdDsAGExhbmRyb2lkL2dyYXBoaWNzL1JlY3RGOwAcTGFuZHJvaWQvbWVkaWEvQXVkaW9N",
    "YW5hZ2VyOwAaTGFuZHJvaWQvb3MvQnVpbGQkVkVSU0lPTjsAEkxhbmRyb2lkL29zL0J1aWxkOwATTGFuZHJvaWQvb3MvQnVuZGxl",
    "OwAUTGFuZHJvaWQvb3MvSGFuZGxlcjsAE0xhbmRyb2lkL29zL0xvb3BlcjsAGExhbmRyb2lkL29zL1N5c3RlbUNsb2NrOwAaTGFu",
    "ZHJvaWQvdmlldy9TdXJmYWNlVmlldzsAGkxhbmRyb2lkL3ZpZXcvVGV4dHVyZVZpZXc7AC9MYW5kcm9pZC92aWV3L1ZpZXckT25B",
    "dHRhY2hTdGF0ZUNoYW5nZUxpc3RlbmVyOwAjTGFuZHJvaWQvdmlldy9WaWV3JE9uQ2xpY2tMaXN0ZW5lcjsAE0xhbmRyb2lkL3Zp",
    "ZXcvVmlldzsAJUxhbmRyb2lkL3ZpZXcvVmlld0dyb3VwJExheW91dFBhcmFtczsAK0xhbmRyb2lkL3ZpZXcvVmlld0dyb3VwJE1h",
    "cmdpbkxheW91dFBhcmFtczsAGExhbmRyb2lkL3ZpZXcvVmlld0dyb3VwOwAZTGFuZHJvaWQvdmlldy9WaWV3UGFyZW50OwAxTGFu",
    "ZHJvaWQvdmlldy9WaWV3VHJlZU9ic2VydmVyJE9uUHJlRHJhd0xpc3RlbmVyOwAfTGFuZHJvaWQvdmlldy9WaWV3VHJlZU9ic2Vy",
    "dmVyOwAVTGFuZHJvaWQvdmlldy9XaW5kb3c7AClMYW5kcm9pZC93aWRnZXQvRnJhbWVMYXlvdXQkTGF5b3V0UGFyYW1zOwAZTGFu",
    "ZHJvaWQvd2lkZ2V0L1RleHRWaWV3OwAWTGFuZHJvaWQvd2lkZ2V0L1RvYXN0OwAtTGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFj",
    "ZWJvb2svYWRoaWRlL0NvcmU7AC5MYXBwL3JldmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUvRW50cnk7ACxMYXBwL3Jl",
    "dmFuY2VkL2V4dGVuc2lvbi9mYWNlYm9vay9hZGhpZGUvUmVjOwATTGphdmEvbGFuZy9Cb29sZWFuOwAYTGphdmEvbGFuZy9DaGFy",
    "U2VxdWVuY2U7ABVMamF2YS9sYW5nL0NoYXJhY3RlcjsAEUxqYXZhL2xhbmcvQ2xhc3M7ABBMamF2YS9sYW5nL01hdGg7ABJMamF2",
    "YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcvUnVubmFibGU7ABJMamF2YS9sYW5nL1N0cmluZzsAGUxqYXZhL2xhbmcvU3RyaW5n",
    "QnVpbGRlcjsAEkxqYXZhL2xhbmcvU3lzdGVtOwAVTGphdmEvbGFuZy9UaHJvd2FibGU7ABBMamF2YS9sYW5nL1ZvaWQ7ABlMamF2",
    "YS9sYW5nL3JlZmxlY3QvRmllbGQ7ABpMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOwAbTGphdmEvdGV4dC9Ob3JtYWxpemVyJEZv",
    "cm07ABZMamF2YS90ZXh0L05vcm1hbGl6ZXI7ABJMamF2YS91dGlsL0xvY2FsZTsABU1PREVMAANORkMAAk9LAAlSRUxBWU9VVCAA",
    "B1JFTEVBU0UAB1JFTkRFUiAAJ1JFU1RJTkcgZm9yIDEwIHMgKDYgZmFpbHVyZXMgaW4gYSByb3cpCgAEUk9PVAAKUlYgaGlkZXI6",
    "IAAQUlYtSElERVIgMQp0aW1lPQAIUmVjLmphdmEAB1NES19JTlQACVRSRUUgZXhjIAAMVFJFRSBmaWVsZHM9AARUWVBFAAFWAAJW",
    "RgACVkkAA1ZJSQAEVklJSQAFVklJSUkAA1ZJTAACVkwABFZMRkYABVZMRkZMAANWTEkAB1ZMSUlGRkwAClZMSUlJSUZGWkwAA1ZM",
    "TAAEVkxMTAAFVkxMTEwAA1ZMWgACVloAAVoAAlpDAAJaSQADWklJAAJaTAADWkxKAANaTEwABFpMTEwAAltDAAJbRgACW0kAEltM",
    "amF2YS9sYW5nL0NsYXNzOwATW0xqYXZhL2xhbmcvT2JqZWN0OwATW0xqYXZhL2xhbmcvU3RyaW5nOwAaW0xqYXZhL2xhbmcvcmVm",
    "bGVjdC9GaWVsZDsAG1tMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOwA1W2hpZGUtYWQgYnV0dG9uIGxlYXJuZWQgZnJvbSB0aGUg",
    "YWRzIGp1ZGdlZCBieSBzaGFwZV0AEFtoaWRlLWFkIGJ1dHRvbl0AAlx8AAxfX1JWX0FEQlROX18ADV9fUlZfTEFCRUxTX18ADl9f",
    "UlZfUE9TVEJUTl9fAANhYnMABWFkQXJyAAlhZEJ1dHRvbnMABWFkQ250AAZhZEZpbmQACGFkRm9yZ2V0AAVhZEhhcwAHYWRMZWFy",
    "bgADYWROAAVhZFNldAADYWRkAB5hZGRPbkF0dGFjaFN0YXRlQ2hhbmdlTGlzdGVuZXIAFGFkZE9uUHJlRHJhd0xpc3RlbmVyAAdh",
    "ZGRWaWV3ACthcHAucmV2YW5jZWQuZXh0ZW5zaW9uLmZhY2Vib29rLmFkaGlkZS5Db3JlAAZhcHBlbmQABmF0dGFjaAAFYXVkaW8A",
    "BWJhZGdlAAdiYWRnZU9uAAliYWRnZVRleHQABGJhbmQAA2JtcAAMYm90dG9tTWFyZ2luAARjYW5kAARjZEluAAZjZEtpbmQAB2No",
    "YW5nZWQABmNoYXJBdAAFY2xlYW4ACWNsaXBib2FyZAAIY29sbGFwc2UAE2NvbS5mYWNlYm9vay5saXRoby4AIGNvbS5mYWNlYm9v",
    "ay5saXRoby5Db21wb25lbnRUcmVlABxjb20uZmFjZWJvb2subGl0aG8uTGl0aG9WaWV3AARjb3B5AAxjcmVhdGVCaXRtYXAABmN1",
    "clJvdwAHY3VycmVudAARY3VycmVudFRpbWVNaWxsaXMABmRlbGV0ZQAFZGlydHkABGRyYXcACmRyYXdCaXRtYXAACGRyYXdUZXh0",
    "AAtkcmF3VGV4dFJ1bgAGZXF1YWxzAAVmYWlscwAMZmluZFZpZXdCeUlkAA9maW5kVmlld1dpdGhUYWcABWZpcnN0AAdmb3JOYW1l",
    "AAZmb3JnZXQAAmZ5AANnZXQACWdldEJvdHRvbQAKZ2V0Q2hpbGRBdAANZ2V0Q2hpbGRDb3VudAAIZ2V0Q2xhc3MAFWdldENvbnRl",
    "bnREZXNjcmlwdGlvbgARZ2V0RGVjbGFyZWRGaWVsZHMAEmdldERlY2xhcmVkTWV0aG9kcwARZ2V0RGVjbGFyaW5nQ2xhc3MADGdl",
    "dERlY29yVmlldwAJZ2V0SGVpZ2h0AAVnZXRJZAAPZ2V0TGF5b3V0UGFyYW1zAAdnZXRMZWZ0ABNnZXRMb2NhdGlvbk9uU2NyZWVu",
    "AA1nZXRNYWluTG9vcGVyAAlnZXRNYXRyaXgAEWdldE1lYXN1cmVkSGVpZ2h0AAlnZXRNZXRob2QADGdldE1vZGlmaWVycwAHZ2V0",
    "TmFtZQAOZ2V0UGFja2FnZUluZm8AEWdldFBhY2thZ2VNYW5hZ2VyAA5nZXRQYWNrYWdlTmFtZQARZ2V0UGFyYW1ldGVyVHlwZXMA",
    "CWdldFBhcmVudAANZ2V0UmV0dXJuVHlwZQAIZ2V0UmlnaHQADWdldFN1cGVyY2xhc3MAEGdldFN5c3RlbVNlcnZpY2UABmdldFRh",
    "ZwAGZ2V0VG9wAA9nZXRUcmFuc2xhdGlvblgAB2dldFR5cGUAE2dldFZpZXdUcmVlT2JzZXJ2ZXIADWdldFZpc2liaWxpdHkACGdl",
    "dFdpZHRoAAlnZXRXaW5kb3cABGdpdmUAB2hhbmRsZXIACGhhc2hDb2RlAAJoaQAGaGlkZGVuAARoaWRlAARoaW50AAdoaW50QmFk",
    "AAhoaW50RG9uZQAHaGludEV4YwAFaGludE0ABWhpbnROAANoaXQAEGlkZW50aXR5SGFzaENvZGUABmluVGFpbAAHaW5zcGVjdAAH",
    "aW5zdGFsbAAKaW52YWxpZGF0ZQAGaW52b2tlAAdpc0FsaXZlAAdpc0RpZ2l0AAdpc0V4YWN0AAppc0luc3RhbmNlAAhpc0xldHRl",
    "cgAGaXNMaXN0AAdpc0xpdGhvAA1pc011c2ljQWN0aXZlAAxpc1doaXRlc3BhY2UABGtLZXkABGtWYWwABmxhYmVscwAIbGFzdExp",
    "c3QABmxheW91dAAKbGVhcm5lZEhpdAAGbGVuZ3RoAAhsaXN0UGFzcwACbG8AA2xvZwAGbG9nUm93AAhtYWtlVGV4dAAJbWFwUG9p",
    "bnRzAAdtZWFzdXJlAANtZW0ABm1lbUFkZAAGbWVtRGVsAAdtZW1GaW5kAAZtZW1IYXMABm1lbUhpdAAEbWVtTgAGbWVtb3J5AAhu",
    "YW5vVGltZQAMbmV3UGxhaW5UZXh0AAJubAAGbm9MYW5nAAVub1JlbAAEbm9ybQAGbm9ybWFsAAlub3JtYWxpemUABG5vdGUAA29m",
    "ZgAFb2ZmQXQAEW9uQWN0aXZpdHlDcmVhdGVkABNvbkFjdGl2aXR5RGVzdHJveWVkABBvbkFjdGl2aXR5UGF1c2VkABFvbkFjdGl2",
    "aXR5UmVzdW1lZAAbb25BY3Rpdml0eVNhdmVJbnN0YW5jZVN0YXRlABFvbkFjdGl2aXR5U3RhcnRlZAARb25BY3Rpdml0eVN0b3Bw",
    "ZWQAB29uQ2xpY2sACW9uUHJlRHJhdwAWb25WaWV3QXR0YWNoZWRUb1dpbmRvdwAYb25WaWV3RGV0YWNoZWRGcm9tV2luZG93AAZv",
    "cmRIYXMACG9yZExlYXJuAARvcmROAAZvcmRTZXQABXBhaXJBAAVwYWlyQgAJcGFpckNoZWNrAAZwYWlyT2sABHBhc3MABHBlbmQA",
    "B3BlbmRpbmcAB3Bvc3RBcnIAC3Bvc3RCdXR0b25zAAtwb3N0RGVsYXllZAAicmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxi",
    "YWNrcwAGcmVsUG9zAAhyZWxheW91dAAIcmVtZW1iZXIAD3JlbW92ZUNhbGxiYWNrcwAXcmVtb3ZlT25QcmVEcmF3TGlzdGVuZXIA",
    "BnJlbmRlcgAHcmVwbGFjZQAGcmVwb3J0AAlyZXN0b3JpbmcAAnJrAAJybgAHcm93RmFpbAAFcm93T2sAB3Jvd1Bhc3MACHJvd1Bh",
    "c3MwAANydW4ACHJ2LWhpZGVyAAJyeQAEc2VlbgANc2V0QWNjZXNzaWJsZQAIc2V0QWxwaGEAEnNldEJhY2tncm91bmRDb2xvcgAP",
    "c2V0TGF5b3V0UGFyYW1zABJzZXRPbkNsaWNrTGlzdGVuZXIACnNldFBhZGRpbmcADnNldFByaW1hcnlDbGlwAAZzZXRUYWcAB3Nl",
    "dFRleHQADHNldFRleHRDb2xvcgALc2V0VGV4dFNpemUAD3NldFRyYW5zbGF0aW9uWAANc2V0VmlzaWJpbGl0eQAEc2hvdwADc2ln",
    "AAZzbW9vdGgABXNwbGl0AARzdE9mAAVzdGFydAAKc3RhcnRzV2l0aAAGc3RyZWFrAAtzdHJ1Y3RIaWRlcwALc3RydWN0SnVkZ2UA",
    "CnN0cnVjdEtpbmQACHN0cnVjdE9rAAtzdWJTZXF1ZW5jZQAJc3Vic3RyaW5nAAV0SGlzdAAEdE1heAAEdFN1bQAEdGFpbAAJdGFp",
    "bEZyYW1lAAh0YWlsUG9zdAAFdGltZWQAC3RvTG93ZXJDYXNlAAh0b1N0cmluZwAFdG9hc3QAB3RyZWVCYWQAB3RyZWVDbHMACHRy",
    "ZWVEb25lAAZ0cmVlRjEABnRyZWVGMgAIdHJlZUhpdHMABnRyZWVJZAAEdHJpbQADdHh0AAx1cHRpbWVNaWxsaXMAB3ZhbHVlT2YA",
    "B3ZlcnNpb24AC3ZlcnNpb25OYW1lAAR3YWxrAAR3YW50AAR3aW5XAAZ4ZmFpbHMAAAADAAAACQAtAAIAAAACAAAAMwACAAkAAAA/",
    "AAIAAgACAAIAAQABAD4AEAAAAAMAAAACAAIAAgAAAAEAAAAeAAAAAQAAAAYAAAABAAAAMwAAAAIAAAAtADoABgAAAD8AAgACAAEA",
    "AQAQAAQAAAANAAEAAQAQAAIAAAAyAAMAAgAAACEAHgABAAAAGAAAAAEAAAAEAAAAAgAAAAAAAAACAAAAHgArAAEAAAA8AAAAAQAA",
    "ACMAAAACAAAALQAtAAEAAAAFAAAAAQAAAAkAAAABAAAAHwAAAAIAAAAtAEQAAwAAAA0ADwAQAAAAAgAAAB4APgAGAAAAMwACAAIA",
    "AQABABAABQAAAAUAIwAyAB0AHAAAAAIAAAAeAAIABAAAADMAAQABABAAAQAAADEAAAACAAAAHgBBAAEAAAArAAAAAgAAAAIAMQAB",
    "AAAAHQAAAAkAAAAtAAIAAgACAAIAAQABAD4AEAAAAAIAAAAeAB8AAwAAAC0AAQABAAAAAgAAAAQAMwAEAAAADQARABIAEAABAAAA",
    "MgAAAAEAAAAOAAAABgAAAC0AAgACAAEAAQAQAAIAAAACAAIAAQAAAAAAAAADAAAAHgAtAEEAAAADAAAAHgAeAEEAAAAEAAAADQAR",
    "ABEAEAABAAAAAQAAAAEAAAAhAAAAAgAAADMAQgABAAAAAgAAAAEAAAADAAAAAQAAAEAAAAABAAAAHAAAAAEAAABBAAAAAgAAAAQA",
    "FgADAAAAPwACAAIAAAACAAAAMQBDAAMAAAACAAIADAAAAAQAAAACAAIAAgACAAEAAAAHAAAAAgAAADMAHgABAAAADQAAAAEAAAA+",
    "AAAAAQAAAC0AAAAAAAAABAABAAIAAAAAAAAAWQAAAHAQqgADACIAFwBxABIAAAAMAXAgDwAQAFswEgAiADQAcBC4AAAAWzAdABMA",
    "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",
    "jQBuIL0AEABxAMIAAAALAm4wuwAgAxoBBABuIL0AEABiAQQAbiC9ABAAGgENAG4gvQAQAGIBAgBuIL0AEAAaARcAbiC9ABAAYAED",
    "AG4gugAQABoBAwBuIL0AEABuEAYABgAMAW4gvQAQABoBBwBuIL0AEABxEIwABgAMAW4gvQAQABoBAgBuIL0AEABuEKsABgAMAW4Q",
    "pgABAAwBbiC9ABAAGgEFAG4gvQAQAFJREwBuILoAEAAaARAAbiC9ABAAUlERAG4gugAQABoBGwBuIL0AEABSUTsAbiC6ABAAGgER",
    "AG4gvQAQAFJRFgBuILoAEAAaARYAbiC9ABAAUlE5AG4gugAQABoBDwBuIL0AEABxAE8AAAAKAW4gvgAQABoBFABuIL0AEABVUSAA",
    "biC+ABAAGgEaAG4gvQAQAFJRLQBuILoAEAAaARUAbiC9ABAAUlEkAG4gugAQABoBDABuIL0AEABSUQgAbiC6ABAAGgEGAG4gvQAQ",
    "AFRRLwASAhIDEmQ1QggARAQBArBD2AICASj4biC6ADAAGgIOAG4gvQAgABICPQMFAFJSMQCzMm4gugAgABoCEwBuIL0AIABSUjAA",
    "biC6ACAAGgIYAG4gvQAgABICEmQ1Qg8ARAQBAm4gugBAABMELwBuILkAQADYAgIBKPEaAhIAbiC9ACAAUlIYAG4gugAgABoBGQBu",
    "IL0AEAAaAcwAbiAHABYADAEfARMAbhAOAAEACgFuIL4AEAAaAQEAbiC9ABAAVFEdAG4gvAAQAG4QwQAAAAwAEQANARoBJgBuIL0A",
    "EAAo6w8BAAAPAAEAAQCtAgYAAgACAAAAAAAAABwAAABSQBEA2AAAAVlAEQAUAAGg8H9uICEABQAMASASQQA4AgwAHwFBABMCDABE",
    "AwEC2AMDAUsDAQIOAAYAAgACAAAAAAAAABYAAAASAFlAEQAUAAGg8H9uICEABQAMASASQQA4AgkAHwFBABMCDAASA0sDAQIOAAcA",
    "AwADAAAAAAAAACoAAABwMH8AVAYUAAGg8H9uICEABgAMASASQQA4Ah0AHwFBABMACwBEAgEAEjMzMhQAEwIKAEQCAQI5Ag4AEnJE",
    "AgECOQIJABICSwIBABIScDBUAGQCDgAOAAMAAwAAAAAAAAAmAgAAFAABoPB/biAhAA0ADAEgEkEAOQIJABMCEwAjIUEAbjAvAA0B",
    "HwFBAFWyKwA4AgYAcDCBANsBDgBxABMAAAALAoQiEiREAwEEOQMEAEsCAQQTAxwAIzNBAFu9DgBwMIIA2wNVtCAAOAQlAHAgcwA7",
    "AAoEEgc4BBwAEihECAMIOQgXABI4RAgDCDkIEgASSEQIAwg5CAQAEhcoCnAQWgALAAoIOAgFABIoSwgDCFy3LgASBEQFAwTaBQUf",
    "EhREBgMEsGUSJEQGAwQSFEQHAQQSGDOHFQA5BgwAbhAZAA0ACggTCQgARAkBCTKYHgASB0sHAQQSJEsCAQQoFxIoM4cVABIIRAgB",
    "CDJYDAASCEsFAQgSR0sHAQQSJEsCAQQoBXAwUwDbAQ4AEghbuAsAOQZdABJIModaAG4QFwANAAoIPQhUAG4QIgANAAoIbhA2AAwA",
    "Cgk1mEoAcCBhANsACgg4CEQAElhECQEIOQkIABIZSwkBCG4gFAC9ABJIEhlLCAEJEghLBQEIEihLAgEIbhAZAA0ACggTCQgASwgB",
    "CRMICwBECQEIOQkJABIpSwkBCBMIDQBLAgEIcDBTANsBUrg5ANgICAFZuDkAUrgTANgICAFZuBMAcBBKAAsAGggyAHAwXACLDQ4A",
    "OQYoABJIRAgDCDkICwASOEQIAwg5CB4AVbguADkIGgASSDOHBgBwMFMA2wEOABIoSwIBCBJ4RAkBCDgJCgASCUsJAQgVCoA/biAq",
    "AK0ADgASWEQJAQg5CQgAEhlLCQEIbiAUAL0AbhAZAA0ACggTCQgASwgBCTkGswBuECIADQAKCG4QJgANAAoJ2goJGdsKCmSwim4Q",
    "NgAMAAoEPQqJADVIhwA6CHIANkpwABIEcDBVANsECgQSKDKEkgASGDOEOwATCAkARAkBCDkJEwBLAgEIEkgzhwYAcDBTANsBDgBw",
    "MGUA2wESOBIUSwgBBA4AkQkCCRMKCAA1qQMADgBwMIEA2wESSDOHBQBwIFEA2wASGBIUSwgBBBIESwUBBBMECQASCEsIAQQaCIYA",
    "cDBcAIsNDgASSDOHBgBwMFMA2wEOABIoRAgBCJEIAggTBCwBN0gSAHAwgQDbARIYEhRLCAEEEgRLBQEEGggwAHAwXACLDQ4AcDB1",
    "ANsBUrgpANgICAFZuCkADgASZEQIAQSRCAIIEwlQADSYDABLAgEEEgRwMFUA2wQKBBIoMoQZABIoSwIBCBMICQASCUsJAQgSSDOH",
    "BgBwMFMA2wEOAHAwZQDbARI4EhRLCAEEDgASSDKHKgA4BgsAGgi2ABIpM5YEABoItQBbuAsAcCB5ANsAGggxAHAwXACLDRJIRAgD",
    "CDgICQBUuB0AGgkLAG4gvQCYAFK4EwDYCAgBWbgTAHAQSgALABMICwBECQEIEiozqQUAEglLCQEIcDBTANsBEwgJABIJSwkBCBIo",
    "EhRLCAEEEgRLBQEEDgAHAAMAAwAAAAAAAAAUAAAAEwALAEQBBgA4AQwAEgJLAgYAEiIyIQYAEhJwMFQAVAJwMFIAVAYOAAgAAwAE",
    "AAAAAAAAAFwAAAA4BlsAEhBEAQcA2AEBAUsBBwATAtAHNiFQACBiGwA5AgYAIGIaADgCBQASQksABwJuEBgABgAMAjgCKABwIE0A",
    "JQAKAzgDCwAzAwYAEiRLAAcEKAQSNEsABwRVUyAAOAMFAHBASwBlcnEQtgACAAwCbhCuAAIACgISA0QEBwPaBAQfsCRLBAcDIGIh",
    "ADgCFQAfBiEAbhA1AAYACgISAzUjDABuIDQANgAMBHAwggBFB9gDAwEo9Q4AAQAAAAAAAAAAAAAAAgAAABIQDwAEAAEAAgAAAAAA",
    "AAAQAAAAFAABoPB/biAhAAMADAEgEkEAOAIFAB8BQQARARIAEQACAAEAAgAAAAAAAAAJAAAAIgApAHAQQgAAAG4gAgABAA4AAAAP",
    "AAMAAgAAAAAAAACvAAAAcCCHAOwACgASITMQGABVwSgAOAGZAFLBJgBwIHIAHABwIEUAHABSwScAcCByABwAcCBFABwAKQCHABIR",
    "MxCEAFXBLgA4AYAAUsEmAHAgcQAcAAoBOAEXAFLBJwBwIHEAHAAKATgBDwBxEIQADQAMATgBBwATAg4AEgNLAwECEhAPAHEQhAAN",
    "AAwBOQEEABIADwBU4kUAbhDBAAIADAJuEK4AAgAKAjkCAwASEnEAEwAAAAsDhDM5AwMAEhMTBA4ARAUBBBMGDwBEBwEGOAU2ADMn",
    "NACRCAMFEwkIADSYMgBUyB0AGgkJAG4gvQCYAFLILQDYCAgBWcgtABMIEABECQEIOQkZAFLJJgBwIHEAnAAKClLLJwBwIHEAvAAK",
    "AjIqCwASE0sDAQg4CgMAAblwIEcAnAASIA8ASwMBBEsCAQYSAA8AcRCEAA0ADAE4AQcAEwIOABIDSwMBAhLwDwAAAA4AAgABAAAA",
    "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",
    "CgAAAHEwtwBDBQwAcECQAAKYDgANACj+AAAAAAcAAQABAAgANkAMBgIBAgECAQIBAgECAQIBAgECAQIBAgECARIBAgECAQIBAgEC",
    "AQIBAgECAQIBAgESAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECAQIBAgECQoKA",
    "BPxhAQnAYwEC2GMBApRkAQK8ZAEC/GQBAthlAQmMZwECoGcBAvBnAQnsaQECzGoBCfBrAQnAbAEK1GwBArRtAQLYbQECuG4BAvBw",
    "AQL8cwEJiHcBCqx4AQrMeQEJhHoBApx6AQLUegEC9HwBApx+AQLofgECqH8BAuB/AQKsgAEBCeSAAQEJ+IABAQmMgQEBArSCAQwC",
    "kIMBAQLQgwEBAoyEAQECqIUBAQLgiAEBCYyJAQEKpIkBAQKMigEBAtSLAQEKgIwBAQK0jAEBAriRAQECgJIBAQK8kgEBAqCTAQIC",
    "/JsBAQK0nAEBCfydAQEKkJ4BAQnAngEBAuSeAQEC1KEBAQKsowEBAvijAQEKgKUBAQK0pQEBCpioAQEC3KgBZgHMqQEBAeCpAQEB",
    "9KkBAQHYqgEBAdyrAQEB8KsBAQGErAEBAZisAQEBiK0BAQG8rQEBAdCtARAB1K4BAAABAI4BCZyvAQALAwo8AQEBAQEBAQEBAREB",
    "AQERAQEBEQEBjwGBgAT8rwEBArywAQsC+LEBkQEBpLQBAQG4tAEBAcy0AQEB4LQBAQH0tAEBAaS1AQEBzLUBAQH8tQEBAay2AQEB",
    "3LYBAA0AAAAAAAAAAQAAAAAAAAABAAAAtwEAAHAAAAACAAAARwAAAEwHAAADAAAAeAAAAGgIAAAEAAAATAAAAAgOAAAFAAAA0QAA",
    "AGgQAAAGAAAAAwAAAPAWAAACIAAAtwEAAFAXAAABEAAAQQAAAHwuAAADEAAAAQAAAPgwAAABIAAAWgAAAPwwAAAAIAAAAwAAAItb",
    "AAAAEAAAAQAAAMRdAAA=",
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
        "An option (off by default) can remember the ads it has hidden, so scrolling back up does not show them again. " +
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
        name = "Hide again when scrolling up",
        description = "EXPERIMENTAL. Remembers the ads that were hidden (not their content: only a number that identifies the " +
            "item and the row height) so that an ad you scroll back up to is hidden at once instead of showing for a moment " +
            "until its header is on the screen. Off by default: turn it on to test. If scrolling stalls or posts do not load, turn it off.",
        default = false,
    )

    val noDependLanguage by booleanOption(
        name = "No depend language",
        description = "Also recognises a Sponsored post by the SHAPE of its header instead of by its words, for the cases where the app " +
            "is in a language whose \"Sponsored\" label and \"Hide ad\" / \"Hide post\" button texts are not in this patch's lists. " +
            "It only looks at a post that has the two header buttons (\"More options\" and \"Hide\" next to each other at the top right) " +
            "and no video, whose first header line is exactly one short label followed by a dot (\"label \u00b7\") and shows NO time or " +
            "number; it has to see that twice before it hides the post, and it never hides a post whose buttons were seen on ordinary " +
            "posts. In a language that is in the lists nothing changes (the lists decide). It does not hide \"Suggested for you\" or " +
            "paid partnership posts (those need the lists). A Sponsored post with a video is never drawn, so it cannot be read: it is hidden " +
            "only after two earlier Sponsored posts of your language have shown which button text means \"Hide ad\" (from the third ad on). " +
            "Off by default: it is a safety net that was only tested in a simulation. Turn it on if the Sponsored posts of your language are not hidden.",
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
        if (noDependLanguage != true) {
            val m = coreMethods.firstOrNull { it.name == "noLang" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
                ?: throw PatchException("Hide native feed ads: Core.noLang() not found")
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
                "suggested+partnership=${hideSuggested != false}, remember=${rememberAds == true}, no-depend-language=${noDependLanguage == true}, badge=${showBadge == true}",
        )
    }
}
