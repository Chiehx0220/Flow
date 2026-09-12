package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.utils.shareVideo
import org.schabi.newpipe.extractor.ServiceList

/**
 * A share callback that already honours the user's "share without text" preference, so a component
 * outside the player package can raise the same chooser the player's info row does without
 * importing a feature package or re-deriving the payload.
 */
@Composable
fun rememberVideoShareAction(): (videoId: String, title: String, serviceId: Int) -> Unit {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val shareWithoutText by preferences.shareWithoutText.collectAsStateWithLifecycle(initialValue = false)
    val linkOnly by rememberUpdatedState(shareWithoutText)
    return remember(context) {
        { videoId: String, title: String, serviceId: Int ->
            shareVideo(context, videoId, title, linkOnly, serviceId)
        }
    }
}
