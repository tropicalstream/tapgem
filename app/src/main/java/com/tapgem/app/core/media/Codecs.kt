package com.tapgem.app.core.media

import android.media.MediaCodecList
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * What this device can really decode. Sites choose a codec by asking the browser, and Chromium
 * answers for every codec it has *a* decoder for — including Android's software AV1 decoder,
 * which on this SoC manages a few dozen frames and stalls. YouTube then serves AV1, plays 0.7 s,
 * and pauses: "the video loads but doesn't play". The fix is to answer truthfully: a codec with
 * no hardware decoder is not supported here, so the site falls back to VP9 or H.264, which are.
 */
object Codecs {
    private const val TAG = "Codecs"

    /** MIME types with no hardware-accelerated decoder on this device. */
    val unsupported: Set<String> by lazy {
        val want = mapOf("video/av01" to "av01|av1", "video/hevc" to "hev1|hvc1|hevc", "video/x-vnd.on2.vp9" to "vp09|vp9")
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        want.filter { (mime, _) -> infos.none { c -> c.isHardwareAccelerated && c.supportedTypes.any { it.equals(mime, true) } } }
            .values.toSet()
            .also { Log.i(TAG, "no hardware decoder for: ${if (it.isEmpty()) "nothing — all fine" else it.joinToString()}") }
    }

    /** JavaScript that makes the page's codec queries answer like the hardware would. */
    private fun script(): String? {
        if (unsupported.isEmpty()) return null
        val re = unsupported.joinToString("|")
        return """(function(){var block=/(?:$re)/i;
try{var mst=MediaSource.isTypeSupported.bind(MediaSource);MediaSource.isTypeSupported=function(t){return block.test(t)?false:mst(t)};}catch(e){}
try{var cpt=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(t){return block.test(t)?'':cpt.call(this,t)};}catch(e){}
try{if(navigator.mediaCapabilities&&navigator.mediaCapabilities.decodingInfo){var di=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
navigator.mediaCapabilities.decodingInfo=function(c){return (c&&c.video&&block.test(c.video.contentType||''))?Promise.resolve({supported:false,smooth:false,powerEfficient:false,configuration:c}):di(c)}}}catch(e){}
})();"""
    }

    /**
     * Install the override on a WebView. Before any page script when the WebView supports
     * document-start scripts (this one does); [onPageStarted] is the fallback and belt-and-braces
     * for single-page sites that probe codecs long after load.
     */
    fun install(wv: WebView) {
        val js = script() ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*")) }
                .onFailure { Log.w(TAG, "document-start script refused: ${it.message}") }
    }
    fun onPageStarted(wv: WebView) { script()?.let { wv.evaluateJavascript(it, null) } }
}
