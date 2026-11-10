package app.revanced.patches.facebook.misc.userscript

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
 * ONE self-contained file. It adds NO file to the apk (no asset, no resource, no new dex, no library): everything is
 *   1. one tiny class, "Injector", added to the dex the app already has (classes.dex),
 *   2. the userscript text, stored as a string inside that class,
 *   3. one call at the top of the Application.onCreate() that already exists.
 *
 * What it does at run time (inside the app process, every process of the app):
 *   Injector registers an ActivityLifecycleCallbacks. While an activity is resumed, every 1.5 s it walks the view tree,
 *   and for every android.webkit.WebView it finds it runs the userscript with WebView.evaluateJavascript(). The first
 *   line of the userscript makes every repeat a no-op, and the script only acts on facebook.com / fb.com pages.
 *   No hidden Facebook class is touched, so it does not depend on the obfuscated names of any build.
 *
 * Hook point: the first onCreate()V with a body on the chain  FacebookApplication -> superclass -> ...
 *   490 / 521 : FacebookApplication has no onCreate, the first one is in its (obfuscated) base class
 *   585       : FacebookApplication.onCreate itself
 *
 * LIMIT: this only reaches WEB pages shown in the app (in-app browser, m.facebook.com pages in a WebView).
 * The main News Feed of the app is native, it is not a web page, so this userscript cannot touch it.
 *
 * ---- Injector source (assembled with smali; the text below is what the Base64 further down contains) ----------------
 * .class public final Lapp/revanced/extension/facebook/userscript/Injector;
 * .super Ljava/lang/Object;
 * .source "Injector.java"
 *
 * # Runs the userscript inside every WebView shown by the app.
 * .implements Landroid/app/Application$ActivityLifecycleCallbacks;
 * .implements Ljava/lang/Runnable;
 *
 * .field private final handler:Landroid/os/Handler;
 * .field private current:Landroid/app/Activity;
 *
 * .method private constructor <init>()V
 *     .locals 2
 *     invoke-direct {p0}, Ljava/lang/Object;-><init>()V
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
 * # The patch replaces the body of this method with the userscript text.
 * .method public static script()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "__RV_SCRIPT__"
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
 * .method public onActivityResumed(Landroid/app/Activity;)V
 *     .locals 3
 *     iput-object p1, p0, Lapp/revanced/extension/facebook/userscript/Injector;->current:Landroid/app/Activity;
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
  }

  function fullScan() {
    try { abp(document); } catch (e) {}
    var posts = document.querySelectorAll('[aria-posinset],[role="article"]');
    for (var i = 0; i < posts.length; i++) {
      var p = postOf(posts[i]);
      if (p) { try { inspect(p); } catch (e) {} }
    }
  }

  var badge = null;
  function paintBadge() {
    if (!SHOW_BADGE) return;
    try {
      if (!badge) {
        badge = document.createElement('div');
        badge.style.cssText = 'position:fixed;left:6px;bottom:6px;z-index:2147483647;padding:2px 6px;' +
          'font:11px/14px sans-serif;color:#fff;background:rgba(0,120,0,.85);border-radius:4px;pointer-events:none;';
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
    "ZGV4CjAzOABmjNmDcEOR71Hdf0NT1zqtBb8LDLBuwS0ACgAAcAAAAHhWNBIAAAAAAAAAAGAJAAAzAAAAcAAAABMAAAA8AQAAEQAA",
    "AIgBAAACAAAAVAIAABkAAABkAgAAAQAAACwDAAC0BgAATAMAAEwDAABUAwAAVwMAAGYDAABpAwAAbAMAAHADAACIAwAAvgMAANkD",
    "AADuAwAABAQAABkEAAAuBAAASAQAAF8EAAB/BAAAmQQAANAEAADkBAAA+gQAAA4FAAARBQAAFQUAABoFAAAdBQAAIgUAADEFAAA6",
    "BQAATgUAAFoFAABpBQAAdwUAAIYFAACVBQAAoAUAAKkFAACxBQAAugUAAM0FAADiBQAA9AUAAAcGAAAkBgAANwYAAEoGAABXBgAA",
    "ewYAAIwGAACRBgAAlwYAAAEAAAADAAAABgAAAAcAAAAIAAAACQAAAAoAAAALAAAADAAAAA0AAAAOAAAADwAAABAAAAARAAAAEgAA",
    "ABMAAAAUAAAAFQAAABgAAAABAAAAAAAAAAAAAAAEAAAABwAAAAAAAAAEAAAACAAAAAAAAAAFAAAACAAAAKgGAAAEAAAACgAAAAAA",
    "AAAEAAAAEAAAAAAAAAAVAAAAEQAAAAAAAAAWAAAAEQAAAPAGAAAXAAAAEQAAAMgGAAAWAAAAEQAAALgGAAAWAAAAEQAAANAGAAAW",
    "AAAAEQAAAOAGAAAWAAAAEQAAAMAGAAAWAAAAEQAAANgGAAAWAAAAEQAAAPgGAAAXAAAAEQAAALAGAAAZAAAAEgAAAOgGAAANAAIA",
    "GwAAAA0ABgAjAAAAAgAEACIAAAAEAAkALgAAAAYACwAAAAAABgAQAC0AAAAGAA4ALwAAAAcAAQAgAAAACAAAACEAAAAJAAMAHQAA",
    "AAkAAAAeAAAACgACAB8AAAAMAA8AHAAAAA0ABgAAAAAADQANACQAAAANAAoAJQAAAA0ACAAmAAAADQAHACcAAAANAAcAKAAAAA0A",
    "BwApAAAADQAIACoAAAANAAcAKwAAAA0ABwAsAAAADQAGADAAAAANAAwAMQAAAA0ABQAyAAAADgAGAAAAAAANAAAAEQAAAA4AAACg",
    "BgAAAgAAAAAAAAAfCQAAAAAAAAY8aW5pdD4AAUkADUluamVjdG9yLmphdmEAAUoAAUwAAkxJABZMYW5kcm9pZC9hcHAvQWN0aXZp",
    "dHk7ADRMYW5kcm9pZC9hcHAvQXBwbGljYXRpb24kQWN0aXZpdHlMaWZlY3ljbGVDYWxsYmFja3M7ABlMYW5kcm9pZC9hcHAvQXBw",
    "bGljYXRpb247ABNMYW5kcm9pZC9vcy9CdW5kbGU7ABRMYW5kcm9pZC9vcy9IYW5kbGVyOwATTGFuZHJvaWQvb3MvTG9vcGVyOwAT",
    "TGFuZHJvaWQvdmlldy9WaWV3OwAYTGFuZHJvaWQvdmlldy9WaWV3R3JvdXA7ABVMYW5kcm9pZC92aWV3L1dpbmRvdzsAHkxhbmRy",
    "b2lkL3dlYmtpdC9WYWx1ZUNhbGxiYWNrOwAYTGFuZHJvaWQvd2Via2l0L1dlYlZpZXc7ADVMYXBwL3JldmFuY2VkL2V4dGVuc2lv",
    "bi9mYWNlYm9vay91c2Vyc2NyaXB0L0luamVjdG9yOwASTGphdmEvbGFuZy9PYmplY3Q7ABRMamF2YS9sYW5nL1J1bm5hYmxlOwAS",
    "TGphdmEvbGFuZy9TdHJpbmc7AAFWAAJWTAADVkxMAAFaAANaTEoADV9fUlZfU0NSSVBUX18AB2N1cnJlbnQAEmV2YWx1YXRlSmF2",
    "YXNjcmlwdAAKZ2V0Q2hpbGRBdAANZ2V0Q2hpbGRDb3VudAAMZ2V0RGVjb3JWaWV3AA1nZXRNYWluTG9vcGVyAA1nZXRWaXNpYmls",
    "aXR5AAlnZXRXaW5kb3cAB2hhbmRsZXIABmluamVjdAAHaW5zdGFsbAARb25BY3Rpdml0eUNyZWF0ZWQAE29uQWN0aXZpdHlEZXN0",
    "cm95ZWQAEG9uQWN0aXZpdHlQYXVzZWQAEW9uQWN0aXZpdHlSZXN1bWVkABtvbkFjdGl2aXR5U2F2ZUluc3RhbmNlU3RhdGUAEW9u",
    "QWN0aXZpdHlTdGFydGVkABFvbkFjdGl2aXR5U3RvcHBlZAALcG9zdERlbGF5ZWQAInJlZ2lzdGVyQWN0aXZpdHlMaWZlY3ljbGVD",
    "YWxsYmFja3MAD3JlbW92ZUNhbGxiYWNrcwADcnVuAARzY2FuAAZzY3JpcHQAAAIAAAADAA8AAQAAAAAAAAACAAAAEAALAAEAAAAD",
    "AAAAAQAAAAgAAAACAAAAAgAFAAEAAAAEAAAAAQAAAAwAAAABAAAABwAAAAIAAAAPAAEAAQAAAAIAAAABAAAADwAAAAAAAAADAAEA",
    "AgAAAAAAAAAPAAAAcBAYAAIAIgAGAHEABQAAAAwBcCACABAAWyABAA4AAAADAAEAAwABAAAAAAALAAAAcQAXAAAADAASAW4wCgAC",
    "AQ4ADQAo/gAAAAAAAAgAAQABAAkAAwABAAIAAQAAAAAACwAAACIADQBwEAsAAABuIAEAAgAOAA0AKP4AAAAAAAAIAAEAAQAJAAUA",
    "AQACAAAAAAAAACwAAABuEAYABAAKABMBCAAyECUAIEAMADgACAAfBAwAcRAMAAQADgAgQAkAOAAXAB8ECQBuEAgABAAKABIBNQEO",
    "AG4gBwAUAAwCOAIFAHEQFgACANgBAQEo8w4AAQAAAAAAAAAAAAAAAwAAABoAGgARAAAAAwADAAAAAAAAAAAAAQAAAA4AAAACAAIA",
    "AAAAAAAAAAABAAAADgAAAAQAAgACAAAAAAAAAAkAAABUIAEAbiAEACAAEgFbIQAADgAAAAUAAgAEAAAAAAAAAA0AAABbNAAAVDAB",
    "AG4gBAAwABYBIANuQAMAMCEOAAAAAwADAAAAAAAAAAAAAQAAAA4AAAACAAIAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEA",
    "AAAOAAAABAABAAQAAQAAAAAAGgAAAFQwAAA5AAMADgBuEAAAAAAMAW4QCQABAAwBcRAWAAEAVDABABYB3AVuQAMAMCEOAA0AKPcF",
    "AAAACwABAAEAGAACBQgAAgESC4KABIQOAQq0DgEJ6A4JCpwPAQmEEA4BnBABAbAQAQHEEAEB6BABAZQRAQGoEQEBvBEBAdARAAAA",
    "DQAAAAAAAAABAAAAAAAAAAEAAAAzAAAAcAAAAAIAAAATAAAAPAEAAAMAAAARAAAAiAEAAAQAAAACAAAAVAIAAAUAAAAZAAAAZAIA",
    "AAYAAAABAAAALAMAAAIgAAAzAAAATAMAAAEQAAAMAAAAoAYAAAMQAAABAAAAAAcAAAEgAAANAAAABAcAAAAgAAABAAAAHwkAAAAQ",
    "AAABAAAAYAkAAA==",
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
    description = "Runs a userscript inside the WebViews of the Facebook app (in-app browser, m.facebook.com pages) " +
        "to hide Sponsored posts. Adds no file to the apk. It does not affect the native News Feed.",
    use = false,
) {
    compatibleWith("com.facebook.katana")

    val showBadge by booleanOption(
        name = "Show test badge",
        description = "Shows a small green 'RV userscript on, hidden: N' label at the bottom left of every facebook.com " +
            "page in a WebView, so you can see that the userscript runs. Turn it off after testing.",
        default = true,
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

        // ---- 3. Put the userscript text into Injector.script() ---------------------------------------------------
        val script = USERSCRIPT.replace("__BADGE__", if (showBadge == true) "true" else "false")
        val scriptMethod = classDefs.getOrReplaceMutable(injector).methods
            .firstOrNull { it.name == "script" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
            ?: throw PatchException("Inject userscript: Injector.script() not found")
        scriptMethod.replaceInstruction(0, "const-string v0, \"${smaliString(script)}\"")

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
                "test badge ${if (showBadge == true) "on" else "off"}",
        )
    }
}
