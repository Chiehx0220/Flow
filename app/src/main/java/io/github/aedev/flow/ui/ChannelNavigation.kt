package io.github.aedev.flow.ui

import androidx.navigation.NavHostController
import org.schabi.newpipe.extractor.ServiceList
import java.net.URLDecoder

internal fun NavHostController.navigateToYoutubeChannel(
    channelIdOrHandle: String,
    serviceId: Int = ServiceList.YouTube.serviceId,
) {
    val targetUrl = youtubeChannelUrl(channelIdOrHandle, serviceId) ?: return
    val currentUrl = currentBackStackEntry
        ?.takeIf { it.destination.route == "channel?url={channelUrl}" }
        ?.arguments
        ?.getString("channelUrl")
        ?.let { encodedUrl ->
            runCatching { URLDecoder.decode(encodedUrl, Charsets.UTF_8.name()) }
                .getOrDefault(encodedUrl)
        }
        ?.let(::youtubeChannelUrl)

    if (currentUrl == targetUrl) return
    youtubeChannelRoute(targetUrl)?.let(::navigate)
}
