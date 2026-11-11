package app.revanced.patches.facebook.misc.userscript

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
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
 * Inject userscript to block sponsored posts on android 8 apk app
 *
 * ONE self-contained file. It adds NO file to the apk and touches NO resource: no asset, no res/, no new dex, no
 * library, and not even the manifest. This is on purpose: a patch that edits the manifest (or any resource) makes
 * the patcher decode ALL resources and rebuild them with aapt2, which breaks the recompile of this apk. Everything is
 *   1. one small class, "Injector", added to the dex the app already has (classes.dex),
 *   2. the userscript, the start url and the user agent, stored as strings inside that class,
 *   3. one call at the top of the Application.onCreate() that already exists.
 * It has no resource patch and no dependency, so the patcher does not decode resources for it.
 *
 * What it does at run time (inside the app process):
 *   a. START PAGE (idea taken from the "Cobalt-Start-URL aendern" patch of the YouTube TV bundle, which repoints the
 *      app at another url): when the main screen of the app (FbMainTabActivity, the target of the launcher alias in
 *      the manifest) or one of its login / sign-up screens (class names under com.facebook. that contain "Login" or
 *      "Registration") is resumed, a WebView with https://m.facebook.com/ and a phone user agent (Chrome on Android)
 *      is laid over it. Covering the login screens too means the log in is done ONCE, on the page, and the native
 *      screens behind it never get a session (no feed, no video, no sound of their own). Back goes back inside the
 *      page first. Links that are not http(s) (fb://, intent://) are ignored.
 *   b. USERSCRIPT: while an activity is resumed, every 1.5 s the view tree is walked, and every android.webkit.WebView
 *      found (the start page, the in-app browser) runs the userscript with evaluateJavascript(); the page-finished
 *      callback of the start page runs it too. The first line of the userscript makes every repeat a no-op and the
 *      script only acts on facebook.com / fb.com pages. The script hides Sponsored / suggested posts (also on the
 *      phone layout), removes the "Open app" bar in every language and gives its room back to the page.
 *   No hidden Facebook class is touched, so nothing depends on the obfuscated names of a build.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * LIMIT: the native News Feed of the app is not a web page, so the userscript cannot touch it. That is why (a)
 * exists: the feed the user reads is then the web feed, which the userscript can filter. An account that is already
 * logged in natively keeps its native session behind the page (log out / clear the app data once to avoid that).
 *
 * ---- Injector.smali (assembled with smali; the Base64 further down contains it) ---------------------------------------
 * # ===== Injector.smali =====
 * .class public final Lapp/revanced/extension/facebook/userscript/Injector;
 * .super Landroid/webkit/WebViewClient;
 * .source "Injector.java"
 *
 * # 1. Opens the start page (default m.facebook.com, phone user agent) in a WebView laid over the app's main screen.
 * # 2. Runs the userscript in every WebView the app shows (that one, and the in-app browser).
 * .implements Landroid/app/Application$ActivityLifecycleCallbacks;
 * .implements Landroid/view/View$OnKeyListener;
 * .implements Ljava/lang/Runnable;
 *
 * .field private static lastUrl:Ljava/lang/String;
 *
 * .field private final handler:Landroid/os/Handler;
 * .field private current:Landroid/app/Activity;
 *
 * .method private constructor <init>()V
 *     .locals 2
 *     invoke-direct {p0}, Landroid/webkit/WebViewClient;-><init>()V
 *     new-instance v0, Landroid/os/Handler;
 *     invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;
 *     move-result-object v1
 *     invoke-direct {v0, v1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V
 *     iput-object v0, p0, Lapp/revanced/extension/facebook/userscript/Injector;->handler:Landroid/os/Handler;
 *     return-void
 * .end method
 *
 * # Called first thing from Application.onCreate().
 * .method public static install(Landroid/app/Application;)V
 *     .locals 2
 *     :try_start_0
 *     new-instance v0, Lapp/revanced/extension/facebook/userscript/Injector;
 *     invoke-direct {v0}, Lapp/revanced/extension/facebook/userscript/Injector;-><init>()V
 *     invoke-virtual {p0, v0}, Landroid/app/Application;->registerActivityLifecycleCallbacks(Landroid/app/Application$ActivityLifecycleCallbacks;)V
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :goto_0
 *     return-void
 *     :catch_0
 *     move-exception v0
 *     goto :goto_0
 * .end method
 *
 * # The three methods below get their text from the patch (userscript, start url, user agent).
 * .method public static script()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "__RV_SCRIPT__"
 *     return-object v0
 * .end method
 *
 * .method public static startUrl()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "__RV_START_URL__"
 *     return-object v0
 * .end method
 *
 * .method public static userAgent()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "__RV_USER_AGENT__"
 *     return-object v0
 * .end method
 *
 * .method private static inject(Landroid/webkit/WebView;)V
 *     .locals 2
 *     :try_start_0
 *     invoke-static {}, Lapp/revanced/extension/facebook/userscript/Injector;->script()Ljava/lang/String;
 *     move-result-object v0
 *     const/4 v1, 0x0
 *     invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :goto_0
 *     return-void
 *     :catch_0
 *     move-exception v0
 *     goto :goto_0
 * .end method
 *
 * .method private static newWebView(Landroid/app/Activity;Lapp/revanced/extension/facebook/userscript/Injector;)Landroid/webkit/WebView;
 *     .locals 4
 *     new-instance v0, Landroid/webkit/WebView;
 *     invoke-direct {v0, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V
 *     const-string v1, "rv-web"
 *     invoke-virtual {v0, v1}, Landroid/webkit/WebView;->setTag(Ljava/lang/Object;)V
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
 *     move-result-object v1
 *     const/4 v2, 0x1
 *     invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V
 *     invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V
 *     invoke-static {}, Lapp/revanced/extension/facebook/userscript/Injector;->userAgent()Ljava/lang/String;
 *     move-result-object v3
 *     invoke-virtual {v3}, Ljava/lang/String;->length()I
 *     move-result v2
 *     if-eqz v2, :cond_no_ua
 *     invoke-virtual {v1, v3}, Landroid/webkit/WebSettings;->setUserAgentString(Ljava/lang/String;)V
 *     :cond_no_ua
 *     invoke-virtual {v0, p1}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V
 *     invoke-virtual {v0, p1}, Landroid/webkit/WebView;->setOnKeyListener(Landroid/view/View$OnKeyListener;)V
 *     const/4 v2, 0x1
 *     invoke-virtual {v0, v2}, Landroid/webkit/WebView;->setFocusableInTouchMode(Z)V
 *     invoke-static {}, Landroid/webkit/CookieManager;->getInstance()Landroid/webkit/CookieManager;
 *     move-result-object v3
 *     invoke-virtual {v3, v0, v2}, Landroid/webkit/CookieManager;->setAcceptThirdPartyCookies(Landroid/webkit/WebView;Z)V
 *     return-object v0
 * .end method
 *
 * # Lays the start page over the main screen, once per activity instance.
 * .method private attach(Landroid/app/Activity;)V
 *     .locals 5
 *     :try_start_0
 *     invoke-static {}, Lapp/revanced/extension/facebook/userscript/Injector;->startUrl()Ljava/lang/String;
 *     move-result-object v0
 *     invoke-virtual {v0}, Ljava/lang/String;->length()I
 *     move-result v4
 *     if-eqz v4, :done
 *     const v4, 0x1020002
 *     invoke-virtual {p1, v4}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
 *     move-result-object v1
 *     instance-of v4, v1, Landroid/view/ViewGroup;
 *     if-eqz v4, :done
 *     check-cast v1, Landroid/view/ViewGroup;
 *     const-string v4, "rv-web"
 *     invoke-virtual {v1, v4}, Landroid/view/View;->findViewWithTag(Ljava/lang/Object;)Landroid/view/View;
 *     move-result-object v4
 *     if-nez v4, :done
 *     invoke-static {p1, p0}, Lapp/revanced/extension/facebook/userscript/Injector;->newWebView(Landroid/app/Activity;Lapp/revanced/extension/facebook/userscript/Injector;)Landroid/webkit/WebView;
 *     move-result-object v2
 *     sget-object v3, Lapp/revanced/extension/facebook/userscript/Injector;->lastUrl:Ljava/lang/String;
 *     if-eqz v3, :use_start
 *     move-object v0, v3
 *     :use_start
 *     new-instance v3, Landroid/widget/FrameLayout$LayoutParams;
 *     const/4 v4, -0x1
 *     invoke-direct {v3, v4, v4}, Landroid/widget/FrameLayout$LayoutParams;-><init>(II)V
 *     invoke-virtual {v1, v2, v3}, Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V
 *     invoke-virtual {v2, v0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
 *     invoke-virtual {v2}, Landroid/view/View;->requestFocus()Z
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :done
 *     return-void
 *     :catch_0
 *     move-exception v0
 *     goto :done
 * .end method
 *
 * .method private static scan(Landroid/view/View;)V
 *     .locals 4
 *     invoke-virtual {p0}, Landroid/view/View;->getVisibility()I
 *     move-result v0
 *     const/16 v1, 0x8
 *     if-eq v0, v1, :cond_end
 *     instance-of v0, p0, Landroid/webkit/WebView;
 *     if-eqz v0, :cond_group
 *     check-cast p0, Landroid/webkit/WebView;
 *     invoke-static {p0}, Lapp/revanced/extension/facebook/userscript/Injector;->inject(Landroid/webkit/WebView;)V
 *     return-void
 *     :cond_group
 *     instance-of v0, p0, Landroid/view/ViewGroup;
 *     if-eqz v0, :cond_end
 *     check-cast p0, Landroid/view/ViewGroup;
 *     invoke-virtual {p0}, Landroid/view/ViewGroup;->getChildCount()I
 *     move-result v0
 *     const/4 v1, 0x0
 *     :loop
 *     if-ge v1, v0, :cond_end
 *     invoke-virtual {p0, v1}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
 *     move-result-object v2
 *     if-eqz v2, :cond_next
 *     invoke-static {v2}, Lapp/revanced/extension/facebook/userscript/Injector;->scan(Landroid/view/View;)V
 *     :cond_next
 *     add-int/lit8 v1, v1, 0x1
 *     goto :loop
 *     :cond_end
 *     return-void
 * .end method
 *
 * .method public run()V
 *     .locals 3
 *     iget-object v0, p0, Lapp/revanced/extension/facebook/userscript/Injector;->current:Landroid/app/Activity;
 *     if-nez v0, :cond_go
 *     return-void
 *     :cond_go
 *     :try_start_0
 *     invoke-virtual {v0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
 *     move-result-object v1
 *     invoke-virtual {v1}, Landroid/view/Window;->getDecorView()Landroid/view/View;
 *     move-result-object v1
 *     invoke-static {v1}, Lapp/revanced/extension/facebook/userscript/Injector;->scan(Landroid/view/View;)V
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :goto_0
 *     iget-object v0, p0, Lapp/revanced/extension/facebook/userscript/Injector;->handler:Landroid/os/Handler;
 *     const-wide/16 v1, 0x5dc
 *     invoke-virtual {v0, p0, v1, v2}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z
 *     return-void
 *     :catch_0
 *     move-exception v0
 *     goto :goto_0
 * .end method
 *
 * # ---- WebViewClient (only set on the WebView this class creates) -------------------------------------------------------
 *
 * # Anything that is not http(s) (fb://, intent://, market://) would end in an error page: ignore it.
 * .method public shouldOverrideUrlLoading(Landroid/webkit/WebView;Ljava/lang/String;)Z
 *     .locals 2
 *     if-nez p2, :cond_check
 *     const/4 v0, 0x1
 *     return v0
 *     :cond_check
 *     const-string v0, "http"
 *     invoke-virtual {p2, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
 *     move-result v0
 *     xor-int/lit8 v0, v0, 0x1
 *     return v0
 * .end method
 *
 * .method public onPageCommitVisible(Landroid/webkit/WebView;Ljava/lang/String;)V
 *     .locals 0
 *     invoke-static {p1}, Lapp/revanced/extension/facebook/userscript/Injector;->inject(Landroid/webkit/WebView;)V
 *     return-void
 * .end method
 *
 * .method public onPageFinished(Landroid/webkit/WebView;Ljava/lang/String;)V
 *     .locals 0
 *     sput-object p2, Lapp/revanced/extension/facebook/userscript/Injector;->lastUrl:Ljava/lang/String;
 *     invoke-static {p1}, Lapp/revanced/extension/facebook/userscript/Injector;->inject(Landroid/webkit/WebView;)V
 *     return-void
 * .end method
 *
 * # ---- View.OnKeyListener: Back goes back inside the page, then leaves the app as usual -----------------------------------
 *
 * .method public onKey(Landroid/view/View;ILandroid/view/KeyEvent;)Z
 *     .locals 3
 *     const/4 v0, 0x0
 *     const/4 v1, 0x4
 *     if-ne p2, v1, :cond_out
 *     invoke-virtual {p3}, Landroid/view/KeyEvent;->getAction()I
 *     move-result v1
 *     const/4 v2, 0x1
 *     if-ne v1, v2, :cond_out
 *     instance-of v1, p1, Landroid/webkit/WebView;
 *     if-eqz v1, :cond_out
 *     check-cast p1, Landroid/webkit/WebView;
 *     invoke-virtual {p1}, Landroid/webkit/WebView;->canGoBack()Z
 *     move-result v1
 *     if-eqz v1, :cond_out
 *     invoke-virtual {p1}, Landroid/webkit/WebView;->goBack()V
 *     const/4 v0, 0x1
 *     :cond_out
 *     return v0
 * .end method
 *
 * # The screens the page is laid over: the main screen, and the login / sign-up screens (so that the log in is done once, on the
 * # page, and the native screens behind it never get a session). Class names of the app, matched by a word they contain.
 * .method private static isHost(Ljava/lang/String;)Z
 *     .locals 2
 *     const-string v0, "com.facebook."
 *     invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
 *     move-result v1
 *     if-eqz v1, :no
 *     const-string v0, "FbMainTabActivity"
 *     invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
 *     move-result v1
 *     if-nez v1, :yes
 *     const-string v0, "Login"
 *     invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
 *     move-result v1
 *     if-nez v1, :yes
 *     const-string v0, "Registration"
 *     invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
 *     move-result v1
 *     if-nez v1, :yes
 *     :no
 *     const/4 v0, 0x0
 *     return v0
 *     :yes
 *     const/4 v0, 0x1
 *     return v0
 * .end method
 *
 * # ---- Application.ActivityLifecycleCallbacks -----------------------------------------------------------------------------
 *
 * .method public onActivityResumed(Landroid/app/Activity;)V
 *     .locals 3
 *     iput-object p1, p0, Lapp/revanced/extension/facebook/userscript/Injector;->current:Landroid/app/Activity;
 *     invoke-virtual {p1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
 *     move-result-object v0
 *     invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
 *     move-result-object v0
 *     invoke-static {v0}, Lapp/revanced/extension/facebook/userscript/Injector;->isHost(Ljava/lang/String;)Z
 *     move-result v0
 *     if-eqz v0, :cond_not_main
 *     invoke-direct {p0, p1}, Lapp/revanced/extension/facebook/userscript/Injector;->attach(Landroid/app/Activity;)V
 *     :cond_not_main
 *     iget-object v0, p0, Lapp/revanced/extension/facebook/userscript/Injector;->handler:Landroid/os/Handler;
 *     invoke-virtual {v0, p0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V
 *     const-wide/16 v1, 0x320
 *     invoke-virtual {v0, p0, v1, v2}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z
 *     return-void
 * .end method
 *
 * .method public onActivityPaused(Landroid/app/Activity;)V
 *     .locals 2
 *     iget-object v0, p0, Lapp/revanced/extension/facebook/userscript/Injector;->handler:Landroid/os/Handler;
 *     invoke-virtual {v0, p0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V
 *     const/4 v1, 0x0
 *     iput-object v1, p0, Lapp/revanced/extension/facebook/userscript/Injector;->current:Landroid/app/Activity;
 *     return-void
 * .end method
 *
 * .method public onActivityCreated(Landroid/app/Activity;Landroid/os/Bundle;)V
 *     .locals 0
 *     return-void
 * .end method
 *
 * .method public onActivityStarted(Landroid/app/Activity;)V
 *     .locals 0
 *     return-void
 * .end method
 *
 * .method public onActivityStopped(Landroid/app/Activity;)V
 *     .locals 0
 *     return-void
 * .end method
 *
 * .method public onActivitySaveInstanceState(Landroid/app/Activity;Landroid/os/Bundle;)V
 *     .locals 0
 *     return-void
 * .end method
 *
 * .method public onActivityDestroyed(Landroid/app/Activity;)V
 *     .locals 0
 *     return-void
 * .end method
 * ------------------------------------------------------------------------------------------------------------------
 */

private const val INJECTOR = "Lapp/revanced/extension/facebook/userscript/Injector;"
private const val APP_CLASS = "Lcom/facebook/katana/app/FacebookApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"
private const val START_URL = "https://m.facebook.com/"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** Userscript. "__BADGE__" is replaced by true/false (patch option). Written without the dollar sign on purpose. */
private val USERSCRIPT = """
/* RV: hide Sponsored posts and the "Open app" bar on facebook.com pages shown inside the Android 8 Facebook app.
 * Derived from "FB - Clean my feeds" 6.5.0 (GPL-3.0, Artificial Sweetener, zbluebugz, Quoc Viet Rtinh):
 *   - /ads/about/ disclosure link, svg disclosure, sponsored label in every language of the original
 *   - its label tables (23 languages) for the suggestions it hides by default: Suggested for you, Groups you might
 *     like, Events you may like, Follow, Join, Paid for by ______; its Paid partnership link (/business/help/), Meta AI
 *     link (meta.ai) and survey link (/survey/?session=) rules
 *   - the filter  facebook.com##[data-ad-rendering-role="profile_name"]:upward([aria-posinset]:has(
 *       [data-ad-rendering-role="story_message"]):has([data-ad-rendering-role^="cta-"]))
 * Injected again by the app every ~1.5 s; the first line makes every later injection a no-op.
 * ES5 on purpose (old System WebView). */
(function () {
  'use strict';
  try {
    if (window.__rvFbSponsorBlock) return;
    var host = String(location.hostname || '');
    if (!/(^|\.)(facebook\.com|fb\.com|facebookcorewwwi\.onion)${'$'}/.test(host)) return;
    window.__rvFbSponsorBlock = { hidden: 0, openApp: 0, rules: {}, started: Date.now() };
  } catch (e) { return; }

  var SHOW_BADGE = __BADGE__;
  var HIDE = 'data-rv-sponsored';
  var GONE = 'data-rv-openapp';
  var FIXED = 'data-rv-fixed';
  var LABELS = ["Sponsored","\u0645\u064f\u0645\u0648\u064e\u0651\u0644","\u0421\u043f\u043e\u043d\u0441\u043e\u0440\u0438\u0440\u0430\u043d\u043e","Sponzorov\u00e1no","Gesponsert","Anzeige","\u03a7\u03bf\u03c1\u03b7\u03b3\u03bf\u03cd\u03bc\u03b5\u03bd\u03b7","Publicidad","Sponsoroitu","Sponsoris\u00e9","\u05de\u05de\u05d5\u05de\u05df","Bersponsor","Sponsorizzato","\u5e83\u544a","Apmaks\u0101ta rekl\u0101ma","Gesponsord","Sponsorowane","Patrocinado","\u0420\u0435\u043a\u043b\u0430\u043c\u0430","Sponsorlu","\u0421\u043f\u043e\u043d\u0441\u043e\u0440\u043e\u0432\u0430\u043d\u0430","\u0110\u01b0\u1ee3c t\u00e0i tr\u1ee3","\u8d5e\u52a9\u5185\u5bb9","\u8d0a\u52a9","Ad","\u0625\u0639\u0644\u0627\u0646","Reklama","Werbung","\u0394\u03b9\u03b1\u03c6\u03ae\u03bc\u03b9\u03c3\u03b7","Anuncio","Mainos","Publicit\u00e9","Pub","\u05de\u05d5\u05d3\u05e2\u05d4","Iklan","Inserzione","Pubblicit\u00e0","Rekl\u0101ma","Advertentie","An\u00fancio","Reklam","Qu\u1ea3ng c\u00e1o","\u5e7f\u544a","\u5ee3\u544a","C\u00f3 t\u00e0i tr\u1ee3"];
  var OPENAPP = ["Open app","Open the app","Open in app","Open in the app","Open Facebook app","Open Facebook","Use app","Use the app","Use Facebook app","M\u1edf \u1ee9ng d\u1ee5ng","M\u1edf app","M\u1edf trong \u1ee9ng d\u1ee5ng","M\u1edf b\u1eb1ng \u1ee9ng d\u1ee5ng","M\u1edf \u1ee9ng d\u1ee5ng Facebook","M\u1edf Facebook","M\u1edf trong app","D\u00f9ng \u1ee9ng d\u1ee5ng","S\u1eed d\u1ee5ng \u1ee9ng d\u1ee5ng","D\u00f9ng \u1ee9ng d\u1ee5ng Facebook","Abrir app","Abrir la app","Abrir aplicaci\u00f3n","Abrir la aplicaci\u00f3n","Abrir en la app","Abrir en la aplicaci\u00f3n","Usar la aplicaci\u00f3n","Abrir Facebook","Abrir o app","Abrir aplicativo","Abrir o aplicativo","Abrir no app","Abrir no aplicativo","Abrir aplica\u00e7\u00e3o","Usar o app","Ouvrir l'application","Ouvrir l'appli","Ouvrir dans l'application","Ouvrir dans l'appli","Utiliser l'application","Ouvrir Facebook","App \u00f6ffnen","\u00d6ffne die App","In der App \u00f6ffnen","App verwenden","Facebook-App \u00f6ffnen","Facebook \u00f6ffnen","In App \u00f6ffnen","Apri app","Apri l'app","Apri nell'app","Apri l'applicazione","Apri in app","Usa l'app","Apri Facebook","App openen","Open de app","Openen in app","De app gebruiken","Facebook openen","\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435","\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u0432 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0438","\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u0432 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0438 Facebook","\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435","\u041e\u0442\u043a\u0440\u044b\u0442\u044c Facebook","\u0412\u0456\u0434\u043a\u0440\u0438\u0442\u0438 \u0434\u043e\u0434\u0430\u0442\u043e\u043a","\u0412\u0456\u0434\u043a\u0440\u0438\u0442\u0438 \u0432 \u0434\u043e\u0434\u0430\u0442\u043a\u0443","\u0412\u0456\u0434\u043a\u0440\u0438\u0442\u0438 \u0437\u0430\u0441\u0442\u043e\u0441\u0443\u043d\u043e\u043a","\u0412\u0456\u0434\u043a\u0440\u0438\u0442\u0438 \u0443 \u0437\u0430\u0441\u0442\u043e\u0441\u0443\u043d\u043a\u0443","\u0412\u0438\u043a\u043e\u0440\u0438\u0441\u0442\u043e\u0432\u0443\u0432\u0430\u0442\u0438 \u0434\u043e\u0434\u0430\u0442\u043e\u043a","Otw\u00f3rz aplikacj\u0119","Otw\u00f3rz w aplikacji","U\u017cyj aplikacji","Otw\u00f3rz Facebooka","Otev\u0159\u00edt aplikaci","Otev\u0159\u00edt v aplikaci","Pou\u017e\u00edt aplikaci","Otvori\u0165 aplik\u00e1ciu","Otvori\u0165 v aplik\u00e1cii","Deschide aplica\u021bia","Deschide \u00een aplica\u021bie","Folose\u0219te aplica\u021bia","Alkalmaz\u00e1s megnyit\u00e1sa","Megnyit\u00e1s az alkalmaz\u00e1sban","Az alkalmaz\u00e1s haszn\u00e1lata","\u0386\u03bd\u03bf\u03b9\u03b3\u03bc\u03b1 \u03b5\u03c6\u03b1\u03c1\u03bc\u03bf\u03b3\u03ae\u03c2","\u0386\u03bd\u03bf\u03b9\u03b3\u03bc\u03b1 \u03c3\u03c4\u03b7\u03bd \u03b5\u03c6\u03b1\u03c1\u03bc\u03bf\u03b3\u03ae","\u0386\u03bd\u03bf\u03b9\u03b3\u03bc\u03b1 \u03c4\u03b7\u03c2 \u03b5\u03c6\u03b1\u03c1\u03bc\u03bf\u03b3\u03ae\u03c2","\u03a7\u03c1\u03ae\u03c3\u03b7 \u03c4\u03b7\u03c2 \u03b5\u03c6\u03b1\u03c1\u03bc\u03bf\u03b3\u03ae\u03c2","\u041e\u0442\u0432\u043e\u0440\u0438 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435\u0442\u043e","\u041e\u0442\u0432\u0430\u0440\u044f\u043d\u0435 \u0432 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0435\u0442\u043e","Otvori aplikaciju","Otvori u aplikaciji","Koristi aplikaciju","Odpri aplikacijo","Odpri v aplikaciji","Uygulamay\u0131 a\u00e7","Uygulamada a\u00e7","Uygulamay\u0131 kullan","\u0641\u062a\u062d \u0627\u0644\u062a\u0637\u0628\u064a\u0642","\u0627\u0641\u062a\u062d \u0627\u0644\u062a\u0637\u0628\u064a\u0642","\u0641\u062a\u062d \u0641\u064a \u0627\u0644\u062a\u0637\u0628\u064a\u0642","\u0627\u0633\u062a\u062e\u062f\u0627\u0645 \u0627\u0644\u062a\u0637\u0628\u064a\u0642","\u0627\u0633\u062a\u062e\u062f\u0645 \u0627\u0644\u062a\u0637\u0628\u064a\u0642","\u05e4\u05ea\u05d7 \u05d1\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4","\u05e4\u05ea\u05d9\u05d7\u05d4 \u05d1\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4","\u05e4\u05ea\u05d7 \u05d0\u05ea \u05d4\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4","\u05e4\u05ea\u05d7 \u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4","\u05d4\u05e9\u05ea\u05de\u05e9 \u05d1\u05d0\u05e4\u05dc\u05d9\u05e7\u05e6\u05d9\u05d4","\u0628\u0627\u0632 \u06a9\u0631\u062f\u0646 \u0628\u0631\u0646\u0627\u0645\u0647","\u0628\u0627\u0632\u06a9\u0631\u062f\u0646 \u062f\u0631 \u0628\u0631\u0646\u0627\u0645\u0647","\u0627\u0633\u062a\u0641\u0627\u062f\u0647 \u0627\u0632 \u0628\u0631\u0646\u0627\u0645\u0647","\u0910\u092a \u0916\u094b\u0932\u0947\u0902","\u0910\u092a \u092e\u0947\u0902 \u0916\u094b\u0932\u0947\u0902","\u0910\u092a \u0915\u093e \u0909\u092a\u092f\u094b\u0917 \u0915\u0930\u0947\u0902","\u0985\u09cd\u09af\u09be\u09aa \u0996\u09c1\u09b2\u09c1\u09a8","\u0985\u09cd\u09af\u09be\u09aa\u09c7 \u0996\u09c1\u09b2\u09c1\u09a8","\u0e40\u0e1b\u0e34\u0e14\u0e41\u0e2d\u0e1b","\u0e40\u0e1b\u0e34\u0e14\u0e43\u0e19\u0e41\u0e2d\u0e1b","\u0e40\u0e1b\u0e34\u0e14\u0e41\u0e2d\u0e1b\u0e1e\u0e25\u0e34\u0e40\u0e04\u0e0a\u0e31\u0e19","\u0e43\u0e0a\u0e49\u0e41\u0e2d\u0e1b","Buka aplikasi","Buka di aplikasi","Buka app","Gunakan aplikasi","Buka Facebook","Buka apl","Buka dalam apl","Guna apl","Buksan ang app","Buksan sa app","Gamitin ang app","\u6253\u5f00\u5e94\u7528","\u6253\u5f00\u5e94\u7528\u7a0b\u5e8f","\u5728\u5e94\u7528\u4e2d\u6253\u5f00","\u6253\u5f00App","\u6253\u5f00 App","\u4f7f\u7528\u5e94\u7528","\u5728App\u4e2d\u6253\u5f00","\u5728 App \u4e2d\u6253\u5f00","\u6253\u5f00Facebook\u5e94\u7528","\u958b\u555f\u61c9\u7528\u7a0b\u5f0f","\u958b\u555fApp","\u958b\u555f App","\u5728\u61c9\u7528\u7a0b\u5f0f\u4e2d\u958b\u555f","\u5728 App \u4e2d\u958b\u555f","\u4f7f\u7528\u61c9\u7528\u7a0b\u5f0f","\u6253\u958b\u61c9\u7528\u7a0b\u5f0f","\u6253\u958bApp","\u5728App\u4e2d\u6253\u958b","\u30a2\u30d7\u30ea\u3092\u958b\u304f","\u30a2\u30d7\u30ea\u3067\u958b\u304f","\u30a2\u30d7\u30ea\u3092\u4f7f\u3046","Facebook\u30a2\u30d7\u30ea\u3092\u958b\u304f","\uc571 \uc5f4\uae30","\uc571\uc5d0\uc11c \uc5f4\uae30","\uc571 \uc0ac\uc6a9\ud558\uae30","\uc571\uc73c\ub85c \uc5f4\uae30","\uc571\uc5d0\uc11c \ubcf4\uae30","\u00d6ppna appen","\u00d6ppna i appen","Anv\u00e4nd appen","\u00c5pne appen","\u00c5pne i appen","Bruk appen","\u00c5bn appen","\u00c5bn i appen","Brug appen","Avaa sovellus","Avaa sovelluksessa","K\u00e4yt\u00e4 sovellusta","Ava rakendus","Atv\u0113rt lietotni","Atidaryti program\u0117l\u0119","Obre l'aplicaci\u00f3","Obrir l'app","Maak app oop","Fungua programu","\u0627\u06cc\u067e \u06a9\u06be\u0648\u0644\u06cc\u06ba","\u0b9a\u0bc6\u0baf\u0bb2\u0bbf\u0baf\u0bc8\u0ba4\u0bcd \u0ba4\u0bbf\u0bb1","\u1794\u17be\u1780\u1780\u1798\u17d2\u1798\u179c\u17b7\u1792\u17b8"];
  var NEWS = {"follow":["Follow","متابعة","تابع","Следване","Следвай","Sledovat","Folgen","Ακολούθηση","Ακολούθησε","Seguir","Seuraa","Suivre","S’abonner","S'abonner","עקוב","מעקב","Ikuti","Segui","フォロー","フォローする","Sekot","Volgen","Obserwuj","Подписаться","Takip Et","Стежити","Підписатися","Theo dõi","关注","追蹤"],"join":["Join","Join group","انضمام","انضمام إلى المجموعة","Присъединяване","Присъединяване към групата","Přidat se","Přidat se ke skupině","Beitreten","Gruppe beitreten","Συμμετοχή","Γίνετε μέλος","Unirte","Unirse","Unirte al grupo","Liity","Liity ryhmään","Rejoindre","Rejoindre le groupe","הצטרף","הצטרפות לקבוצה","Gabung","Gabung grup","Iscriviti","Iscriviti al gruppo","参加","グループに参加","Pievienoties","Pievienoties grupai","Lid worden","Lid worden van groep","Dołącz","Dołącz do grupy","Participar","Aderir","Entrar no grupo","Вступить","Вступить в группу","Katıl","Gruba Katıl","Приєднатися","Приєднатися до групи","Tham gia","Tham gia nhóm","加入","加入小组","加入社團"],"suggested":["Suggested for you","Recommended for you","Suggested Pages","Pages you may like","مقترح لك","مقترحات لك","صفحات قد تعجبك","Предложения за вас","Страници, които може да харесате","Navrhované pro vás","Stránky, které by se vám mohly líbit","Vorschläge für dich","Für dich empfohlen","Seiten, die dir gefallen könnten","Προτεινόμενα για εσάς","Σελίδες που μπορεί να σας αρέσουν","Sugerencias para ti","Sugerido para ti","Páginas que te pueden gustar","Ehdotuksia sinulle","Sivut, joista saatat pitää","Suggestion pour vous","Suggestions pour vous","Pages qui pourraient vous plaire","הצעות עבורך","דפים שאולי תאהב","Disarankan untuk Anda","Halaman yang mungkin Anda sukai","Contenuti suggeriti per te","Suggerimenti per te","Pagine che potrebbero piacerti","おすすめ","おすすめのページ","Ieteikumi jums","Lapas, kas tev varētu patikt","Voorgesteld voor jou","Pagina’s die je misschien leuk vindt","Pagina's die je misschien leuk vindt","Proponowane dla Ciebie","Strony, które możesz polubić","Sugestões para ti","Sugestões para você","Páginas de que poderás gostar","Páginas que você pode curtir","Рекомендуем вам","Предлагаемое для вас","Страницы, которые могут вам понравиться","Senin için önerilenler","Beğenebileceğin Sayfalar","Рекомендовано для вас","Сторінки, які можуть вам сподобатися","Gợi ý cho bạn","Đề xuất cho bạn","Trang bạn có thể thích","为你推荐","推荐给你","你可能喜欢的公共主页","為你推薦","推薦給你","你可能會喜歡的粉絲專頁"],"groups":["Groups you might like","Suggested groups","مجموعات قد تعجبك","مجموعات مقترحة","Групи, които може да харесате","Предложени групи","Skupiny, které by se vám mohly líbit","Navrhované skupiny","Gruppen, die dir gefallen könnten","Vorgeschlagene Gruppen","Ομάδες που μπορεί να σας αρέσουν","Προτεινόμενες ομάδες","Grupos que te pueden gustar","Grupos sugeridos","Ryhmät, joista saatat pitää","Ehdotetut ryhmät","Groupes qui pourraient vous plaire","Groupes suggérés","קבוצות שאולי תאהב","קבוצות מוצעות","Grup yang mungkin Anda sukai","Grup yang disarankan","Gruppi che potrebbero piacerti","Gruppi suggeriti","おすすめのグループ","Grupas, kas tev varētu patikt","Ieteiktās grupas","Groepen die je misschien leuk vindt","Voorgestelde groepen","Grupy, które mogą Ci się spodobać","Proponowane grupy","Grupos de que poderás gostar","Grupos que você pode gostar","Группы, которые могут вам понравиться","Рекомендуемые группы","Beğenebileceğin Gruplar","Önerilen Gruplar","Групи, які можуть вам сподобатися","Рекомендовані групи","Nhóm bạn có thể thích","Nhóm được gợi ý","你可能喜欢的小组","推荐小组","你可能會喜歡的社團","推薦社團"],"events":["Events you may like","أحداث قد تعجبك","Събития, които може да ви харесат","Události, které se vám mohou líbit","Veranstaltungen, die dir gefallen könnten","Veranstaltungen, die Ihnen gefallen könnten","Εκδηλώσεις που μπορεί να σας αρέσουν","Eventos que te pueden gustar","Tapahtumat, joista saatat pitää","Évènements qui pourraient vous intéresser","אירועים שאולי תאהבו","Acara yang mungkin Anda sukai","Eventi che potrebbero piacerti","おすすめのイベント","Notikumi, kas jums varētu patikt","Evenementen die je misschien leuk vindt","Wydarzenia, które mogą Ci się spodobać","Eventos que você pode gostar","Мероприятия, которые вам могут понравиться","İlgini çekebilecek etkinlikler","Події, які можуть вам сподобатися","Sự kiện bạn có thể thích","您可能喜欢的活动","你可能感興趣的活動"],"paidBy":["Paid for by ______","مدفوعة بواسطة ______","Платено от ______","Platí za to ______","Finanziert von ______","Πληρωμένο από ______","Pagado por ______","Maksaja ______","Financé par ______","שולם על ידי ______","Dibayar oleh ______","Finanziato da ______","______ による支払い","______による支払い","Apmaksā ______","Betaald door ______","Opłacona przez ______","Financiado por ______","Оплачено ______","______ tarafından ödendi","Tài trợ bởi ______","由 ______ 付费","由______付费","出資者：______"]};   /* {follow, join, suggested, groups, people, events, reels, paidBy} from the original script */
  /* What is hidden: the defaults of the original script (people / reels are off there too). Sponsored is hidden on every
   * page; the rest only on the News Feed route, where the original applies it, so a Follow button at the top of a page
   * or profile is never mistaken for a post. */
  var ON = { follow: 1, join: 1, suggested: 1, groups: 1, events: 1, paidBy: 1, paidPartnership: 1, metaAi: 1, survey: 1,
             people: 0, reels: 0 };
  var state = window.__rvFbSponsorBlock;

  /* ---------------------------------------------------------------------------------------------------------------
   * text helpers
   * ------------------------------------------------------------------------------------------------------------- */
  function norm(s) {
    s = String(s == null ? '' : s);
    try { s = s.normalize('NFKC'); } catch (e) {}
    return s.replace(/[​-‏‪-‮⁦-⁩﻿]/g, '')
      .replace(/[‘’ʼ]/g, "'")
      .replace(/\s+/g, ' ').trim().toLowerCase();
  }
  /* norm() without the separators a label is printed next to: "Được tài trợ · [globe]" -> "được tài trợ" */
  function core(s) {
    return norm(s).replace(/^[\s·•|.:,–—-]+|[\s·•|.:,–—-]+${'$'}/g, '');
  }
  var SET = Object.create(null), OA = Object.create(null), i;
  for (i = 0; i < LABELS.length; i++) SET[core(LABELS[i])] = 1;
  for (i = 0; i < OPENAPP.length; i++) OA[core(OPENAPP[i])] = 1;
  function isLabel(s) { return SET[core(s)] === 1; }
  function isOpenApp(s) { return OA[core(s)] === 1; }

  /* normalized label text -> kind, for the kinds of the original's news label table; "Paid for by ______" templates */
  var KIND = Object.create(null), PAIDBY = [], kk, kl;
  for (kk in NEWS) {
    if (kk === 'paidBy') {
      for (kl = 0; kl < NEWS.paidBy.length; kl++) {
        var parts = norm(NEWS.paidBy[kl]).split('______');
        if ((parts[0] || parts[1])) PAIDBY.push([parts[0] || '', parts[1] || '']);
      }
    } else {
      for (kl = 0; kl < NEWS[kk].length; kl++) { var key = core(NEWS[kk][kl]); if (key && !KIND[key]) KIND[key] = kk; }
    }
  }
  function paidByText(c) {
    if (c.length < 6 || c.length > 90) return false;
    for (var i = 0; i < PAIDBY.length; i++) {
      var pre = PAIDBY[i][0], suf = PAIDBY[i][1];
      if (c.indexOf(pre) !== 0) continue;
      if (suf && c.slice(c.length - suf.length) !== suf) continue;
      if (c.slice(pre.length, c.length - suf.length).replace(/\s+/g, '') !== '') return true;
    }
    return false;
  }
  /* what a piece of text says it is: 'sponsored', one of the news kinds, 'paidBy', or '' */
  var memo = Object.create(null), memoN = 0;
  function kindOf(s) {
    if (s.length > 100) return '';
    var r = memo[s];
    if (r !== undefined) return r;
    var c = core(s), k = '';
    if (c) {
      if (SET[c] === 1) k = 'sponsored';
      else if (KIND[c]) k = ON[KIND[c]] ? KIND[c] : '';
      else if (ON.paidBy && paidByText(c)) k = 'paidBy';
    }
    if (memoN++ > 6000) { memo = Object.create(null); memoN = 0; }
    memo[s] = k;
    return k;
  }
  function feedRoute() {
    var p = String(location.pathname || '/');
    return p === '/' || p.indexOf('/home.php') === 0 || p === '/index.php';
  }
  function allowedKind(k) { return k === 'sponsored' || feedRoute(); }

  var BODY = '[data-ad-rendering-role="story_message"],[data-ad-preview="message"],[data-ad-comet-preview="message"],blockquote,p';

  /* text a person can see inside el (hidden decoy letters are skipped, bounded work) */
  function visibleText(el) {
    var out = '', n = 0;
    (function walk(node) {
      if (n++ > 128 || out.length > 256) return;
      if (node.nodeType === 3) { out += node.nodeValue || ''; return; }
      if (node.nodeType !== 1) return;
      var t = String(node.tagName).toUpperCase();
      if (t === 'SCRIPT' || t === 'STYLE' || t === 'SVG' || node.hasAttribute('hidden')) return;
      if (node !== el) {
        var cs;
        try { cs = getComputedStyle(node); } catch (e) { cs = null; }
        if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || cs.fontSize === '0px')) return;
      }
      for (var c = node.firstChild; c; c = c.nextSibling) walk(c);
    })(el);
    return out.length > 256 ? '' : out;
  }

  function svgLabel(svg) {
    var a = svg.getAttribute('aria-label');
    if (a) return a;
    var txt = '', ts = svg.querySelectorAll('text,title'), k;
    for (k = 0; k < ts.length && k < 40; k++) txt += ts[k].textContent || '';
    return txt;
  }

  function inBody(el) { return !!el.closest(BODY); }

  function near(a, b) { return isFinite(a) && isFinite(b) && Math.abs(a - b) <= 2.5; }
  function vw() { return window.innerWidth || document.documentElement.clientWidth || 390; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight || 800; }

  /* ---------------------------------------------------------------------------------------------------------------
   * hiding
   * ------------------------------------------------------------------------------------------------------------- */
  function hide(el, rule) {
    if (!el || el.hasAttribute(HIDE)) return;
    el.setAttribute(HIDE, rule);
    state.hidden++;
    state.rules[rule] = (state.rules[rule] || 0) + 1;
    paintBadge();
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * facebook.com (desktop layout): posts are [aria-posinset] / role=article
   * ------------------------------------------------------------------------------------------------------------- */
  /* the post that owns el: the outermost [aria-posinset], else the outermost role=article */
  function postOf(el) {
    var p = el.closest('[aria-posinset]');
    if (p) return p;
    p = el.closest('[role="article"]');
    while (p && p.parentElement) {
      var o = p.parentElement.closest('[role="article"]');
      if (!o) break;
      p = o;
    }
    return p;
  }

  /* ABP filter: closest ancestor of profile_name that is [aria-posinset] with story_message and a cta-* node */
  function abp(root) {
    if (!root || !root.querySelectorAll) return;
    var list = root.querySelectorAll('[data-ad-rendering-role="profile_name"]');
    if (root.matches && root.matches('[data-ad-rendering-role="profile_name"]')) list = [root];
    for (var i = 0; i < list.length; i++) {
      for (var a = list[i].parentElement; a; a = a.parentElement) {
        if (a.hasAttribute('aria-posinset') &&
            a.querySelector('[data-ad-rendering-role="story_message"]') &&
            a.querySelector('[data-ad-rendering-role^="cta-"]')) {
          hide(a, 'abp');
          break;
        }
      }
    }
  }

  /* link / svg based rules for one post */
  function inspect(post) {
    if (!post || !post.isConnected || post.hasAttribute(HIDE)) return;
    var links = post.querySelectorAll('a[href],[role="link"]'), i, l, h, t;
    for (i = 0; i < links.length && i < 200; i++) {
      l = links[i];
      if (inBody(l)) continue;
      h = l.getAttribute('href') || '';
      var name = l.getAttribute('aria-label') || '';
      var txt = name ? '' : visibleText(l);
      if (/\/ads\/about(\/|\?|${'$'})/.test(h) && (name ? isLabel(name) : (isLabel(txt) || txt === '' || l.querySelector('svg')))) {
        hide(post, 'ads-about'); return;
      }
      if (name ? isLabel(name) : isLabel(txt) || isLabel(l.getAttribute('title') || '')) {
        hide(post, 'label'); return;
      }
      var svgs = l.querySelectorAll('svg');
      for (t = 0; t < svgs.length && t < 8; t++) {
        if (isLabel(svgLabel(svgs[t]))) { hide(post, 'svg-label'); return; }
      }
    }
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * m.facebook.com (phone layout): no aria-posinset / role=article that can be relied on, so the post around a
   * "Sponsored" label is found by SHAPE, not by attribute names:
   *   a post card is the lowest ancestor of the label that
   *     - is at least a quarter of the screen high and (nearly) as wide as the screen,
   *     - is not taller than 1.7 screens,
   *     - has at least one sibling that is just as big (the next / previous post, so its parent is the feed list).
   *   The label must sit in the top part of the card (its header), so a friend's text that happens to be the word
   *   "Sponsored" in the middle of a post is left alone.
   * ------------------------------------------------------------------------------------------------------------- */
  function big(r, minH, minW) { return r.height >= minH && r.width >= minW; }

  function geoCard(el, frac) {
    var W = vw(), H = vh(), MIN = H * (frac || 0.25), MAX = H * 1.7, a, d, r, par, kids, k, cnt;
    for (a = el, d = 0; a && a !== document.body && a !== document.documentElement && d < 30; a = a.parentElement, d++) {
      r = a.getBoundingClientRect();
      if (r.height > MAX) return null;
      if (!big(r, MIN, W * 0.8)) continue;
      par = a.parentElement;
      if (!par) return null;
      kids = par.children;
      cnt = 0;
      for (k = 0; k < kids.length && k < 400; k++) {
        if (big(kids[k].getBoundingClientRect(), MIN, W * 0.8)) cnt++;
      }
      if (cnt >= 2) return a;
    }
    return null;
  }

  /* older idea, kept as a second chance: a story carries data-tracking-duration-id; from the label upwards take the
   * outermost one that holds no OTHER such element and is not taller than 1.7 screens. */
  function trackCard(el, frac) {
    var best = null, H = vh(), SEL = '[data-tracking-duration-id]';
    for (var a = el.closest(SEL); a; a = a.parentElement ? a.parentElement.closest(SEL) : null) {
      var inner = a.querySelectorAll(SEL), foreign = false, i;
      for (i = 0; i < inner.length; i++) {
        if (!inner[i].contains(el)) { foreign = true; break; }
      }
      if (foreign) break;
      var r = a.getBoundingClientRect();
      if (r.height >= H * (frac || 0.25) && r.height <= H * 1.7) best = a;
    }
    return best;
  }

  /* the label has to be in the header part of the card */
  function inHeader(el, card) {
    var a = el.getBoundingClientRect(), b = card.getBoundingClientRect(), off = a.top - b.top;
    return off >= -4 && off <= Math.max(160, b.height * 0.35);
  }

  /* what a link points at: 'paidPartnership' (/business/help/), 'metaAi' (meta.ai, also through l.facebook.com/l.php),
   * 'survey' (/survey/?session=), or '' */
  function linkKind(a) {
    var h = a.getAttribute('href');
    if (!h || h.length > 600) return '';
    try {
      var u = new URL(h, location.href);
      if (/(^|\.)facebook\.com${'$'}/.test(u.hostname)) {
        if (u.hostname === 'l.facebook.com' && u.pathname === '/l.php') {
          var d = u.searchParams.get('u');
          if (!d) return '';
          u = new URL(d);
        } else {
          if (ON.paidPartnership && u.pathname.indexOf('/business/help/') === 0) return 'paidPartnership';
          if (ON.survey && u.pathname === '/survey/' && /(^|&)session=/.test(String(u.search).slice(1))) return 'survey';
          return '';
        }
      }
      if (ON.metaAi && (u.protocol === 'https:' || u.protocol === 'http:') && (u.hostname === 'meta.ai' || /\.meta\.ai${'$'}/.test(u.hostname))) return 'metaAi';
    } catch (e) {}
    return '';
  }

  /* all elements that show a label inside root: text nodes (the label is often a bare text node next to a " · " and an
   * icon), controls named by aria-label, links that mean a kind, and elements whose visible letters add up to a sponsored
   * label (decoy letters). Each element is tagged with its kind in __rvKind. */
  function labelEls(root) {
    var out = [];
    if (!root || root.nodeType !== 1) return out;
    var tw = document.createTreeWalker(root, 4, null, false), t, n = 0, pe, tag, k;
    while ((t = tw.nextNode()) && n++ < 8000) {
      var v = t.nodeValue;
      if (!v || v.length < 2 || v.length > 100 || !/\S/.test(v)) continue;
      k = kindOf(v);
      if (!k) continue;
      pe = t.parentElement;
      if (!pe) continue;
      tag = String(pe.tagName).toUpperCase();
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TEXTAREA' || tag === 'TITLE' || tag === 'NOSCRIPT') continue;
      pe.__rvKind = k;
      out.push(pe);
    }
    var ctl = root.querySelectorAll('button[aria-label],[role="button"][aria-label],a[aria-label]'), j;
    for (j = 0; j < ctl.length && j < 600; j++) {
      k = kindOf(ctl[j].getAttribute('aria-label') || '');
      if (k) { ctl[j].__rvKind = k; out.push(ctl[j]); }
    }
    var as = root.querySelectorAll('a[href]');
    for (j = 0; j < as.length && j < 800; j++) {
      k = linkKind(as[j]);
      if (k) { as[j].__rvKind = k; out.push(as[j]); }
    }
    if (root.matches && root.matches('a[href]')) { k = linkKind(root); if (k) { root.__rvKind = k; out.push(root); } }
    var cand = root.querySelectorAll('span,div,a,h3,h4,h5,strong,b'), e, c = 0, tl;
    for (j = 0; j < cand.length && c < 2500; j++) {
      e = cand[j];
      if (e.childElementCount < 2 || e.childElementCount > 40) continue;
      c++;
      tl = (e.textContent || '').length;
      if (tl < 5 || tl > 80) continue;
      if (isLabel(visibleText(e))) { e.__rvKind = 'sponsored'; out.push(e); }
    }
    return out;
  }

  /* things that are a card of their own: the label does not have to be in the header part of the card */
  var ANYWHERE = { survey: 1 };
  var CLICKABLE = 'button,[role="button"],a[href],[tabindex]';

  /* true = finished (hidden or rejected), false = the page has not been laid out yet, try again later */
  function handleLabel(el) {
    if (el.__rvDone) return true;
    if (!el.isConnected) return true;
    if (el.closest('[' + HIDE + ']')) { el.__rvDone = true; return true; }
    var kind = el.__rvKind || 'sponsored';
    if (!allowedKind(kind)) return true;      /* not on this page (yet): the next scan looks again */
    if (inBody(el)) { el.__rvDone = true; return true; }
    /* "Follow" / "Join" count only on a button, never as a word in a text */
    if ((kind === 'follow' || kind === 'join') && !el.closest(CLICKABLE)) { el.__rvDone = true; return true; }
    var card = postOf(el), rule = 'text';
    if (!card) { card = geoCard(el); rule = 'shape'; }
    if (!card) { card = trackCard(el); rule = 'track'; }
    /* a short text-only suggested post is lower than a quarter of the screen */
    if (!card && kind !== 'sponsored') { card = geoCard(el, 0.12); rule = 'shape-small'; }
    if (!card) return false;
    if (!card.getBoundingClientRect().height) return false;
    el.__rvDone = true;
    if (!ANYWHERE[kind] && !inHeader(el, card)) return true;
    hide(card, kind + ':' + rule);
    return true;
  }

  var pend = [], retryTimer = 0;
  function checkLabels(els) {
    for (var i = 0; i < els.length; i++) {
      var el = els[i], done = true;
      try { done = handleLabel(el); } catch (e) { done = true; }
      if (!done && !el.__rvQ && pend.length < 300) { el.__rvQ = 1; pend.push({ el: el, n: 0 }); }
    }
  }
  function retryPending() {
    retryTimer = 0;
    var keep = [], i, p, done;
    for (i = 0; i < pend.length; i++) {
      p = pend[i];
      try { done = handleLabel(p.el); } catch (e) { done = true; }
      if (!done && ++p.n < 15) keep.push(p); else p.el.__rvQ = 0;
    }
    pend = keep;
    scheduleRetry();
  }
  function scheduleRetry() {
    if (pend.length && !retryTimer) retryTimer = setTimeout(retryPending, 500);
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * the "Open app" bar / button (every language), and the room it leaves behind
   * ------------------------------------------------------------------------------------------------------------- */
  function fix(el, prop, value) {
    el.style.setProperty(prop, value, 'important');
    el.setAttribute(FIXED, '1');
  }

  /* After a bar of height h was removed from the top or the bottom edge, give its room back to the page: spacing that
   * was reserved for it (padding / margin / offset of the same height), an empty spacer of that height, and a box
   * that was sized to stop where the bar began. */
  function reclaim(h, atBottom) {
    if (!(h >= 8)) return;
    var H = vh(), W = vw(), list = [document.documentElement, document.body], lvl = [document.body], d, k, nx;
    for (d = 0; d < 3 && list.length < 400; d++) {
      nx = [];
      for (var q = 0; q < lvl.length; q++) {
        for (k = 0; k < lvl[q].children.length && k < 60; k++) nx.push(lvl[q].children[k]);
      }
      list = list.concat(nx);
      lvl = nx;
    }
    var pad = atBottom ? 'padding-bottom' : 'padding-top', mar = atBottom ? 'margin-bottom' : 'margin-top';
    var off = atBottom ? 'bottom' : 'top';
    for (k = 0; k < list.length; k++) {
      var e = list[k];
      if (!e || e.hasAttribute(HIDE) || e.hasAttribute(GONE) || e.hasAttribute(FIXED)) continue;
      var cs = getComputedStyle(e), pos = cs.position;
      if (cs.display === 'none') continue;
      if (near(parseFloat(cs.getPropertyValue(pad)), h)) fix(e, pad, '0px');
      if (near(parseFloat(cs.getPropertyValue(mar)), h)) fix(e, mar, '0px');
      if ((pos === 'fixed' || pos === 'absolute') && near(parseFloat(cs.getPropertyValue(off)), h)) fix(e, off, '0px');
      var r = e.getBoundingClientRect();
      if (e !== document.body && e !== document.documentElement && e.childElementCount === 0 &&
          (e.textContent || '').trim() === '' && near(r.height, h) && pos !== 'fixed' && pos !== 'absolute') {
        e.setAttribute(GONE, 'spacer');
        continue;
      }
      if (atBottom && r.width >= W * 0.9 && r.height >= H * 0.4 && near(r.bottom, H - h) &&
          (pos === 'fixed' || pos === 'absolute' || /(auto|scroll)/.test(cs.overflowY))) {
        fix(e, 'height', (r.height + h) + 'px');
        fix(e, 'max-height', 'none');
      }
    }
  }

  /* a dim layer left behind by a removed dialog, and the scroll lock it set on the page */
  function dropScrim() {
    var H = vh(), W = vw(), all = document.body ? document.body.children : [], k, e, cs;
    for (k = 0; k < all.length && k < 60; k++) {
      e = all[k];
      cs = getComputedStyle(e);
      if ((cs.position === 'fixed') && e.getBoundingClientRect().width >= W * 0.95 &&
          e.getBoundingClientRect().height >= H * 0.95 && (e.textContent || '').trim() === '') {
        e.setAttribute(GONE, 'scrim');
      }
    }
    fix(document.documentElement, 'overflow', 'auto');
    if (document.body) fix(document.body, 'overflow', 'auto');
  }

  function removeOpenApp(e) {
    if (e.hasAttribute(GONE) || e.closest('[' + GONE + ']')) return;
    var H = vh(), bar = null, a, cs, r;
    for (a = e; a && a !== document.body && a !== document.documentElement; a = a.parentElement) {
      cs = getComputedStyle(a);
      if (cs.position === 'fixed' || cs.position === 'sticky') { bar = a; break; }
    }
    var tgt = null, atBottom = false, modal = false;
    if (bar) {
      r = bar.getBoundingClientRect();
      if (bar.querySelector('input,textarea,[contenteditable="true"]')) return;      /* a composer, not a banner */
      if (r.height <= H * 0.35) {
        tgt = bar;
      } else if ((bar.textContent || '').length < 400) {
        tgt = bar; modal = true;                                                      /* a small dialog */
      }
    }
    if (!tgt) {
      var tag = String(e.tagName).toUpperCase();
      if (tag === 'A' || tag === 'BUTTON' || e.getAttribute('role') === 'button') tgt = e;
    }
    if (!tgt) return;
    r = tgt.getBoundingClientRect();
    var h = r.height;
    atBottom = r.bottom >= H - 3 && r.top > H * 0.4;
    var atTop = r.top <= 3 && r.bottom < H * 0.4;
    if (bar === tgt && !atBottom && !atTop) modal = true;      /* floats in the middle: a dialog, with a dim layer */
    tgt.setAttribute(GONE, '1');
    state.openApp++;
    /* inline button: also drop the now empty wrappers around it */
    if (tgt === e) {
      for (var p = e.parentElement, n = 0; p && p !== document.body && n < 3; p = p.parentElement, n++) {
        if (p.querySelector('input,textarea,img,svg,video,canvas,[' + HIDE + ']')) break;
        if (visibleText(p).trim() !== '') break;
        p.setAttribute(GONE, 'wrap');
      }
    }
    if (modal) dropScrim();
    if (bar === tgt && (atBottom || atTop)) reclaim(h, atBottom);
    paintBadge();
  }

  var LINK_OUT = /^(intent:|fb:|fb-messenger:|market:)|play\.google\.com\/store\/apps/i;
  function sweepOpenApp(root) {
    if (!root || root.nodeType !== 1) return;
    var H = vh(), k, e, t, c = 0;
    var cand = root.querySelectorAll('a,button,[role="button"],[role="link"],div,span');
    for (k = 0; k < cand.length && c < 4000; k++) {
      e = cand[k];
      if (e.childElementCount > 4 || e.hasAttribute(GONE)) continue;
      c++;
      t = e.textContent;
      if (!t || t.length < 3 || t.length > 40) continue;
      if (!isOpenApp(visibleText(e))) continue;
      if (e.childElementCount === 1 && isOpenApp(visibleText(e.firstElementChild))) continue;   /* the inner one acts */
      removeOpenApp(e);
    }
    /* any language: a link that opens the Facebook app / the store, inside a small fixed bar */
    var links = root.querySelectorAll('a[href]');
    for (k = 0; k < links.length && k < 600; k++) {
      e = links[k];
      if (e.hasAttribute(GONE) || e.closest('[' + GONE + ']')) continue;
      if (!LINK_OUT.test(e.getAttribute('href') || '')) continue;
      for (var a = e; a && a !== document.body && a !== document.documentElement; a = a.parentElement) {
        var cs = getComputedStyle(a);
        if (cs.position === 'fixed' || cs.position === 'sticky') {
          if (a.getBoundingClientRect().height <= H * 0.3 && !a.querySelector('input,textarea')) removeOpenApp(e);
          break;
        }
      }
    }
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * scanning
   * ------------------------------------------------------------------------------------------------------------- */
  var queue = [], timer = 0;
  function enqueue(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    if (!el) return;
    if (queue.length < 150 && queue.indexOf(el) < 0) queue.push(el);
    if (!timer) timer = setTimeout(flush, 100);
  }
  function flush() {
    timer = 0;
    var items = queue; queue = [];
    if (items.length > 40) items = [document.body || document.documentElement];
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (!el || !el.isConnected) continue;
      try { var p = postOf(el); if (p) { abp(p); inspect(p); } else { abp(el); } } catch (e) {}
      try { checkLabels(labelEls(el)); } catch (e) {}
      try { sweepOpenApp(el); } catch (e) {}
    }
    scheduleRetry();
  }

  function fullScan() {
    var root = document.body || document.documentElement;
    try { abp(document); } catch (e) {}
    try { checkLabels(labelEls(root)); } catch (e) {}
    try { sweepOpenApp(root); } catch (e) {}
    var posts = document.querySelectorAll('[aria-posinset],[role="article"]');
    for (var i = 0; i < posts.length; i++) {
      var p = postOf(posts[i]);
      if (p) { try { inspect(p); } catch (e) {} }
    }
    scheduleRetry();
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * test badge: tap = copy a short structure summary (paste it back if a Sponsored post still shows)
   * ------------------------------------------------------------------------------------------------------------- */
  function diag() {
    var out = [location.hostname + location.pathname.slice(0, 40),
      'view=' + vw() + 'x' + vh() + ' posinset=' + document.querySelectorAll('[aria-posinset]').length +
      ' article=' + document.querySelectorAll('[role="article"]').length +
      ' tracking=' + document.querySelectorAll('[data-tracking-duration-id]').length,
      'hidden=' + state.hidden + ' ' + JSON.stringify(state.rules) + ' openApp=' + state.openApp];
    var els = labelEls(document.body || document.documentElement), n = 0, i, j;
    var AT = ['role', 'aria-posinset', 'data-ad-rendering-role', 'data-tracking-duration-id', 'data-mcomponent', 'data-type', 'data-pagelet', 'data-focusable'];
    for (i = 0; i < els.length && n < 3; i++) {
      var e = els[i];
      if (e.closest('[' + HIDE + ']')) continue;
      n++;
      var path = [];
      for (var a = e, d = 0; a && a !== document.body && d < 16; a = a.parentElement, d++) {
        var r = a.getBoundingClientRect();
        var t = a.tagName.toLowerCase() + '(' + Math.round(r.width) + 'x' + Math.round(r.height) + ',c' + a.childElementCount + ')';
        for (j = 0; j < AT.length; j++) {
          if (a.hasAttribute(AT[j])) t += '[' + AT[j] + (AT[j] === 'data-tracking-duration-id' ? '' : '=' + String(a.getAttribute(AT[j])).slice(0, 24)) + ']';
        }
        path.push(t);
      }
      var g = geoCard(e), tk = trackCard(e);
      out.push('SHOWN ' + (e.__rvKind || '?') + ' #' + n + ': ' + path.join(' < '));
      out.push('  shape=' + (g ? g.tagName.toLowerCase() : 'none') + ' track=' + (tk ? tk.tagName.toLowerCase() : 'none'));
    }
    return out.join('\n');
  }
  function copyText(text) {
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position:fixed;left:-1000px;top:0;opacity:0';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      if (!ok && navigator.clipboard) navigator.clipboard.writeText(text);
    } catch (e) {}
  }

  var badge = null;
  function paintBadge() {
    if (!SHOW_BADGE) return;
    try {
      if (!badge) {
        badge = document.createElement('div');
        badge.style.cssText = 'position:fixed;left:6px;bottom:6px;z-index:2147483647;padding:2px 6px;' +
          'font:11px/14px sans-serif;color:#fff;background:rgba(0,120,0,.85);border-radius:4px;';
        badge.onclick = function () {
          copyText(diag());
          badge.textContent = 'RV: copied';
          setTimeout(paintBadge, 1500);
        };
        (document.body || document.documentElement).appendChild(badge);
      }
      badge.textContent = 'RV userscript on, hidden: ' + state.hidden;
    } catch (e) {}
  }

  function start() {
    try {
      var st = document.createElement('style');
      st.textContent = '[' + HIDE + '],[' + GONE + ']{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
      paintBadge();
      fullScan();
      new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (m.type === 'childList') {
            for (var j = 0; j < m.addedNodes.length; j++) enqueue(m.addedNodes[j]);
            enqueue(m.target);
          } else {
            enqueue(m.target);
          }
        }
      }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['href', 'aria-label', 'aria-posinset'] });
      setInterval(fullScan, 4000);
    } catch (e) {}
  }

  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start, { once: true });
})();

""".trimIndent()

/** Assembled Injector class (a one-class dex file), Base64. */
private val INJECTOR_DEX = listOf(
    "ZGV4CjAzOAD1RFJJJ+iB239F4WgTAXGOVJKz0ZhZM3+UEwAAcAAAAHhWNBIAAAAAAAAAAPQSAABuAAAAcAAAAB0AAAAoAgAAJQAA",
    "AJwCAAADAAAAWAQAADsAAABwBAAAAQAAAEgGAAAsDQAAaAYAAGgGAABwBgAAgwYAAIYGAACVBgAAmAYAAJsGAACfBgAAowYAAKgG",
    "AADABgAA9gYAABEHAAAsBwAAQQcAAFcHAABsBwAAhQcAAKgHAAC9BwAA5AcAAP4HAAAVCAAANQgAAFUIAABzCAAAjQgAAK0IAADY",
    "CAAADwkAACkJAAA8CQAAUAkAAGYJAAB6CQAAgQkAAI8JAACSCQAAlwkAAJsJAACgCQAApQkAAKkJAACsCQAAsAkAALYJAAC7CQAA",
    "wAkAAM8JAADhCQAA9AkAAP0JAAAFCgAAEAoAAB8KAAApCgAAMgoAAEYKAABUCgAAZQoAAHAKAAB8CgAAiwoAAJUKAACjCgAAsAoA",
    "AL8KAADICgAA1QoAAOQKAADvCgAA9woAAAALAAAGCwAADgsAABcLAAAfCwAAKAsAADALAAA5CwAARQsAAFgLAABtCwAAfwsAAJIL",
    "AACvCwAAwgsAANULAADcCwAA8QsAAAEMAAAODAAAMgwAAEMMAABRDAAAVgwAAF4MAABkDAAAbAwAAIgMAACeDAAAtwwAAM0MAADf",
    "DAAA5wwAAPsMAAANDQAAJw0AADENAAA9DQAAAgAAAAQAAAAJAAAACgAAAAsAAAAMAAAADQAAAA4AAAAPAAAAEAAAABEAAAASAAAA",
    "EwAAABQAAAAVAAAAFgAAABcAAAAYAAAAGQAAABoAAAAbAAAAHAAAAB0AAAAeAAAAHwAAACAAAAAhAAAAJAAAACoAAAACAAAAAAAA",
    "AAAAAAAFAAAACAAAAAAAAAAFAAAACwAAAAAAAAAGAAAACwAAAMQNAAAHAAAACwAAAFANAAAFAAAADgAAAAAAAAAFAAAADwAAAAAA",
    "AAAFAAAAEQAAAAAAAAAIAAAAEgAAAOQNAAAFAAAAFwAAAAAAAAAFAAAAGgAAAAAAAAAkAAAAGwAAAAAAAAAlAAAAGwAAALwNAAAm",
    "AAAAGwAAAKwNAAAnAAAAGwAAAOwNAAAmAAAAGwAAANQNAAAmAAAAGwAAAGgNAAAmAAAAGwAAANwNAAAmAAAAGwAAAJwNAAAmAAAA",
    "GwAAAJQNAAAmAAAAGwAAAFgNAAAnAAAAGwAAAIwNAAAmAAAAGwAAAAAOAAAnAAAAGwAAAGANAAAoAAAAGwAAAHANAAAmAAAAGwAA",
    "AEgNAAAmAAAAGwAAAFANAAAmAAAAGwAAALQNAAAmAAAAGwAAAIQNAAAnAAAAGwAAAMwNAAApAAAAGwAAAAgOAAAqAAAAHAAAAAAA",
    "AAAsAAAAHAAAAPQNAAAuAAAAHAAAAGANAAArAAAAHAAAABAOAAAtAAAAHAAAAKQNAAArAAAAHAAAAIQNAAAVAAIANwAAABUABwBH",
    "AAAAFQAaAEwAAAACAAMAOQAAAAIABQBFAAAABAAPAFsAAAAHABIAAAAAAAcAIwBaAAAABwAbAFwAAAAIAAEAQQAAAAkAAAA7AAAA",
    "CwAEADoAAAALAAAARAAAAAsAHwBdAAAADQAVADIAAAANAAMAPAAAAA0AAAA9AAAADgACAD8AAAAPAAYAQAAAAA8AGABiAAAAEQAe",
    "AGMAAAARAB4AZQAAABEAHABoAAAAEgARAAAAAAASAB8ANAAAABIAHQA4AAAAEgAHAEMAAAASAAsARgAAABIAHABOAAAAEgAeAGQA",
    "AAASABMAZgAAABIAGgBnAAAAEgAZAGkAAAATAAsAAAAAABQADAAAAAAAFQALAAAAAAAVAA0AMwAAABUAFgBJAAAAFQAQAEoAAAAV",
    "ACQASwAAABUACABPAAAAFQAOAFAAAAAVAA0AUQAAABUADQBSAAAAFQANAFMAAAAVAA4AVAAAABUADQBVAAAAFQANAFYAAAAVACAA",
    "VwAAABUAFwBYAAAAFQAXAFkAAAAVAAsAXgAAABUAFABgAAAAFQAKAGEAAAAVACEAagAAABUACgBrAAAAFQAKAG0AAAAXAAoAQgAA",
    "ABgACQA+AAAAGgAiADYAAAAaAAAATQAAABoAJABsAAAAFQAAABEAAAATAAAAeA0AAAMAAAAAAAAAjhIAAAAAAAAGPGluaXQ+ABFG",
    "Yk1haW5UYWJBY3Rpdml0eQABSQANSW5qZWN0b3IuamF2YQABSgABTAACTEkAAkxMAANMTEwAFkxhbmRyb2lkL2FwcC9BY3Rpdml0",
    "eTsANExhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbiRBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrczsAGUxhbmRyb2lkL2FwcC9BcHBs",
    "aWNhdGlvbjsAGUxhbmRyb2lkL2NvbnRlbnQvQ29udGV4dDsAE0xhbmRyb2lkL29zL0J1bmRsZTsAFExhbmRyb2lkL29zL0hhbmRs",
    "ZXI7ABNMYW5kcm9pZC9vcy9Mb29wZXI7ABdMYW5kcm9pZC92aWV3L0tleUV2ZW50OwAhTGFuZHJvaWQvdmlldy9WaWV3JE9uS2V5",
    "TGlzdGVuZXI7ABNMYW5kcm9pZC92aWV3L1ZpZXc7ACVMYW5kcm9pZC92aWV3L1ZpZXdHcm91cCRMYXlvdXRQYXJhbXM7ABhMYW5k",
    "cm9pZC92aWV3L1ZpZXdHcm91cDsAFUxhbmRyb2lkL3ZpZXcvV2luZG93OwAeTGFuZHJvaWQvd2Via2l0L0Nvb2tpZU1hbmFnZXI7",
    "AB5MYW5kcm9pZC93ZWJraXQvVmFsdWVDYWxsYmFjazsAHExhbmRyb2lkL3dlYmtpdC9XZWJTZXR0aW5nczsAGExhbmRyb2lkL3dl",
    "YmtpdC9XZWJWaWV3OwAeTGFuZHJvaWQvd2Via2l0L1dlYlZpZXdDbGllbnQ7AClMYW5kcm9pZC93aWRnZXQvRnJhbWVMYXlvdXQk",
    "TGF5b3V0UGFyYW1zOwA1TGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svdXNlcnNjcmlwdC9JbmplY3RvcjsAGExqYXZh",
    "L2xhbmcvQ2hhclNlcXVlbmNlOwARTGphdmEvbGFuZy9DbGFzczsAEkxqYXZhL2xhbmcvT2JqZWN0OwAUTGphdmEvbGFuZy9SdW5u",
    "YWJsZTsAEkxqYXZhL2xhbmcvU3RyaW5nOwAFTG9naW4ADFJlZ2lzdHJhdGlvbgABVgADVklJAAJWTAADVkxMAANWTFoAAlZaAAFa",
    "AAJaTAAEWkxJTAADWkxKAANaTEwADV9fUlZfU0NSSVBUX18AEF9fUlZfU1RBUlRfVVJMX18AEV9fUlZfVVNFUl9BR0VOVF9fAAdh",
    "ZGRWaWV3AAZhdHRhY2gACWNhbkdvQmFjawANY29tLmZhY2Vib29rLgAIY29udGFpbnMAB2N1cnJlbnQAEmV2YWx1YXRlSmF2YXNj",
    "cmlwdAAMZmluZFZpZXdCeUlkAA9maW5kVmlld1dpdGhUYWcACWdldEFjdGlvbgAKZ2V0Q2hpbGRBdAANZ2V0Q2hpbGRDb3VudAAI",
    "Z2V0Q2xhc3MADGdldERlY29yVmlldwALZ2V0SW5zdGFuY2UADWdldE1haW5Mb29wZXIAB2dldE5hbWUAC2dldFNldHRpbmdzAA1n",
    "ZXRWaXNpYmlsaXR5AAlnZXRXaW5kb3cABmdvQmFjawAHaGFuZGxlcgAEaHR0cAAGaW5qZWN0AAdpbnN0YWxsAAZpc0hvc3QAB2xh",
    "c3RVcmwABmxlbmd0aAAHbG9hZFVybAAKbmV3V2ViVmlldwARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0cm95ZWQA",
    "EG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9uQWN0aXZp",
    "dHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAAFb25LZXkAE29uUGFnZUNvbW1pdFZpc2libGUADm9uUGFnZUZpbmlzaGVkAAtw",
    "b3N0RGVsYXllZAAicmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2FsbGJhY2tzAAxyZXF1ZXN0Rm9j",
    "dXMAA3J1bgAGcnYtd2ViAARzY2FuAAZzY3JpcHQAGnNldEFjY2VwdFRoaXJkUGFydHlDb29raWVzABRzZXREb21TdG9yYWdlRW5h",
    "YmxlZAAXc2V0Rm9jdXNhYmxlSW5Ub3VjaE1vZGUAFHNldEphdmFTY3JpcHRFbmFibGVkABBzZXRPbktleUxpc3RlbmVyAAZzZXRU",
    "YWcAEnNldFVzZXJBZ2VudFN0cmluZwAQc2V0V2ViVmlld0NsaWVudAAYc2hvdWxkT3ZlcnJpZGVVcmxMb2FkaW5nAAhzdGFydFVy",
    "bAAKc3RhcnRzV2l0aAAJdXNlckFnZW50AAEAAAATAAAAAQAAABgAAAABAAAACwAAAAIAAAASABoAAQAAAAQAAAACAAAAEgAcAAMA",
    "AAADAAoAGQAAAAEAAAAaAAAAAgAAAAsADAABAAAACgAAAAEAAAAIAAAAAgAAABkAAQABAAAAAgAAAAEAAAAZAAAAAgAAAAAAAAAB",
    "AAAAAAAAAAIAAAAaABAAAQAAAAMAAAABAAAABQAAAAIAAAACABUAAgAAAAIABgADAAAACwAAAAkAAAABAAAAEgAAAAEAAAAcAAAA",
    "AQAAABYAAAAAAAAAAwABAAIAAAAAAAAADwAAAHAQHgACACIABwBxAAYAAAAMAXAgAwAQAFsgAQAOAAAABwACAAMAAQAAAAAAOgAA",
    "AHEANAAAAAwAbhA5AAAACgQ4BC8AFAQCAAIBbiAAAEYADAEgFA0AOAQkAB8BDQAaBF8AbiAIAEEADAQ5BBoAcSAlAFYADAJiAwIA",
    "OAMDAAcwIgMUABL0cDAfAEMEbjALACEDbiAZAAIAbhAKAAIADgANACj+AAAAADcAAQABADgAAwABAAMAAQAAAAAACwAAAHEAMgAA",
    "AAwAEgFuMBYAAgEOAA0AKP4AAAAAAAAIAAEAAQAJAAMAAQACAAEAAAAAAAsAAAAiABUAcBAgAAAAbiACAAIADgANACj+AAAAAAAA",
    "CAABAAEACQADAAEAAgAAAAAAAAAkAAAAGgA1AG4gOgACAAoBOAEaABoAAQBuIDgAAgAKATkBFAAaACIAbiA4AAIACgE5AQwAGgAj",
    "AG4gOAACAAoBOQEEABIADwASEA8ABgACAAMAAAAAAAAANAAAACIAEgBwIBQAQAAaAV8AbiAcABAAbhAXAAAADAESEm4gEgAhAG4g",
    "EQAhAHEANQAAAAwDbhA5AAMACgI4AgUAbiATADEAbiAdAFAAbiAbAFAAEhJuIBoAIABxAA8AAAAMA24wEAADAhEABQABAAIAAAAA",
    "AAAALAAAAG4QCQAEAAoAEwEIADIQJQAgQBIAOAAIAB8EEgBxECIABAAOACBADQA4ABcAHwQNAG4QDQAEAAoAEgE1AQ4AbiAMABQA",
    "DAI4AgUAcRAxAAIA2AEBASjzDgABAAAAAAAAAAAAAAADAAAAGgAvABEAAAABAAAAAAAAAAAAAAADAAAAGgAwABEAAAABAAAAAAAA",
    "AAAAAAADAAAAGgAxABEAAAADAAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAABAACAAIAAAAAAAAACQAAAFQg",
    "AQBuIAUAIAASAVshAAAOAAAABQACAAQAAAAAAAAAHgAAAFs0AABuEDcABAAMAG4QNgAAAAwAcRAkAAAACgA4AAUAcCAhAEMAVDAB",
    "AG4gBQAwABYBIANuQAQAMCEOAAMAAwAAAAAAAAAAAAEAAAAOAAAAAgACAAAAAAAAAAAAAQAAAA4AAAACAAIAAAAAAAAAAAABAAAA",
    "DgAAAAcABAABAAAAAAAAABwAAAASABJBMxUZAG4QBwAGAAoBEhIzIRIAIEESADgBDgAfBBIAbhAVAAQACgE4AQYAbhAYAAQAEhAP",
    "AAMAAwABAAAAAAAAAAQAAABxECIAAQAOAAMAAwABAAAAAAAAAAYAAABpAgIAcRAiAAEADgAEAAEABAABAAAAAAAaAAAAVDAAADkA",
    "AwAOAG4QAQAAAAwBbhAOAAEADAFxEDEAAQBUMAEAFgHcBW5ABAAwIQ4ADQAo9wUAAAALAAEAAQAYAAUAAwACAAAAAAAAAA0AAAA5",
    "BAQAEhAPABoASABuIDoABAAKAN8AAAEPAAECCgwCCgACARIggoAEnBwBAswcAQrcHQEJkB4BCsQeAQqcHwwKlCABCfwgAgmUIQEJ",
    "rCEmAcQhAQHYIQEB7CEBAZAiAQHcIgEB8CIBAYQjAQGYIwEB4CMBAfgjAQGUJAMB5CQAAA0AAAAAAAAAAQAAAAAAAAABAAAAbgAA",
    "AHAAAAACAAAAHQAAACgCAAADAAAAJQAAAJwCAAAEAAAAAwAAAFgEAAAFAAAAOwAAAHAEAAAGAAAAAQAAAEgGAAACIAAAbgAAAGgG",
    "AAABEAAAGQAAAEgNAAADEAAAAQAAABgOAAABIAAAFgAAABwOAAAAIAAAAQAAAI4SAAAAEAAAAQAAAPQSAAA=",
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
val injectUserscriptPatch = bytecodePatch(
    name = "Inject userscript to block sponsored posts on android 8 app",
    description = "Opens m.facebook.com (phone user agent) in a WebView over the app's main and login screens and " +
        "runs a userscript in it (and in the in-app browser) that hides Sponsored / suggested posts and the Open app " +
        "bar. Adds no file to the apk and touches no resource.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val showBadge by booleanOption(
        name = "Show test badge",
        description = "Shows a small green 'RV userscript on, hidden: N' label at the bottom left of every facebook.com " +
            "page in a WebView (tap it to copy a short report of the page structure). Turn it off when everything works.",
        default = false,
    )

    apply {
        val log = Logger.getLogger("InjectUserscript")

        if (classDefs[INJECTOR] != null) {
            log.info("Inject userscript: Injector is already in the dex, nothing to add")
            return@apply
        }

        // ---- 1. Find the hook (read only) ------------------------------------------------------------------------
        val hookClass = findHookClass()
            ?: throw PatchException(
                "Inject userscript: no Application.onCreate() found ($APP_CLASS or a class extending " +
                    "$ANDROID_APPLICATION). Send the smali of the app's Application class.",
            )

        // ---- 2. Add the Injector class into the existing dex -------------------------------------------------------
        val injector = try {
            DexBackedDexFile(null, Base64.getDecoder().decode(INJECTOR_DEX), 0).classes.single()
        } catch (e: Exception) {
            throw PatchException("Inject userscript: cannot read the embedded Injector class: $e")
        }
        @Suppress("UNCHECKED_CAST")
        val mutableClassDefs = classDefs as MutableSet<ClassDef>
        if (!mutableClassDefs.add(injector)) {
            throw PatchException("Inject userscript: could not add the Injector class to the dex")
        }

        // ---- 3. Put the userscript, the start url and the user agent into the Injector ----------------------------
        val script = USERSCRIPT.replace("__BADGE__", if (showBadge == true) "true" else "false")

        val mutableInjector = classDefs.getOrReplaceMutable(injector)
        fun MutableMethod.setText(text: String) = replaceInstruction(0, "const-string v0, \"${smaliString(text)}\"")
        for ((method, text) in listOf("script" to script, "startUrl" to START_URL, "userAgent" to USER_AGENT)) {
            val target = mutableInjector.methods.firstOrNull {
                it.name == method && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            } ?: throw PatchException("Inject userscript: Injector.$method() not found")
            target.setText(text)
        }

        // ---- 4. One call at the very top of the existing Application.onCreate() ----------------------------------
        val onCreate = classDefs.getOrReplaceMutable(hookClass).methods.first {
            it.name == "onCreate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty() &&
                it.implementation != null &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        onCreate.addInstructions(0, "invoke-static {p0}, $INJECTOR->install(Landroid/app/Application;)V")

        log.info(
            "Inject userscript: hooked ${hookClass.type}->onCreate(), userscript ${script.length} chars, " +
                "start page $START_URL, test badge ${if (showBadge == true) "on" else "off"}",
        )
    }
}
