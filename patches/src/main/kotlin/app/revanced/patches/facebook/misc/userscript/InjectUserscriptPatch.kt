package app.revanced.patches.facebook.misc.userscript

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.util.Base64
import java.util.logging.Logger

/*
 * Inject userscript to block sponsored posts on android 8 apk app
 *
 * ONE self-contained file. It adds NO file to the apk (no asset, no resource, no new dex, no library, no manifest
 * change): everything is
 *   1. one small class, "Injector", added to the dex the app already has (classes.dex),
 *   2. the userscript, the start url and the user agent, stored as strings inside that class,
 *   3. one call at the top of the Application.onCreate() that already exists.
 *
 * What it does at run time (inside the app process):
 *   a. START PAGE (idea taken from the "Cobalt-Start-URL aendern" patch of the YouTube TV bundle, which repoints the
 *      app at another url): when the main screen of the app (FbMainTabActivity, the target of the launcher alias in
 *      the manifest) is resumed, a WebView with the "Start URL" option (default https://m.facebook.com/) and the
 *      "User agent" option (default: a phone Chrome) is laid over it. Back goes back inside the page first. Links that
 *      are not http(s) (fb://, intent://) are ignored. Leave "Start URL" empty to keep the native screen.
 *   b. USERSCRIPT: while an activity is resumed, every 1.5 s the view tree is walked, and every android.webkit.WebView
 *      found (the start page, the in-app browser) runs the userscript with evaluateJavascript(); the page-finished
 *      callback of the start page runs it too. The first line of the userscript makes every repeat a no-op and the
 *      script only acts on facebook.com / fb.com pages.
 *   No hidden Facebook class is touched, so nothing depends on the obfuscated names of a build.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * LIMIT: the native News Feed of the app is not a web page, so the userscript cannot touch it. That is why (a)
 * exists: the feed the user reads is then the web feed, which the userscript can filter.
 *
 * ---- Injector source (assembled with smali; the text below is what the Base64 further down contains) ----------------
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
 * # ---- Application.ActivityLifecycleCallbacks -----------------------------------------------------------------------------
 *
 * .method public onActivityResumed(Landroid/app/Activity;)V
 *     .locals 3
 *     iput-object p1, p0, Lapp/revanced/extension/facebook/userscript/Injector;->current:Landroid/app/Activity;
 *     invoke-virtual {p1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
 *     move-result-object v0
 *     invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
 *     move-result-object v0
 *     const-string v1, "com.facebook.katana.activity.FbMainTabActivity"
 *     invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
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
private const val DEFAULT_START_URL = "https://m.facebook.com/"
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** Userscript. "__BADGE__" is replaced by true/false (patch option). Written without the dollar sign on purpose. */
private val USERSCRIPT = """
/* RV: hide Sponsored posts on facebook.com pages shown inside the Android 8 Facebook app.
 * Derived from "FB - Clean my feeds" (GPL-3.0, Artificial Sweetener, zbluebugz, Quoc Viet Rtinh):
 *   - /ads/about/ disclosure link, svg disclosure, sponsored label in every language of the original
 *   - the filter  facebook.com##[data-ad-rendering-role="profile_name"]:upward([aria-posinset]:has(
 *       [data-ad-rendering-role="story_message"]):has([data-ad-rendering-role^="cta-"]))
 * Injected again by the app every ~1.5 s; the first line makes every later injection a no-op. */
(function () {
  'use strict';
  try {
    if (window.__rvFbSponsorBlock) return;
    var host = String(location.hostname || '');
    if (!/(^|\.)(facebook\.com|fb\.com|facebookcorewwwi\.onion)${'$'}/.test(host)) return;
    window.__rvFbSponsorBlock = { hidden: 0, rules: {}, started: Date.now() };
  } catch (e) { return; }

  var SHOW_BADGE = __BADGE__;
  var HIDE = 'data-rv-sponsored';
  var LABELS = ["Sponsored","\u0645\u064f\u0645\u0648\u064e\u0651\u0644","\u0421\u043f\u043e\u043d\u0441\u043e\u0440\u0438\u0440\u0430\u043d\u043e","Sponzorov\u00e1no","Gesponsert","Anzeige","\u03a7\u03bf\u03c1\u03b7\u03b3\u03bf\u03cd\u03bc\u03b5\u03bd\u03b7","Publicidad","Sponsoroitu","Sponsoris\u00e9","\u05de\u05de\u05d5\u05de\u05df","Bersponsor","Sponsorizzato","\u5e83\u544a","Apmaks\u0101ta rekl\u0101ma","Gesponsord","Sponsorowane","Patrocinado","\u0420\u0435\u043a\u043b\u0430\u043c\u0430","Sponsorlu","\u0421\u043f\u043e\u043d\u0441\u043e\u0440\u043e\u0432\u0430\u043d\u0430","\u0110\u01b0\u1ee3c t\u00e0i tr\u1ee3","\u8d5e\u52a9\u5185\u5bb9","\u8d0a\u52a9","Ad","\u0625\u0639\u0644\u0627\u0646","Reklama","Werbung","\u0394\u03b9\u03b1\u03c6\u03ae\u03bc\u03b9\u03c3\u03b7","Anuncio","Mainos","Publicit\u00e9","Pub","\u05de\u05d5\u05d3\u05e2\u05d4","Iklan","Inserzione","Pubblicit\u00e0","Rekl\u0101ma","Advertentie","An\u00fancio","Reklam","Qu\u1ea3ng c\u00e1o","\u5e7f\u544a","\u5ee3\u544a","C\u00f3 t\u00e0i tr\u1ee3"];
  var state = window.__rvFbSponsorBlock;

  function norm(s) {
    s = String(s == null ? '' : s);
    try { s = s.normalize('NFKC'); } catch (e) {}
    return s.replace(/[​-‏‪-‮⁦-⁩﻿]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
  }
  var SET = Object.create(null);
  for (var i = 0; i < LABELS.length; i++) SET[norm(LABELS[i])] = 1;
  function isLabel(s) { return SET[norm(s)] === 1; }

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

  function hide(el, rule) {
    if (!el || el.hasAttribute(HIDE)) return;
    el.setAttribute(HIDE, rule);
    state.hidden++;
    state.rules[rule] = (state.rules[rule] || 0) + 1;
    paintBadge();
  }

  /* ABP filter: closest ancestor of profile_name that is [aria-posinset] with story_message and a cta-* node */
  function abp(root) {
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

  /* label based rules for one post */
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

  /* m.facebook.com (phone user agent): no aria-posinset / role=article. A story is an element carrying
   * data-tracking-duration-id. From the label upwards take the outermost one that holds no OTHER such element
   * (a wrapper around several stories is not a story) and is not taller than two screens. NOT verified on the real
   * mobile site: tap the green badge to copy a short structure summary. */
  function mobileRoot(el) {
    var best = null, vh = window.innerHeight || 800, SEL = '[data-tracking-duration-id]';
    for (var a = el.closest(SEL); a; a = a.parentElement ? a.parentElement.closest(SEL) : null) {
      var inner = a.querySelectorAll(SEL), foreign = false, i;
      for (i = 0; i < inner.length; i++) {
        if (!inner[i].contains(el)) { foreign = true; break; }
      }
      if (foreign) break;
      if (a.getBoundingClientRect().height <= vh * 2) best = a;
    }
    return best;
  }

  var lastLabelScan = 0;
  function scanLabels(force) {
    var now = Date.now();
    if (!force && now - lastLabelScan < 1000) return;
    lastLabelScan = now;
    if (!document.querySelector('[data-tracking-duration-id]')) return;
    var leaves = document.querySelectorAll('span,div,h3,h4,h5,strong'), i;
    for (i = 0; i < leaves.length && i < 3000; i++) {
      var e = leaves[i];
      if (e.firstElementChild) continue;
      var tl = (e.textContent || '').length;
      if (tl < 5 || tl > 30) continue;
      if (!isLabel(visibleText(e)) || inBody(e)) continue;
      var r = mobileRoot(e);
      if (r) hide(r, 'mobile-label');
    }
  }

  var queue = [], timer = 0;
  function enqueue(el) {
    if (!el || el.nodeType !== 1) el = el && el.parentElement;
    if (!el) return;
    var p = postOf(el);
    if (p && queue.indexOf(p) < 0 && !p.hasAttribute(HIDE)) queue.push(p);
    if (!timer) timer = setTimeout(flush, 120);
  }
  function flush() {
    timer = 0;
    var items = queue; queue = [];
    for (var i = 0; i < items.length; i++) { try { abp(items[i]); inspect(items[i]); } catch (e) {} }
    try { scanLabels(false); } catch (e) {}
  }

  function fullScan() {
    try { abp(document); } catch (e) {}
    try { scanLabels(true); } catch (e) {}
    var posts = document.querySelectorAll('[aria-posinset],[role="article"]');
    for (var i = 0; i < posts.length; i++) {
      var p = postOf(posts[i]);
      if (p) { try { inspect(p); } catch (e) {} }
    }
  }

  function diag() {
    var out = [location.hostname + location.pathname.slice(0, 40),
      'posinset=' + document.querySelectorAll('[aria-posinset]').length +
      ' article=' + document.querySelectorAll('[role="article"]').length +
      ' tracking=' + document.querySelectorAll('[data-tracking-duration-id]').length,
      'hidden=' + state.hidden + ' ' + JSON.stringify(state.rules)];
    var leaves = document.querySelectorAll('span,div,a,h3,h4,h5,strong'), n = 0, i, j;
    var AT = ['role', 'aria-posinset', 'data-ad-rendering-role', 'data-tracking-duration-id', 'data-mcomponent', 'data-type', 'data-pagelet'];
    for (i = 0; i < leaves.length && n < 3; i++) {
      var e = leaves[i];
      if (e.firstElementChild || !isLabel(visibleText(e))) continue;
      n++;
      var path = [];
      for (var a = e, d = 0; a && a !== document.body && d < 14; a = a.parentElement, d++) {
        var t = a.tagName.toLowerCase();
        for (j = 0; j < AT.length; j++) {
          if (a.hasAttribute(AT[j])) t += '[' + AT[j] + (AT[j] === 'data-tracking-duration-id' ? '' : '=' + String(a.getAttribute(AT[j])).slice(0, 24)) + ']';
        }
        path.push(t + (a.hasAttribute(HIDE) ? '(hidden)' : ''));
      }
      out.push('label#' + n + ': ' + path.join(' < '));
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
      st.textContent = '[' + HIDE + ']{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
      paintBadge();
      fullScan();
      new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (m.type === 'childList') {
            for (var j = 0; j < m.addedNodes.length; j++) {
              var n = m.addedNodes[j];
              if (n.nodeType === 1) { try { abp(n); } catch (e) {} enqueue(n); }
            }
            enqueue(m.target);
            if (!timer) timer = setTimeout(flush, 120);
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
    "ZGV4CjAzOADPJbwFATh1KxN+NoXDsu6TgfOU+Zoc0DrsEgAAcAAAAHhWNBIAAAAAAAAAAEwSAABpAAAAcAAAABwAAAAUAgAAJQAA",
    "AIQCAAADAAAAQAQAADoAAABYBAAAAQAAACgGAACkDAAASAYAAEgGAABQBgAAUwYAAGIGAABlBgAAaAYAAGwGAABwBgAAdQYAAI0G",
    "AADDBgAA3gYAAPkGAAAOBwAAJAcAADkHAABSBwAAdQcAAIoHAACxBwAAywcAAOIHAAACCAAAIggAAEAIAABaCAAAeggAAKUIAADc",
    "CAAA7wgAAAMJAAAZCQAALQkAADAJAAA1CQAAOQkAAD4JAABDCQAARwkAAEoJAABOCQAAVAkAAFkJAABeCQAAbQkAAH8JAACSCQAA",
    "mwkAAKMJAACuCQAA3gkAAOcJAADvCQAAAwoAABEKAAAiCgAALQoAADkKAABICgAAUgoAAGAKAABtCgAAfAoAAIUKAACSCgAAoQoA",
    "AKwKAAC0CgAAvQoAAMMKAADLCgAA1AoAAN0KAADlCgAA7goAAPoKAAANCwAAIgsAADQLAABHCwAAZAsAAHcLAACKCwAAkQsAAKYL",
    "AAC2CwAAwwsAAOcLAAD4CwAABgwAAAsMAAATDAAAGQwAACEMAAA9DAAAUwwAAGwMAACCDAAAlAwAAJwMAACwDAAAwgwAANwMAADm",
    "DAAA8gwAAAEAAAADAAAACAAAAAkAAAAKAAAACwAAAAwAAAANAAAADgAAAA8AAAAQAAAAEQAAABIAAAATAAAAFAAAABUAAAAWAAAA",
    "FwAAABgAAAAZAAAAGgAAABsAAAAcAAAAHQAAAB4AAAAfAAAAIAAAACYAAAABAAAAAAAAAAAAAAAEAAAACAAAAAAAAAAEAAAACwAA",
    "AAAAAAAFAAAACwAAAHwNAAAGAAAACwAAAAgNAAAEAAAADgAAAAAAAAAEAAAADwAAAAAAAAAEAAAAEQAAAAAAAAAHAAAAEgAAAJwN",
    "AAAEAAAAFgAAAAAAAAAEAAAAGQAAAAAAAAAgAAAAGgAAAAAAAAAhAAAAGgAAAHQNAAAiAAAAGgAAAGQNAAAjAAAAGgAAAKQNAAAi",
    "AAAAGgAAAIwNAAAiAAAAGgAAACANAAAiAAAAGgAAAJQNAAAiAAAAGgAAAFQNAAAiAAAAGgAAAEwNAAAiAAAAGgAAABANAAAjAAAA",
    "GgAAAEQNAAAiAAAAGgAAALgNAAAjAAAAGgAAABgNAAAkAAAAGgAAACgNAAAiAAAAGgAAAAANAAAiAAAAGgAAAAgNAAAiAAAAGgAA",
    "AGwNAAAiAAAAGgAAADwNAAAjAAAAGgAAAIQNAAAlAAAAGgAAAMANAAAmAAAAGwAAAAAAAAAoAAAAGwAAAKwNAAAqAAAAGwAAABgN",
    "AAAnAAAAGwAAAAgNAAApAAAAGwAAAFwNAAAnAAAAGwAAADwNAAAVAAIAMgAAABUABwBDAAAAFQAZAEcAAAACAAMANQAAAAIABQBB",
    "AAAABAAPAFYAAAAHABIAAAAAAAcAIwBVAAAABwAbAFcAAAAIAAEAPQAAAAkAAAA3AAAACwAEADYAAAALAAAAQAAAAAsAHwBYAAAA",
    "DQAVAC4AAAANAAMAOAAAAA0AAAA5AAAADgACADsAAAAPAAYAPAAAAA8AGABdAAAAEQAeAF4AAAARAB4AYAAAABEAHABjAAAAEgAR",
    "AAAAAAASAB8AMAAAABIAHQA0AAAAEgAHAD8AAAASAAsAQgAAABIAHABJAAAAEgAeAF8AAAASABMAYQAAABIAGgBiAAAAEgAZAGQA",
    "AAATAAsAAAAAABQADAAAAAAAFQALAAAAAAAVAA0ALwAAABUAFgBFAAAAFQAQAEYAAAAVAAgASgAAABUADgBLAAAAFQANAEwAAAAV",
    "AA0ATQAAABUADQBOAAAAFQAOAE8AAAAVAA0AUAAAABUADQBRAAAAFQAgAFIAAAAVABcAUwAAABUAFwBUAAAAFQALAFkAAAAVABQA",
    "WwAAABUACgBcAAAAFQAhAGUAAAAVAAoAZgAAABUACgBoAAAAFgAKAD4AAAAXAAkAOgAAABkAIgAzAAAAGQAAAEgAAAAZACQAZwAA",
    "ABUAAAARAAAAEwAAADANAAACAAAAAAAAAOoRAAAAAAAABjxpbml0PgABSQANSW5qZWN0b3IuamF2YQABSgABTAACTEkAAkxMAANM",
    "TEwAFkxhbmRyb2lkL2FwcC9BY3Rpdml0eTsANExhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbiRBY3Rpdml0eUxpZmVjeWNsZUNhbGxi",
    "YWNrczsAGUxhbmRyb2lkL2FwcC9BcHBsaWNhdGlvbjsAGUxhbmRyb2lkL2NvbnRlbnQvQ29udGV4dDsAE0xhbmRyb2lkL29zL0J1",
    "bmRsZTsAFExhbmRyb2lkL29zL0hhbmRsZXI7ABNMYW5kcm9pZC9vcy9Mb29wZXI7ABdMYW5kcm9pZC92aWV3L0tleUV2ZW50OwAh",
    "TGFuZHJvaWQvdmlldy9WaWV3JE9uS2V5TGlzdGVuZXI7ABNMYW5kcm9pZC92aWV3L1ZpZXc7ACVMYW5kcm9pZC92aWV3L1ZpZXdH",
    "cm91cCRMYXlvdXRQYXJhbXM7ABhMYW5kcm9pZC92aWV3L1ZpZXdHcm91cDsAFUxhbmRyb2lkL3ZpZXcvV2luZG93OwAeTGFuZHJv",
    "aWQvd2Via2l0L0Nvb2tpZU1hbmFnZXI7AB5MYW5kcm9pZC93ZWJraXQvVmFsdWVDYWxsYmFjazsAHExhbmRyb2lkL3dlYmtpdC9X",
    "ZWJTZXR0aW5nczsAGExhbmRyb2lkL3dlYmtpdC9XZWJWaWV3OwAeTGFuZHJvaWQvd2Via2l0L1dlYlZpZXdDbGllbnQ7AClMYW5k",
    "cm9pZC93aWRnZXQvRnJhbWVMYXlvdXQkTGF5b3V0UGFyYW1zOwA1TGFwcC9yZXZhbmNlZC9leHRlbnNpb24vZmFjZWJvb2svdXNl",
    "cnNjcmlwdC9JbmplY3RvcjsAEUxqYXZhL2xhbmcvQ2xhc3M7ABJMamF2YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcvUnVubmFi",
    "bGU7ABJMamF2YS9sYW5nL1N0cmluZzsAAVYAA1ZJSQACVkwAA1ZMTAADVkxaAAJWWgABWgACWkwABFpMSUwAA1pMSgADWkxMAA1f",
    "X1JWX1NDUklQVF9fABBfX1JWX1NUQVJUX1VSTF9fABFfX1JWX1VTRVJfQUdFTlRfXwAHYWRkVmlldwAGYXR0YWNoAAljYW5Hb0Jh",
    "Y2sALmNvbS5mYWNlYm9vay5rYXRhbmEuYWN0aXZpdHkuRmJNYWluVGFiQWN0aXZpdHkAB2N1cnJlbnQABmVxdWFscwASZXZhbHVh",
    "dGVKYXZhc2NyaXB0AAxmaW5kVmlld0J5SWQAD2ZpbmRWaWV3V2l0aFRhZwAJZ2V0QWN0aW9uAApnZXRDaGlsZEF0AA1nZXRDaGls",
    "ZENvdW50AAhnZXRDbGFzcwAMZ2V0RGVjb3JWaWV3AAtnZXRJbnN0YW5jZQANZ2V0TWFpbkxvb3BlcgAHZ2V0TmFtZQALZ2V0U2V0",
    "dGluZ3MADWdldFZpc2liaWxpdHkACWdldFdpbmRvdwAGZ29CYWNrAAdoYW5kbGVyAARodHRwAAZpbmplY3QAB2luc3RhbGwAB2xh",
    "c3RVcmwABmxlbmd0aAAHbG9hZFVybAAKbmV3V2ViVmlldwARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0cm95ZWQA",
    "EG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9uQWN0aXZp",
    "dHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAAFb25LZXkAE29uUGFnZUNvbW1pdFZpc2libGUADm9uUGFnZUZpbmlzaGVkAAtw",
    "b3N0RGVsYXllZAAicmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2FsbGJhY2tzAAxyZXF1ZXN0Rm9j",
    "dXMAA3J1bgAGcnYtd2ViAARzY2FuAAZzY3JpcHQAGnNldEFjY2VwdFRoaXJkUGFydHlDb29raWVzABRzZXREb21TdG9yYWdlRW5h",
    "YmxlZAAXc2V0Rm9jdXNhYmxlSW5Ub3VjaE1vZGUAFHNldEphdmFTY3JpcHRFbmFibGVkABBzZXRPbktleUxpc3RlbmVyAAZzZXRU",
    "YWcAEnNldFVzZXJBZ2VudFN0cmluZwAQc2V0V2ViVmlld0NsaWVudAAYc2hvdWxkT3ZlcnJpZGVVcmxMb2FkaW5nAAhzdGFydFVy",
    "bAAKc3RhcnRzV2l0aAAJdXNlckFnZW50AAAAAAEAAAATAAAAAQAAABcAAAABAAAACwAAAAIAAAASABkAAQAAAAQAAAACAAAAEgAb",
    "AAMAAAADAAoAGAAAAAEAAAAZAAAAAgAAAAsADAABAAAACgAAAAEAAAAIAAAAAgAAABgAAQABAAAAAgAAAAEAAAAYAAAAAgAAAAAA",
    "AAABAAAAAAAAAAIAAAAZABAAAQAAAAMAAAABAAAABQAAAAIAAAACABUAAgAAAAIABgADAAAACwAAAAkAAAABAAAAEgAAAAEAAAAb",
    "AAAAAAAAAAMAAQACAAAAAAAAAA8AAABwEB4AAgAiAAcAcQAGAAAADAFwIAMAEABbIAEADgAAAAcAAgADAAEAAAAAADoAAABxADMA",
    "AAAMAG4QOAAAAAoEOAQvABQEAgACAW4gAABGAAwBIBQNADgEJAAfAQ0AGgRaAG4gCABBAAwEOQQaAHEgJABWAAwCYgMCADgDAwAH",
    "MCIDFAAS9HAwHwBDBG4wCwAhA24gGQACAG4QCgACAA4ADQAo/gAAAAA3AAEAAQA4AAMAAQADAAEAAAAAAAsAAABxADEAAAAMABIB",
    "bjAWAAIBDgANACj+AAAAAAAACAABAAEACQADAAEAAgABAAAAAAALAAAAIgAVAHAQIAAAAG4gAgACAA4ADQAo/gAAAAAAAAgAAQAB",
    "AAkABgACAAMAAAAAAAAANAAAACIAEgBwIBQAQAAaAVoAbiAcABAAbhAXAAAADAESEm4gEgAhAG4gEQAhAHEANAAAAAwDbhA4AAMA",
    "CgI4AgUAbiATADEAbiAdAFAAbiAbAFAAEhJuIBoAIABxAA8AAAAMA24wEAADAhEABQABAAIAAAAAAAAALAAAAG4QCQAEAAoAEwEI",
    "ADIQJQAgQBIAOAAIAB8EEgBxECIABAAOACBADQA4ABcAHwQNAG4QDQAEAAoAEgE1AQ4AbiAMABQADAI4AgUAcRAwAAIA2AEBASjz",
    "DgABAAAAAAAAAAAAAAADAAAAGgArABEAAAABAAAAAAAAAAAAAAADAAAAGgAsABEAAAABAAAAAAAAAAAAAAADAAAAGgAtABEAAAAD",
    "AAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAABAACAAIAAAAAAAAACQAAAFQgAQBuIAUAIAASAVshAAAOAAAA",
    "BQACAAQAAAAAAAAAIAAAAFs0AABuEDYABAAMAG4QNQAAAAwAGgExAG4gNwABAAoAOAAFAHAgIQBDAFQwAQBuIAUAMAAWASADbkAE",
    "ADAhDgADAAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAAAgACAAAAAAAAAAAAAQAAAA4AAAAHAAQAAQAAAAAA",
    "AAAcAAAAEgASQTMVGQBuEAcABgAKARISMyESACBBEgA4AQ4AHwQSAG4QFQAEAAoBOAEGAG4QGAAEABIQDwADAAMAAQAAAAAAAAAE",
    "AAAAcRAiAAEADgADAAMAAQAAAAAAAAAGAAAAaQICAHEQIgABAA4ABAABAAQAAQAAAAAAGgAAAFQwAAA5AAMADgBuEAEAAAAMAW4Q",
    "DgABAAwBcRAwAAEAVDABABYB3AVuQAQAMCEOAA0AKPcFAAAACwABAAEAGAAFAAMAAgAAAAAAAAANAAAAOQQEABIQDwAaAEQAbiA5",
    "AAQACgDfAAABDwABAgkMAgoAAgESIIKABMwbAQL8GwEKjB0BCcAdAQr0HQwK7B4BCdQfAgnsHwEJhCAlAZwgAQGwIAEBxCABAegg",
    "AQG4IQEBzCEBAeAhAQH0IQEBvCIBAdQiAQHwIgMBwCMAAA0AAAAAAAAAAQAAAAAAAAABAAAAaQAAAHAAAAACAAAAHAAAABQCAAAD",
    "AAAAJQAAAIQCAAAEAAAAAwAAAEAEAAAFAAAAOgAAAFgEAAAGAAAAAQAAACgGAAACIAAAaQAAAEgGAAABEAAAGAAAAAANAAADEAAA",
    "AQAAAMgNAAABIAAAFQAAAMwNAAAAIAAAAQAAAOoRAAAAEAAAAQAAAEwSAAA=",
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
    description = "Opens m.facebook.com (phone user agent) in a WebView over the app's main screen and runs a " +
        "userscript in it (and in the in-app browser) that hides Sponsored posts. Adds no file to the apk. " +
        "Leave Start URL empty to keep the native screen: the userscript then only reaches the in-app browser.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val startUrl by stringOption(
        name = "Start URL",
        default = DEFAULT_START_URL,
        description = "Page shown over the app's main screen at launch. Must start with http:// or https://. " +
            "Empty = keep the native screen.",
    )

    val userAgent by stringOption(
        name = "User agent",
        default = DEFAULT_USER_AGENT,
        description = "User agent of that page, so Facebook serves its phone site. Empty = the WebView's own.",
    )

    val showBadge by booleanOption(
        name = "Show test badge",
        description = "Shows a small green 'RV userscript on, hidden: N' label at the bottom left of every facebook.com " +
            "page in a WebView, so you can see that the userscript runs. Turn it off after testing.",
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
        val start = (startUrl ?: DEFAULT_START_URL).trim()
        if (start.isNotEmpty() && !start.startsWith("https://") && !start.startsWith("http://")) {
            throw PatchException("Inject userscript: option 'Start URL' must start with http:// or https://, got: $start")
        }
        val agent = (userAgent ?: DEFAULT_USER_AGENT).trim()
        val script = USERSCRIPT.replace("__BADGE__", if (showBadge == true) "true" else "false")

        val mutableInjector = classDefs.getOrReplaceMutable(injector)
        fun MutableMethod.setText(text: String) = replaceInstruction(0, "const-string v0, \"${smaliString(text)}\"")
        for ((method, text) in listOf("script" to script, "startUrl" to start, "userAgent" to agent)) {
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
                "start page ${start.ifEmpty { "(none, native screen kept)" }}, " +
                "test badge ${if (showBadge == true) "on" else "off"}",
        )
    }
}
