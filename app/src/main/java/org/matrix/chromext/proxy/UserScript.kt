package org.matrix.chromext.proxy

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import java.lang.reflect.Field
import java.net.URLEncoder
import org.matrix.chromext.script.AppDatabase
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.ScriptDao
import org.matrix.chromext.script.encodeScript
import org.matrix.chromext.script.erudaFontFix
import org.matrix.chromext.script.erudaToggle
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.script.urlMatch
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.invokeMethod

class UserScriptProxy(ctx: Context) {
  // These smali code names are possible to change when Chrome updates
  // User should be able to change them by their own if needed
  // If a field is read-only, i.e., initilized with `val`, meaning that we are not using it yet

  // Grep smali code with Tab.loadUrl to get the loadUrl function in
  // org/chromium/chrome/browser/tab/TabImpl.smali
  var LOAD_URL = "h"

  // Grep Android.Omnibox.InputToNavigationControllerStart to get loadUrl in
  // org/chromium/content/browser/framehost/NavigationControllerImpl.smali
  val NAVI_LOAD_URL = "h"
  // ! Note: loadUrl is only called for browser-Initiated navigations

  // It is possible to a HTTP POST with LoadUrlParams Class
  // grep org/chromium/content_public/common/ResourceRequestBody to get setPostData in
  // org/chromium/content_public/browser/LoadUrlParams
  val POST_DATA = "b"
  // ! Note: this is very POWERFUL

  // Grep ()I to get or goToNavigationIndex in
  // org/chromium/content/browser/framehost/NavigationControllerImpl.smali
  // Current tab has the biggest index, a new tab has index 0, index is stored with tab
  val NAVI_GOTO = "z"

  // Grep (I)V to get or getLastCommittedEntryIndex in
  // org/chromium/content/browser/framehost/NavigationControllerImpl.smali
  // Current tab has the biggest index, a new tab has index 0, index is stored with tab
  val NAVI_LAST_INDEX = "e"

  // Grep Android.Intent.IntentUriNavigationResult to get class
  // org/chromium/components/external_intents/ExternalNavigationHandler.java
  val EXTERNAL_NAVIGATION_HANDLER = "GC0"

  // Grep .super Lorg/chromium/components/navigation_interception/InterceptNavigationDelegate
  // to get class org.chromium.components.external_intents.InterceptNavigationDelegateImpl
  val INTERCEPT_NAVIGATION_DELEGATE_IMPL = "yi1"

  // Grep (Lorg/chromium/content_public/browser/WebContents;)V
  // in INTERCEPT_NAVIGATION_DELEGATE_IMPL to get associateWithWebContents
  val ASSOCIATE_CONTENTS = "a"

  companion object {
    // These samli code are considered stable for further releases of Chrome
    // We give two examplar fields.

    // The only field with type Ljava/lang/String in
    // org/chromium/url/GURL.smali is for URL
    private const val SPEC_FIELD = "a"
    // ! Note: GURL has limited length:
    // const size_t kMaxURLChars = 2 * 1024 * 1024; in chromium/src/ur/url_constants.cc
    // const uint32 kMaxURLChars = 2097152; in chromium/src/url/mojom/url.mojom
    const val kMaxURLChars = 2097152

    // Get TabImpl field in
    // org/chromium/chrome/browser/tab/TabWebContentsDelegateAndroidImpl.smali
    private const val TAB_FIELD = "a"

    // Fields of org/chromium/content_public/browser/LoadUrlParams
    // are too many to list here
    // They are in the same order as the source code

    // A variable to avoid redloading eruda
    var eruda_loaded = false
    var eruda_font_fixed = false
  }

  var tabWebContentsDelegateAndroidImpl: Class<*>? = null
  private var mTab: Field? = null
  private var tabDelegator: Any? = null

  val interceptNavigationDelegateImpl: Class<*>? = null

  val navigationControllerImpl: Class<*>? = null
  private val navController: Any? = null

  val webContentsObserverProxy: Class<*>? = null

  private val navigationHandle: Class<*>? = null

  private var gURL: Class<*>? = null
  private var mSpec: Field? = null

  private var loadUrlParams: Class<*>? = null
  private var mUrl: Field? = null
  private val mInitiatorOrigin: Field? = null
  private val mLoadUrlType: Field? = null
  private val mTransitionType: Field? = null
  private val mReferrer: Field? = null
  private val mExtraHeaders: Field? = null
  private val mNavigationHandleUserDataHost: Field? = null
  private val mVerbatimHeaders: Field? = null
  private val mUaOverrideOption: Field? = null
  private val mPostData: Field? = null
  private val mBaseUrlForDataUrl: Field? = null
  private val mVirtualUrlForDataUrl: Field? = null
  private val mDataUrlAsString: Field? = null
  private val mCanLoadLocalResources: Field? = null
  private val mIsRendererInitiated: Field? = null
  private val mShouldReplaceCurrentEntry: Field? = null
  private val mIntentReceivedTimestamp: Field? = null
  private val mInputStartTimestamp: Field? = null
  private val mHasUserGesture: Field? = null
  private val mShouldClearHistoryList: Field? = null
  private val mNavigationUIDataSupplier: Field? = null

  private var scriptDao: ScriptDao? = null
  init {
    val sharedPref: SharedPreferences = ctx.getSharedPreferences("ChromeXt", Context.MODE_PRIVATE)
    updateSmali(sharedPref)
    scriptDao =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "userscript")
            .allowMainThreadQueries()
            .build()
            .init()
    gURL = ctx.getClassLoader().loadClass("org.chromium.url.GURL")
    loadUrlParams =
        ctx.getClassLoader().loadClass("org.chromium.content_public.browser.LoadUrlParams")
    tabWebContentsDelegateAndroidImpl =
        ctx.getClassLoader()
            .loadClass("org.chromium.chrome.browser.tab.TabWebContentsDelegateAndroidImpl")
    // interceptNavigationDelegateImpl =
    //     ctx.getClassLoader().loadClass(INTERCEPT_NAVIGATION_DELEGATE_IMPL)
    // navigationControllerImpl =
    //     ctx.getClassLoader()
    //         .loadClass("org.chromium.content.browser.framehost.NavigationControllerImpl")
    // webContentsObserverProxy =
    //     ctx.getClassLoader()
    //         .loadClass("org.chromium.content.browser.webcontents.WebContentsObserverProxy")
    mUrl = loadUrlParams!!.getDeclaredField("a")
    // mVerbatimHeaders = loadUrlParams!!.getDeclaredField("h")
    mTab = tabWebContentsDelegateAndroidImpl!!.getDeclaredField(TAB_FIELD)
    mSpec = gURL!!.getDeclaredField(SPEC_FIELD)
  }
  private fun updateSmali(sharedPref: SharedPreferences) {
    // if (sharedPref.contains("NAVI_LOAD_URL")) {
    //   NAVI_LOAD_URL = sharedPref.getString("NAVI_LOAD_URL", NAVI_LOAD_URL)!!
    // } else {
    //   writeSmali(sharedPref)
    //   return
    // }
    if (sharedPref.contains("LOAD_URL")) {
      LOAD_URL = sharedPref.getString("LOAD_URL", LOAD_URL)!!
    } else {
      writeSmali(sharedPref)
      return
    }
  }

  private fun writeSmali(sharedPref: SharedPreferences) {
    with(sharedPref.edit()) {
      // putString("NAVI_LOAD_URL", NAVI_LOAD_URL)
      putString("LOAD_URL", LOAD_URL)
      apply()
    }
  }

  private fun loadUrl(url: String) {
    mTab!!.get(tabDelegator)?.invokeMethod(newUrl(url)) { name == LOAD_URL }
    Log.d("loadUrl: ${url}")
  }

  fun newUrl(url: String): Any {
    return loadUrlParams!!.getDeclaredConstructor(String::class.java).newInstance(url)
  }
  // private fun naviUrl(url: String) {
  //   navController!!.invokeMethod( newUrl(url) ) {
  //         name == NAVI_LOAD_URL
  //       }
  // }

  private fun invokeScript(url: String) {
    scriptDao!!.getAll().forEach {
      val script = it
      var run = false
      script.match.forEach {
        if (urlMatch(it, url)) {
          run = true
        }
      }
      if (run) {
        script.exclude.forEach {
          if (it != "" && urlMatch(it, url)) {
            run = false
          }
        }
      }
      if (run) {
        evaluateJavaScript(script)
        Log.i("${script.id} injected")
      }
    }
  }

  private fun evaluateJavaScript(script: Script) {
    if (!script.encoded) {
      val code = encodeScript(script)
      if (code != null) {
        script.code = code
        script.encoded = true
        scriptDao!!.insertAll(script)
      }
    }
    evaluateJavaScript(script.code)
  }

  fun evaluateJavaScript(script: String) {
    // Encode as Url makes it easier to copy and paste for debugging
    if (script.length > kMaxURLChars - 1000) {
      val alphabet: List<Char> = ('a'..'z') + ('A'..'Z')
      val randomString: String = List(14) { alphabet.random() }.joinToString("")
      loadUrl("javascript: void(globalThis.${randomString} = '');")
      script.chunked(2000000).forEach {
        val code =
            URLEncoder.encode(
                    """void(globalThis.${randomString} += `${it.replace("`", "\\`")}`);""", "UTF-8")
                .replace("+", "%20")
        loadUrl("javascript: ${code}")
      }
      loadUrl("javascript: Function(globalThis.${randomString})();")
    } else {
      val code = URLEncoder.encode(script, "UTF-8").replace("+", "%20")
      loadUrl("javascript: ${code}")
    }
  }

  fun parseUrl(packed: Any): String? {
    if (packed::class.qualifiedName == loadUrlParams!!.name) {
      return mUrl!!.get(packed) as String
    } else if (packed::class.qualifiedName == gURL!!.name) {
      return mSpec!!.get(packed) as String
    }
    Log.e(
        "parseUrl: ${packed::class.qualifiedName} is not ${loadUrlParams!!.name} nor ${gURL!!.getName()}")
    return null
  }

  private fun parseArray(str: String): List<String> {
    return str.removeSurrounding("[\"", "\"]").split("\",\"").map { it.replace("\\", "") }
  }

  fun scriptManager(action: String, payload: String): String? {
    var callback: String? = null
    when (action) {
      "installScript" -> {
        val script = parseScript(payload)
        if (script == null) {
          callback = "alert('Invalid UserScript')"
        } else {
          Log.i("Install script ${script.id}")
          scriptDao!!.insertAll(script)
        }
      }
      "getIds" -> {
        val result = scriptDao!!.getAll().map { it.id.replace("'", "\\'") }
        evaluateJavaScript("console.log(['${result.joinToString(separator = "','")}'])")
      }
      // "getScriptById" -> {
      //   runCatching {
      //         val ids = parseArray(payload)
      //         val result = scriptDao!!.getScriptById(ids).map { it.code.replace("'", "\\'") }
      //         evaluateJavaScript("console.log(['${result.joinToString(separator = "','")}'])")
      //       }
      //       .onFailure { evaluateJavaScript("console.error(${it.toString()})") }
      // }
      // "getIdByRunAt" -> {
      //   runCatching {
      //         val runAts =
      //             parseArray(payload).map {
      //               when (it) {
      //                 "document-start" -> RunAt.START
      //                 "document-end" -> RunAt.END
      //                 "document-idle" -> RunAt.IDLE
      //                 else -> RunAt.IDLE
      //               }
      // }
      // val result = scriptDao!!.getIdByRunAt(runAts).map { it.id.replace("'", "\\'") }
      // evaluateJavaScript("console.log(['${result.joinToString(separator = "','")}'])")
      // }
      // .onFailure { evaluateJavaScript("console.error(${it.toString()})") }
      // }
      "deleteScriptById" -> {
        runCatching {
              val ids = parseArray(payload)
              scriptDao!!.getAll().forEach {
                if (ids.contains(it.id)) {
                  if (scriptDao!!.delete(it) == 1) {
                    evaluateJavaScript("console.log(`${it.id} deleted!`)")
                  }
                }
              }
            }
            .onFailure { evaluateJavaScript("console.error(${it.toString()})") }
      }
    }
    return callback
  }

  // fun fixCharset(packed: Any): Boolean {
  //   if (packed::class.qualifiedName == loadUrlParams!!.name) {
  //     mVerbatimHeaders!!.set(packed, "accept: charset=utf-8,text/javascript")
  //     // Log.i("Fix Charset for ${mUrl!!.get(packed)}")
  //     return true
  //   }
  //   Log.e("fixCharset: ${packed::class.qualifiedName} is not ${loadUrlParams!!.name}")
  //   return false
  // }

  fun updateTabDelegator(delegator: Any): Boolean {
    if (delegator::class.qualifiedName == tabWebContentsDelegateAndroidImpl!!.name) {
      if (tabDelegator != delegator) {
        Log.d("tabDelegator updated")
        tabDelegator = delegator
      }
      return true
    }
    Log.e(
        "updateTabDelegator: ${delegator::class.qualifiedName} is not ${tabWebContentsDelegateAndroidImpl!!.name}")
    return false
  }

  // fun updateNavController(controller: Any): Boolean {
  //   if (controller::class.qualifiedName == navigationControllerImpl!!.name) {
  //     if (navController != controller) {
  //       Log.i("navController updated")
  //       // navController = controller
  //     }
  //     return true
  //   }
  //   Log.e(
  //       "updateNavController: ${controller::class.qualifiedName} is not
  // ${navigationControllerImpl!!.name}")
  //   return false
  // }

  fun didUpdateUrl(url: String) {
    if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file://")) {
      invokeScript(url)
    }
    eruda_loaded = false
    eruda_font_fixed = false
  }

  // fun didStartLoading(url: String) {
  //   invokeScriptAt(RunAt.START, url)
  // }

  // fun didStopLoading(url: String) {
  //   invokeScriptAt(RunAt.END, url)
  // }

  fun openDevTools(ctx: Context) {
    if (!eruda_loaded) {
      val sharedPref: SharedPreferences = ctx.getSharedPreferences("ChromeXt", Context.MODE_PRIVATE)
      val eruda = sharedPref.getString("eruda", "")
      if (eruda != "") {
        evaluateJavaScript(eruda!!)
        evaluateJavaScript(erudaToggle)
        eruda_loaded = true
      } else {
        Log.toast(ctx, "Please update Eruda in the Developper options menu")
      }
    } else {
      evaluateJavaScript(erudaToggle)
      if (eruda_font_fixed) {
        evaluateJavaScript(erudaFontFix)
      }
    }
  }

  fun fixErudaFont() {
    if (eruda_loaded && !eruda_font_fixed) {
      evaluateJavaScript(erudaFontFix)
      eruda_font_fixed = true
    }
  }
}
