package app.revanced.patches.facebook.misc.userscript

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.w3c.dom.Element
import java.util.Base64
import java.util.logging.Logger

/*
 * Inject userscript to block sponsored posts on android 8 apk app
 *
 * ONE self-contained file, no option to fill in. It adds and removes NO file of the apk. What changes:
 *   1. the existing classes.dex gets two small classes: "Injector" (the logic) and "HostActivity" (a bare window),
 *   2. the userscript is a string inside Injector,
 *   3. one call at the top of the Application.onCreate() that already exists,
 *   4. the existing AndroidManifest.xml is edited (not replaced, nothing added to the zip): one <activity> element for
 *      HostActivity, and the launcher <activity-alias> entries (the app icon) point to it instead of FbMainTabActivity.
 *
 * What it does at run time (inside the app process):
 *   a. START PAGE (idea taken from the "Cobalt-Start-URL aendern" patch of the YouTube TV bundle, which repoints the
 *      app at another url): the icon of the app opens HostActivity, a window that holds one WebView with
 *      https://m.facebook.com/ and a phone user agent. The native Facebook screens are never started: no second login
 *      on top of the native one, no native video playing behind the page, no double loading. Back goes back inside the
 *      page first; the WebView is paused when the app leaves the screen. Links that are not http(s) (fb://, intent://)
 *      are ignored. Should the native main screen be reached some other way (a notification, say) the same page is
 *      laid over it.
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
 * LIMITS: the native News Feed of the app is not a web page, so the userscript cannot touch it; that is why (a)
 * exists. Because the native screens are not started, what only the native screens do (native notifications' deep
 * links, Messenger-style native chat, camera / file upload from the page) is not available; the web site's own
 * versions are.
 *
 * ---- Injector.smali and HostActivity.smali (assembled with smali; the Base64 further down contains them) ------------
 * # ===== Injector.smali =====
 * .class public final Lapp/revanced/extension/facebook/userscript/Injector;
 * .super Landroid/webkit/WebViewClient;
 * .source "Injector.java"
 *
 * # 1. HostActivity (the launcher entry, see the manifest edit of the patch) shows m.facebook.com with a phone user agent
 * #    in a WebView. Where the native main screen is reached some other way (a notification, say) the same page is laid over it.
 * # 2. Runs the userscript in every WebView the app shows (that one, and the in-app browser).
 * .implements Landroid/app/Application$ActivityLifecycleCallbacks;
 * .implements Landroid/view/View$OnKeyListener;
 * .implements Ljava/lang/Runnable;
 *
 * .field private static instance:Lapp/revanced/extension/facebook/userscript/Injector;
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
 *     sput-object v0, Lapp/revanced/extension/facebook/userscript/Injector;->instance:Lapp/revanced/extension/facebook/userscript/Injector;
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
 * # script() gets its text from the patch. The start page and the phone user agent are fixed.
 * .method public static script()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "__RV_SCRIPT__"
 *     return-object v0
 * .end method
 *
 * .method public static startUrl()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "https://m.facebook.com/"
 *     return-object v0
 * .end method
 *
 * .method public static userAgent()Ljava/lang/String;
 *     .locals 1
 *     const-string v0, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
 * # ---- called by HostActivity ---------------------------------------------------------------------------------------------
 *
 * # onCreate: put the start page in the window right away (no waiting for the 1.5 s poll).
 * .method public static open(Landroid/app/Activity;)V
 *     .locals 1
 *     :try_start_0
 *     sget-object v0, Lapp/revanced/extension/facebook/userscript/Injector;->instance:Lapp/revanced/extension/facebook/userscript/Injector;
 *     if-eqz v0, :done
 *     invoke-direct {v0, p0}, Lapp/revanced/extension/facebook/userscript/Injector;->attach(Landroid/app/Activity;)V
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :done
 *     return-void
 *     :catch_0
 *     move-exception v0
 *     goto :done
 * .end method
 *
 * # The WebView this class put in the activity, or null.
 * .method private static webViewOf(Landroid/app/Activity;)Landroid/webkit/WebView;
 *     .locals 3
 *     const/4 v2, 0x0
 *     const v0, 0x1020002
 *     invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
 *     move-result-object v0
 *     if-eqz v0, :none
 *     const-string v1, "rv-web"
 *     invoke-virtual {v0, v1}, Landroid/view/View;->findViewWithTag(Ljava/lang/Object;)Landroid/view/View;
 *     move-result-object v0
 *     instance-of v1, v0, Landroid/webkit/WebView;
 *     if-eqz v1, :none
 *     check-cast v0, Landroid/webkit/WebView;
 *     return-object v0
 *     :none
 *     return-object v2
 * .end method
 *
 * # onBackPressed: true when the page went back by itself.
 * .method public static back(Landroid/app/Activity;)Z
 *     .locals 2
 *     const/4 v1, 0x0
 *     :try_start_0
 *     invoke-static {p0}, Lapp/revanced/extension/facebook/userscript/Injector;->webViewOf(Landroid/app/Activity;)Landroid/webkit/WebView;
 *     move-result-object v0
 *     if-eqz v0, :done
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->canGoBack()Z
 *     move-result v1
 *     if-eqz v1, :done
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->goBack()V
 *     const/4 v1, 0x1
 *     :try_end_0
 *     .catchall {:try_start_0 .. :try_end_0} :catch_0
 *     :done
 *     return v1
 *     :catch_0
 *     move-exception v0
 *     const/4 v1, 0x0
 *     goto :done
 * .end method
 *
 * # onPause (0) / onResume (1) / onDestroy (2): a page that is not on screen must not keep playing its video and sound;
 * # on pause the cookies (the login) are written to disk as well.
 * .method public static life(Landroid/app/Activity;I)V
 *     .locals 2
 *     :try_start_0
 *     invoke-static {p0}, Lapp/revanced/extension/facebook/userscript/Injector;->webViewOf(Landroid/app/Activity;)Landroid/webkit/WebView;
 *     move-result-object v0
 *     if-eqz v0, :done
 *     if-nez p1, :cond_not_pause
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->onPause()V
 *     invoke-static {}, Landroid/webkit/CookieManager;->getInstance()Landroid/webkit/CookieManager;
 *     move-result-object v1
 *     invoke-virtual {v1}, Landroid/webkit/CookieManager;->flush()V
 *     goto :done
 *     :cond_not_pause
 *     const/4 v1, 0x1
 *     if-ne p1, v1, :cond_destroy
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->onResume()V
 *     goto :done
 *     :cond_destroy
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->getParent()Landroid/view/ViewParent;
 *     move-result-object v1
 *     instance-of v1, v1, Landroid/view/ViewGroup;
 *     if-eqz v1, :no_parent
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->getParent()Landroid/view/ViewParent;
 *     move-result-object v1
 *     check-cast v1, Landroid/view/ViewGroup;
 *     invoke-virtual {v1, v0}, Landroid/view/ViewGroup;->removeView(Landroid/view/View;)V
 *     :no_parent
 *     invoke-virtual {v0}, Landroid/webkit/WebView;->destroy()V
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
 *     const-string v1, "app.revanced.extension.facebook.userscript.HostActivity"
 *     invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
 *     move-result v2
 *     if-nez v2, :cond_is_main
 *     const-string v1, "com.facebook.katana.activity.FbMainTabActivity"
 *     invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
 *     move-result v2
 *     if-eqz v2, :cond_not_main
 *     :cond_is_main
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
 *
 * # ===== HostActivity.smali =====
 * .class public Lapp/revanced/extension/facebook/userscript/HostActivity;
 * .super Landroid/app/Activity;
 * .source "HostActivity.java"
 *
 * # The launcher entry of the app (the manifest alias that used to open FbMainTabActivity points here): a bare window that
 * # shows m.facebook.com in a WebView. The native Facebook screens are never started, so there is no second login, nothing
 * # plays behind the page and the app does not load its feed twice. All the work is in Injector.
 *
 * .method public constructor <init>()V
 *     .locals 0
 *     invoke-direct {p0}, Landroid/app/Activity;-><init>()V
 *     return-void
 * .end method
 *
 * .method protected onCreate(Landroid/os/Bundle;)V
 *     .locals 0
 *     invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
 *     invoke-static {p0}, Lapp/revanced/extension/facebook/userscript/Injector;->open(Landroid/app/Activity;)V
 *     return-void
 * .end method
 *
 * .method protected onPause()V
 *     .locals 1
 *     invoke-super {p0}, Landroid/app/Activity;->onPause()V
 *     const/4 v0, 0x0
 *     invoke-static {p0, v0}, Lapp/revanced/extension/facebook/userscript/Injector;->life(Landroid/app/Activity;I)V
 *     return-void
 * .end method
 *
 * .method protected onResume()V
 *     .locals 1
 *     invoke-super {p0}, Landroid/app/Activity;->onResume()V
 *     const/4 v0, 0x1
 *     invoke-static {p0, v0}, Lapp/revanced/extension/facebook/userscript/Injector;->life(Landroid/app/Activity;I)V
 *     return-void
 * .end method
 *
 * .method protected onDestroy()V
 *     .locals 1
 *     const/4 v0, 0x2
 *     invoke-static {p0, v0}, Lapp/revanced/extension/facebook/userscript/Injector;->life(Landroid/app/Activity;I)V
 *     invoke-super {p0}, Landroid/app/Activity;->onDestroy()V
 *     return-void
 * .end method
 *
 * .method public onBackPressed()V
 *     .locals 1
 *     invoke-static {p0}, Lapp/revanced/extension/facebook/userscript/Injector;->back(Landroid/app/Activity;)Z
 *     move-result v0
 *     if-nez v0, :done
 *     invoke-super {p0}, Landroid/app/Activity;->onBackPressed()V
 *     :done
 *     return-void
 * .end method
 * ------------------------------------------------------------------------------------------------------------------
 */

private const val INJECTOR = "Lapp/revanced/extension/facebook/userscript/Injector;"
private const val HOST_ACTIVITY_TYPE = "Lapp/revanced/extension/facebook/userscript/HostActivity;"
private const val HOST_ACTIVITY = "app.revanced.extension.facebook.userscript.HostActivity"
private const val APP_CLASS = "Lcom/facebook/katana/app/FacebookApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"

/** Userscript. "__BADGE__" is replaced by true/false (patch option). Written without the dollar sign on purpose. */
private val USERSCRIPT = """
/* RV: hide Sponsored posts and the "Open app" bar on facebook.com pages shown inside the Android 8 Facebook app.
 * Derived from "FB - Clean my feeds" (GPL-3.0, Artificial Sweetener, zbluebugz, Quoc Viet Rtinh):
 *   - /ads/about/ disclosure link, svg disclosure, sponsored label in every language of the original
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

  function geoCard(el) {
    var W = vw(), H = vh(), MIN = H * 0.25, MAX = H * 1.7, a, d, r, par, kids, k, cnt;
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
  function trackCard(el) {
    var best = null, H = vh(), SEL = '[data-tracking-duration-id]';
    for (var a = el.closest(SEL); a; a = a.parentElement ? a.parentElement.closest(SEL) : null) {
      var inner = a.querySelectorAll(SEL), foreign = false, i;
      for (i = 0; i < inner.length; i++) {
        if (!inner[i].contains(el)) { foreign = true; break; }
      }
      if (foreign) break;
      var r = a.getBoundingClientRect();
      if (r.height >= H * 0.25 && r.height <= H * 1.7) best = a;
    }
    return best;
  }

  /* the label has to be in the header part of the card */
  function inHeader(el, card) {
    var a = el.getBoundingClientRect(), b = card.getBoundingClientRect(), off = a.top - b.top;
    return off >= -4 && off <= Math.max(160, b.height * 0.35);
  }

  /* all elements that show a sponsored label inside root: text nodes (the label is often a bare text node next to a
   * " · " and an icon), and elements whose visible letters add up to a label (decoy letters). */
  function labelEls(root) {
    var out = [];
    if (!root || root.nodeType !== 1) return out;
    var tw = document.createTreeWalker(root, 4, null, false), t, n = 0, pe, tag;
    while ((t = tw.nextNode()) && n++ < 8000) {
      var v = t.nodeValue;
      if (!v || v.length > 40 || !/\S/.test(v)) continue;
      if (!isLabel(v)) continue;
      pe = t.parentElement;
      if (!pe) continue;
      tag = String(pe.tagName).toUpperCase();
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'TEXTAREA' || tag === 'TITLE' || tag === 'NOSCRIPT') continue;
      out.push(pe);
    }
    var cand = root.querySelectorAll('span,div,a,h3,h4,h5,strong,b'), k, e, c = 0, tl;
    for (k = 0; k < cand.length && c < 2500; k++) {
      e = cand[k];
      if (e.childElementCount < 2 || e.childElementCount > 40) continue;
      c++;
      tl = (e.textContent || '').length;
      if (tl < 5 || tl > 80) continue;
      if (isLabel(visibleText(e))) out.push(e);
    }
    return out;
  }

  /* true = finished (hidden or rejected), false = the page has not been laid out yet, try again later */
  function handleLabel(el) {
    if (el.__rvDone) return true;
    if (!el.isConnected) return true;
    if (el.closest('[' + HIDE + ']')) { el.__rvDone = true; return true; }
    if (inBody(el)) { el.__rvDone = true; return true; }
    var card = postOf(el), rule = 'label-text';
    if (!card) { card = geoCard(el); rule = 'label-shape'; }
    if (!card) { card = trackCard(el); rule = 'label-track'; }
    if (!card) return false;
    if (!card.getBoundingClientRect().height) return false;
    el.__rvDone = true;
    if (!inHeader(el, card)) return true;
    hide(card, rule);
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
      out.push('SHOWN label#' + n + ': ' + path.join(' < '));
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

/** Assembled Injector + HostActivity classes (one dex file with two classes), Base64. */
private val EMBEDDED_DEX = listOf(
    "ZGV4CjAzOAD7BS5CWIRbHU8mQI+PkrjtsWppOuqsc7gsGAAAcAAAAHhWNBIAAAAAAAAAAIwXAAB8AAAAcAAAAB4AAABgAgAAKgAA",
    "ANgCAAAEAAAA0AQAAFAAAADwBAAAAgAAAHAHAAB8EAAAsAcAALAHAAC4BwAAywcAAM4HAADdBwAA4AcAAOMHAADnBwAA6wcAAPAH",
    "AAAICAAAPggAAFkIAAB0CAAAiQgAAJ8IAAC0CAAAzQgAAPAIAAAFCQAALAkAAEYJAABhCQAAeAkAAJgJAAC4CQAA1gkAAPAJAAAQ",
    "CgAAOwoAAHYKAACtCgAAwAoAANQKAADqCgAA/goAAG8LAAByCwAAdwsAAHsLAACACwAAhQsAAIoLAACOCwAAkQsAAJULAACbCwAA",
    "oAsAAKULAAC0CwAAvQsAAPYLAAD+CwAABAwAAA8MAAA/DAAASAwAAFEMAABZDAAAbQwAAHsMAACMDAAAkwwAAJ4MAACqDAAAuQwA",
    "AMMMAADRDAAA3gwAAO0MAAD2DAAAAQ0AAA4NAAAdDQAAKA0AADANAAA5DQAAPw0AAFgNAABgDQAAaQ0AAHMNAAB8DQAAhA0AAIoN",
    "AACTDQAAnw0AALINAADHDQAA2Q0AAOwNAAAJDgAAHA4AAC8OAAA+DgAASA4AAFMOAABaDgAAbw4AAH8OAACIDgAAkg4AAJgOAACl",
    "DgAAyQ4AANoOAADmDgAA9A4AAPkOAAABDwAABw8AAA8PAAArDwAAQQ8AAFoPAABwDwAAgg8AAIoPAACeDwAAsA8AAMoPAADUDwAA",
    "4A8AAOsPAAACAAAABAAAAAkAAAAKAAAACwAAAAwAAAANAAAADgAAAA8AAAAQAAAAEQAAABIAAAATAAAAFAAAABUAAAAWAAAAFwAA",
    "ABgAAAAZAAAAGgAAABsAAAAcAAAAHQAAAB4AAAAfAAAAIAAAACEAAAAiAAAAJAAAACsAAAACAAAAAAAAAAAAAAAFAAAACAAAAAAA",
    "AAAFAAAACwAAAAAAAAAGAAAACwAAAIQQAAAHAAAACwAAAAAQAAAFAAAADgAAAAAAAAAFAAAADwAAAAAAAAAFAAAAEAAAAAAAAAAF",
    "AAAAEgAAAAAAAAAHAAAAEwAAAGwQAAAIAAAAEwAAAKQQAAAFAAAAGAAAAAAAAAAFAAAAGwAAAAAAAAAkAAAAHAAAAAAAAAAlAAAA",
    "HAAAAHwQAAAmAAAAHAAAAGwQAAAnAAAAHAAAAGQQAAAoAAAAHAAAAKwQAAAmAAAAHAAAAJQQAAAmAAAAHAAAACAQAAAmAAAAHAAA",
    "AJwQAAAmAAAAHAAAAAgQAAAmAAAAHAAAAFQQAAAmAAAAHAAAAEwQAAAmAAAAHAAAABAQAAAoAAAAHAAAAEQQAAAmAAAAHAAAAMAQ",
    "AAAoAAAAHAAAABgQAAApAAAAHAAAACgQAAAmAAAAHAAAAPgPAAAmAAAAHAAAAAAQAAAmAAAAHAAAAHQQAAAmAAAAHAAAADwQAAAo",
    "AAAAHAAAAIwQAAAqAAAAHAAAAMgQAAArAAAAHQAAAAAAAAAsAAAAHQAAAGwQAAAtAAAAHQAAALQQAAAvAAAAHQAAABgQAAAsAAAA",
    "HQAAAAAQAAAuAAAAHQAAAFwQAAAsAAAAHQAAADwQAAAXAAIANwAAABcABwBLAAAAFwAXAFAAAAAXABsAUQAAAAIADQAAAAAAAgAD",
    "ADsAAAACAAYASQAAAAIADQBdAAAAAgAVAF4AAAACAA0AXwAAAAIADQBjAAAAAgANAGQAAAAEABIAZwAAAAcAFgAAAAAABwAoAGYA",
    "AAAHAB8AaAAAAAgAAQBEAAAACQAAAD4AAAALAAQAPAAAAAsAAABIAAAACwAjAGoAAAANABkAMQAAAA0AAwA/AAAADQAAAEAAAAAN",
    "ABgAaQAAAA8AAgBCAAAAEAANAD0AAAAQAAcAQwAAABAAHABvAAAAEgAiAHAAAAASACIAcgAAABIAIAB1AAAAEwAUAAAAAAATACMA",
    "NQAAABMADQA4AAAAEwAhADoAAAATAAUARgAAABMACABHAAAAEwANAEoAAAATACAAVAAAABMADQBjAAAAEwANAGQAAAATACIAcQAA",
    "ABMAFwBzAAAAEwAeAHQAAAATAB0AdgAAABQADQAAAAAAFQAOAAAAAAAWAA0AAAAAABYADQBdAAAAFgAVAF4AAAAWAA0AXwAAABYA",
    "DQBjAAAAFgANAGQAAAAXAA0AAAAAABcADwAzAAAAFwAkADQAAAAXABoATgAAABcAEwBPAAAAFwAQAFMAAAAXAAoAVQAAABcAEQBW",
    "AAAAFwAPAFcAAAAXAA8AWAAAABcADwBZAAAAFwARAFoAAAAXAA8AWwAAABcADwBcAAAAFwAlAGAAAAAXABsAYQAAABcAGwBiAAAA",
    "FwAPAGUAAAAXAA0AawAAABcAGABtAAAAFwAMAG4AAAAXACYAdwAAABcADAB4AAAAFwAMAHoAAAAXAAkAewAAABgADABFAAAAGQAL",
    "AEEAAAAbACcAOQAAABsAAABSAAAAGwApAHkAAAAWAAAAAQAAAAIAAAAAAAAAAQAAAAAAAAD6FgAAAAAAABcAAAARAAAAFAAAADAQ",
    "AAADAAAAAAAAABgXAAAAAAAABjxpbml0PgARSG9zdEFjdGl2aXR5LmphdmEAAUkADUluamVjdG9yLmphdmEAAUoAAUwAAkxJAAJM",
    "TAADTExMABZMYW5kcm9pZC9hcHAvQWN0aXZpdHk7ADRMYW5kcm9pZC9hcHAvQXBwbGljYXRpb24kQWN0aXZpdHlMaWZlY3ljbGVD",
    "YWxsYmFja3M7ABlMYW5kcm9pZC9hcHAvQXBwbGljYXRpb247ABlMYW5kcm9pZC9jb250ZW50L0NvbnRleHQ7ABNMYW5kcm9pZC9v",
    "cy9CdW5kbGU7ABRMYW5kcm9pZC9vcy9IYW5kbGVyOwATTGFuZHJvaWQvb3MvTG9vcGVyOwAXTGFuZHJvaWQvdmlldy9LZXlFdmVu",
    "dDsAIUxhbmRyb2lkL3ZpZXcvVmlldyRPbktleUxpc3RlbmVyOwATTGFuZHJvaWQvdmlldy9WaWV3OwAlTGFuZHJvaWQvdmlldy9W",
    "aWV3R3JvdXAkTGF5b3V0UGFyYW1zOwAYTGFuZHJvaWQvdmlldy9WaWV3R3JvdXA7ABlMYW5kcm9pZC92aWV3L1ZpZXdQYXJlbnQ7",
    "ABVMYW5kcm9pZC92aWV3L1dpbmRvdzsAHkxhbmRyb2lkL3dlYmtpdC9Db29raWVNYW5hZ2VyOwAeTGFuZHJvaWQvd2Via2l0L1Zh",
    "bHVlQ2FsbGJhY2s7ABxMYW5kcm9pZC93ZWJraXQvV2ViU2V0dGluZ3M7ABhMYW5kcm9pZC93ZWJraXQvV2ViVmlldzsAHkxhbmRy",
    "b2lkL3dlYmtpdC9XZWJWaWV3Q2xpZW50OwApTGFuZHJvaWQvd2lkZ2V0L0ZyYW1lTGF5b3V0JExheW91dFBhcmFtczsAOUxhcHAv",
    "cmV2YW5jZWQvZXh0ZW5zaW9uL2ZhY2Vib29rL3VzZXJzY3JpcHQvSG9zdEFjdGl2aXR5OwA1TGFwcC9yZXZhbmNlZC9leHRlbnNp",
    "b24vZmFjZWJvb2svdXNlcnNjcmlwdC9JbmplY3RvcjsAEUxqYXZhL2xhbmcvQ2xhc3M7ABJMamF2YS9sYW5nL09iamVjdDsAFExq",
    "YXZhL2xhbmcvUnVubmFibGU7ABJMamF2YS9sYW5nL1N0cmluZzsAb01vemlsbGEvNS4wIChMaW51eDsgQW5kcm9pZCAxMDsgSykg",
    "QXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEyNC4wLjAuMCBNb2JpbGUgU2FmYXJpLzUzNy4z",
    "NgABVgADVklJAAJWTAADVkxJAANWTEwAA1ZMWgACVloAAVoAAlpMAARaTElMAANaTEoAA1pMTAANX19SVl9TQ1JJUFRfXwAHYWRk",
    "VmlldwA3YXBwLnJldmFuY2VkLmV4dGVuc2lvbi5mYWNlYm9vay51c2Vyc2NyaXB0Lkhvc3RBY3Rpdml0eQAGYXR0YWNoAARiYWNr",
    "AAljYW5Hb0JhY2sALmNvbS5mYWNlYm9vay5rYXRhbmEuYWN0aXZpdHkuRmJNYWluVGFiQWN0aXZpdHkAB2N1cnJlbnQAB2Rlc3Ry",
    "b3kABmVxdWFscwASZXZhbHVhdGVKYXZhc2NyaXB0AAxmaW5kVmlld0J5SWQAD2ZpbmRWaWV3V2l0aFRhZwAFZmx1c2gACWdldEFj",
    "dGlvbgAKZ2V0Q2hpbGRBdAANZ2V0Q2hpbGRDb3VudAAIZ2V0Q2xhc3MADGdldERlY29yVmlldwALZ2V0SW5zdGFuY2UADWdldE1h",
    "aW5Mb29wZXIAB2dldE5hbWUACWdldFBhcmVudAALZ2V0U2V0dGluZ3MADWdldFZpc2liaWxpdHkACWdldFdpbmRvdwAGZ29CYWNr",
    "AAdoYW5kbGVyAARodHRwABdodHRwczovL20uZmFjZWJvb2suY29tLwAGaW5qZWN0AAdpbnN0YWxsAAhpbnN0YW5jZQAHbGFzdFVy",
    "bAAGbGVuZ3RoAARsaWZlAAdsb2FkVXJsAApuZXdXZWJWaWV3ABFvbkFjdGl2aXR5Q3JlYXRlZAATb25BY3Rpdml0eURlc3Ryb3ll",
    "ZAAQb25BY3Rpdml0eVBhdXNlZAARb25BY3Rpdml0eVJlc3VtZWQAG29uQWN0aXZpdHlTYXZlSW5zdGFuY2VTdGF0ZQARb25BY3Rp",
    "dml0eVN0YXJ0ZWQAEW9uQWN0aXZpdHlTdG9wcGVkAA1vbkJhY2tQcmVzc2VkAAhvbkNyZWF0ZQAJb25EZXN0cm95AAVvbktleQAT",
    "b25QYWdlQ29tbWl0VmlzaWJsZQAOb25QYWdlRmluaXNoZWQAB29uUGF1c2UACG9uUmVzdW1lAARvcGVuAAtwb3N0RGVsYXllZAAi",
    "cmVnaXN0ZXJBY3Rpdml0eUxpZmVjeWNsZUNhbGxiYWNrcwAPcmVtb3ZlQ2FsbGJhY2tzAApyZW1vdmVWaWV3AAxyZXF1ZXN0Rm9j",
    "dXMAA3J1bgAGcnYtd2ViAARzY2FuAAZzY3JpcHQAGnNldEFjY2VwdFRoaXJkUGFydHlDb29raWVzABRzZXREb21TdG9yYWdlRW5h",
    "YmxlZAAXc2V0Rm9jdXNhYmxlSW5Ub3VjaE1vZGUAFHNldEphdmFTY3JpcHRFbmFibGVkABBzZXRPbktleUxpc3RlbmVyAAZzZXRU",
    "YWcAEnNldFVzZXJBZ2VudFN0cmluZwAQc2V0V2ViVmlld0NsaWVudAAYc2hvdWxkT3ZlcnJpZGVVcmxMb2FkaW5nAAhzdGFydFVy",
    "bAAKc3RhcnRzV2l0aAAJdXNlckFnZW50AAl3ZWJWaWV3T2YAAAABAAAAFAAAAAEAAAAZAAAAAQAAAAYAAAABAAAACwAAAAIAAAAT",
    "ABsAAQAAAAQAAAACAAAAEwAdAAMAAAADAAoAGgAAAAEAAAAbAAAAAgAAAAsADAABAAAACgAAAAEAAAAIAAAAAgAAABoAAQACAAAA",
    "AgAAAAEAAAACAAAAAQAAABoAAAACAAAAAAAAAAEAAAAAAAAAAgAAABsAEQABAAAAAwAAAAEAAAAFAAAAAgAAAAIAFwACAAAAAgAG",
    "AAMAAAALAAAACQAAAAEAAAATAAAAAQAAAB0AAAAAAAAAAQABAAEAAAAAAAAABAAAAHAQAAAAAA4AAgABAAEAAAAAAAAACgAAAHEQ",
    "NAABAAoAOQAFAG8QAwABAA4AAgACAAIAAAAAAAAABwAAAG8gBAAQAHEQQwAAAA4AAAACAAEAAgAAAAAAAAAIAAAAEiBxIDcAAQBv",
    "EAUAAQAOAAIAAQACAAAAAAAAAAgAAABvEAYAAQASAHEgNwABAA4AAgABAAIAAAAAAAAACAAAAG8QBwABABIQcSA3AAEADgADAAEA",
    "AgAAAAAAAAAPAAAAcBAqAAIAIgAHAHEADAAAAAwBcCAJABAAWyABAA4AAAAHAAIAAwABAAAAAAA6AAAAcQBIAAAADABuEE4AAAAK",
    "BDgELwAUBAIAAgFuIAEARgAMASAUDQA4BCQAHwENABoEbABuIA4AQQAMBDkEGgBxIDgAVgAMAmIDAwA4AwMABzAiAxUAEvRwMCsA",
    "QwRuMBEAIQNuICMAAgBuEBAAAgAOAA0AKP4AAAAANwABAAEAOAADAAEAAQABAAAAAAAVAAAAEgFxEEoAAgAMADgADABuEB0AAAAK",
    "ATgBBgBuECIAAAASEQ8BDQASASj9AAABAAAAEAABAAEAEgADAAEAAwABAAAAAAALAAAAcQBGAAAADAASAW4wHwACAQ4ADQAo/gAA",
    "AAAAAAgAAQABAAkAAwABAAIAAQAAAAAADQAAACIAFwBwEDIAAABpAAIAbiAIAAIADgANACj+AAAAAAAACgABAAEACwAEAAIAAgAB",
    "AAAAAAAxAAAAcRBKAAIADAA4ACoAOQMNAG4QJAAAAHEAFwAAAAwBbhAWAAEAKBwSETMTBgBuECUAAAAoFW4QIAAAAAwBIBENADgB",
    "CwBuECAAAAAMAR8BDQBuIBQAAQBuEB4AAAAOAA0AKP4AAAAAAAAuAAEAAQAvAAYAAgADAAAAAAAAADQAAAAiABMAcCAcAEAAGgFs",
    "AG4gKAAQAG4QIQAAAAwBEhJuIBoAIQBuIBkAIQBxAEkAAAAMA24QTgADAAoCOAIFAG4gGwAxAG4gKQBQAG4gJwBQABISbiAmACAA",
    "cQAXAAAADANuMBgAAwIRAAIAAQACAAEAAAAAAAoAAABiAAIAOAAFAHAgMwAQAA4ADQAo/gAAAAAHAAEAAQAIAAUAAQACAAAAAAAA",
    "ACwAAABuEA8ABAAKABMBCAAyECUAIEATADgACAAfBBMAcRA1AAQADgAgQA0AOAAXAB8EDQBuEBMABAAKABIBNQEOAG4gEgAUAAwC",
    "OAIFAHEQRQACANgBAQEo8w4AAQAAAAAAAAAAAAAAAwAAABoAMAARAAAAAQAAAAAAAAAAAAAAAwAAABoATQARAAAAAQAAAAAAAAAA",
    "AAAAAwAAABoAIwARAAAABAABAAIAAAAAAAAAGAAAABICFAACAAIBbiABAAMADAA4AA8AGgFsAG4gDgAQAAwAIAETADgBBQAfABMA",
    "EQARAgMAAwAAAAAAAAAAAAEAAAAOAAAAAgACAAAAAAAAAAAAAQAAAA4AAAAEAAIAAgAAAAAAAAAJAAAAVCABAG4gCwAgABIBWyEA",
    "AA4AAAAFAAIABAAAAAAAAAAoAAAAWzQAAG4QTAAEAAwAbhBLAAAADAAaATIAbiBNAAEACgI5AgoAGgE2AG4gTQABAAoCOAIFAHAg",
    "MwBDAFQwAQBuIAsAMAAWASADbkAKADAhDgADAAMAAAAAAAAAAAABAAAADgAAAAIAAgAAAAAAAAAAAAEAAAAOAAAAAgACAAAAAAAA",
    "AAAAAQAAAA4AAAAHAAQAAQAAAAAAAAAcAAAAEgASQTMVGQBuEA0ABgAKARISMyESACBBEwA4AQ4AHwQTAG4QHQAEAAoBOAEGAG4Q",
    "IgAEABIQDwADAAMAAQAAAAAAAAAEAAAAcRA1AAEADgADAAMAAQAAAAAAAAAGAAAAaQIDAHEQNQABAA4ABAABAAQAAQAAAAAAGgAA",
    "AFQwAAA5AAMADgBuEAIAAAAMAW4QFQABAAwBcRBFAAEAVDABABYB3AVuQAoAMCEOAA0AKPcFAAAACwABAAEAGAAFAAMAAgAAAAAA",
    "AAANAAAAOQQEABIQDwAaAEwAbiBPAAQACgDfAAABDwAAAAEFLIGABNQhLQHsIQEEkCIBBLAiAQTQIgEE8CICAg0MAgoBCgACARIy",
    "goAEkCMBAsAjAQnQJAEKmCUBCcwlAQmEJgEKhCcLCfwnAgqsKAEJlCkCCawpAQnEKQEK3Ck5AZwqAQGwKgEBxCoBAegqAQHIKwEB",
    "3CsBAfArAQGELAEBzCwBAeQsAgGALQMB0C0AAA0AAAAAAAAAAQAAAAAAAAABAAAAfAAAAHAAAAACAAAAHgAAAGACAAADAAAAKgAA",
    "ANgCAAAEAAAABAAAANAEAAAFAAAAUAAAAPAEAAAGAAAAAgAAAHAHAAACIAAAfAAAALAHAAABEAAAGgAAAPgPAAADEAAAAQAAANAQ",
    "AAABIAAAHwAAANQQAAAAIAAAAgAAAPoWAAAAEAAAAQAAAIwXAAA=",
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

/** True when one of the <intent-filter>s of [this] has action MAIN and category LAUNCHER. */
private fun Element.isLauncher(): Boolean {
    fun Element.has(tag: String, name: String): Boolean {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            if ((nodes.item(i) as? Element)?.getAttribute("android:name") == name) return true
        }
        return false
    }
    val filters = getElementsByTagName("intent-filter")
    for (i in 0 until filters.length) {
        val filter = filters.item(i) as? Element ?: continue
        if (filter.has("action", "android.intent.action.MAIN") && filter.has("category", "android.intent.category.LAUNCHER")) {
            return true
        }
    }
    return false
}

/*
 * Edit of the EXISTING manifest (no file is added to or removed from the apk): the launcher aliases open HostActivity.
 * Without a name, so it is not a patch of its own: it only runs because the patch below depends on it.
 */
private val launcherOpensHostActivityPatch = resourcePatch {
    apply {
        val log = Logger.getLogger("InjectUserscript")
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("Inject userscript: AndroidManifest.xml has no <application> element")

            var repointed = 0
            val aliases = manifest.getElementsByTagName("activity-alias")
            for (i in 0 until aliases.length) {
                val alias = aliases.item(i) as? Element ?: continue
                if (!alias.isLauncher()) continue
                alias.setAttribute("android:targetActivity", HOST_ACTIVITY)
                repointed++
            }
            if (repointed == 0) {
                log.warning(
                    "Inject userscript: no launcher <activity-alias> in the manifest, so the app icon still opens the " +
                        "native screen. The page is only laid over the native main screen (second login, native " +
                        "video behind the page).",
                )
                return@use
            }

            val activities = manifest.getElementsByTagName("activity")
            var declared = false
            for (i in 0 until activities.length) {
                if ((activities.item(i) as? Element)?.getAttribute("android:name") == HOST_ACTIVITY) declared = true
            }
            if (!declared) {
                val activity = manifest.createElement("activity")
                activity.setAttribute("android:name", HOST_ACTIVITY)
                activity.setAttribute("android:exported", "true")
                activity.setAttribute("android:launchMode", "singleTop")
                activity.setAttribute(
                    "android:configChanges",
                    "keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|navigation",
                )
                activity.setAttribute("android:windowSoftInputMode", "stateHidden|adjustResize")
                activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.Light.NoActionBar")
                application.appendChild(activity)
            }
            log.info("Inject userscript: $repointed launcher alias(es) now open HostActivity")
        }
    }
}

@Suppress("unused")
val injectUserscriptPatch = bytecodePatch(
    name = "Inject userscript to block sponsored posts on android 8 apk app",
    description = "The app opens m.facebook.com (phone user agent) in a WebView instead of the native screens, and " +
        "runs a userscript in it that hides Sponsored / suggested posts and the Open app bar. Adds and removes no " +
        "file of the apk.",
    use = false,
) {
    compatibleWith("com.facebook.katana")
    dependsOn(launcherOpensHostActivityPatch)

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

        // ---- 2. Add the two classes into the existing dex ---------------------------------------------------------
        val embedded = try {
            DexBackedDexFile(null, Base64.getDecoder().decode(EMBEDDED_DEX), 0).classes.toList()
        } catch (e: Exception) {
            throw PatchException("Inject userscript: cannot read the embedded classes: $e")
        }
        val injector = embedded.firstOrNull { it.type == INJECTOR }
        if (injector == null || embedded.none { it.type == HOST_ACTIVITY_TYPE }) {
            throw PatchException("Inject userscript: the embedded dex does not hold Injector and HostActivity")
        }
        @Suppress("UNCHECKED_CAST")
        val mutableClassDefs = classDefs as MutableSet<ClassDef>
        embedded.forEach {
            if (!mutableClassDefs.add(it)) throw PatchException("Inject userscript: could not add ${it.type} to the dex")
        }

        // ---- 3. Put the userscript into the Injector --------------------------------------------------------------
        val script = USERSCRIPT.replace("__BADGE__", if (showBadge == true) "true" else "false")
        val mutableInjector = classDefs.getOrReplaceMutable(injector)
        val scriptMethod = mutableInjector.methods.firstOrNull {
            it.name == "script" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Inject userscript: Injector.script() not found")
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
