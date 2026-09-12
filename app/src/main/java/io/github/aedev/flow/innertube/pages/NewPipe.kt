package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Proxy

class NewPipeDownloaderImpl(
    proxy: Proxy?,
    proxyAuth: String? = null,
) : Downloader() {
    companion object {
        // Verbs OkHttp's Request.Builder.method() refuses to send without a body.
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }

    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    private fun buildOkHttpRequest(request: Request): okhttp3.Request {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        // OkHttp's method(name, body) throws "method POST must have a request body" for any
        // body-requiring verb (POST/PUT/PATCH/...) given a null body - dataToSend is null
        // whenever the extractor issues a bodyless POST, so fall back to an empty body instead
        // of passing the null straight through.
        val requestBody =
            dataToSend?.toRequestBody()
                ?: if (httpMethod in BODY_REQUIRED_METHODS) ByteArray(0).toRequestBody() else null
        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        return requestBuilder.build()
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun toNewPipeResponse(response: okhttp3.Response, url: String): Response {
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val rawBody = response.body?.bytes() ?: ByteArray(0)
        val responseBodyToReturn = String(rawBody, Charsets.UTF_8)
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, rawBody, latestUrl)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val response = client.newCall(buildOkHttpRequest(request)).execute()
        return toNewPipeResponse(response, request.url())
    }

    override fun executeAsync(request: Request, callback: Downloader.AsyncCallback): CancellableCall {
        val call = client.newCall(buildOkHttpRequest(request))
        val cancellableCall = CancellableCall(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try {
                    callback.onError(e)
                } finally {
                    cancellableCall.setFinished()
                }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    response.use {
                        callback.onSuccess(toNewPipeResponse(response, request.url()))
                    }
                } catch (e: Exception) {
                    callback.onError(e)
                } finally {
                    cancellableCall.setFinished()
                }
            }
        })
        return cancellableCall
    }
}

class NewPipeUtils(
    downloader: Downloader,
) {
    init {
        NewPipe.init(downloader)
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }

    fun deobfuscateThrottling(videoId: String, url: String): String? =
        try {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        } catch (e: Exception) {
            android.util.Log.w("NewPipeUtils", "nsig deobfuscation threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? =
        try {
            val url =
                format.url ?: format.signatureCipher?.let { signatureCipher ->
                    val params = parseQueryString(signatureCipher)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")
                    url.parameters[signatureParam] =
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                    url.buildString()
                } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
}

object NewPipeExtractor {
    private var newPipeDownloader: NewPipeDownloaderImpl? = null
    private var newPipeUtils: NewPipeUtils? = null
    private var isInitialized = false
    private var lastProxySignature: String? = null

    fun init() {
        val currentSignature = listOf(YouTube.proxy?.address(), YouTube.proxyAuth).joinToString(separator = "|")
        if (!isInitialized || lastProxySignature != currentSignature) {
            newPipeDownloader = NewPipeDownloaderImpl(
                proxy = YouTube.proxy,
                proxyAuth = YouTube.proxyAuth
            )
            newPipeUtils = NewPipeUtils(newPipeDownloader!!)
            isInitialized = true
            lastProxySignature = currentSignature
        }
    }

    fun invalidateClient() {
        newPipeDownloader = null
        newPipeUtils = null
        isInitialized = false
        lastProxySignature = null
        nsigThrewThisSession = false
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> {
        init()
        return newPipeUtils?.getSignatureTimestamp(videoId)
            ?: Result.failure(Exception("NewPipeUtils not initialized"))
    }

    @Volatile
    private var nsigThrewThisSession = false

    fun deobfuscateThrottling(videoId: String, url: String): String? {
        if (nsigThrewThisSession) return null
        init()
        val result = newPipeUtils?.deobfuscateThrottling(videoId, url)
        if (result == null || (url.contains("n=") && result == url)) {
            nsigThrewThisSession = true
            android.util.Log.w(
                "NewPipeExtractor",
                "NewPipe nsig ineffective (${if (result == null) "threw" else "unchanged"}) — disabling for this session, falling back to home-grown"
            )
        }
        return result
    }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        init()
        return newPipeUtils?.getStreamUrl(format, videoId)
    }

    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        init()
        return try {
            val streamInfo = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
