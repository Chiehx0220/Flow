@file:Suppress("ktlint:standard:backing-property-naming")

package io.github.aedev.flow.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.SearchPagingSource
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.youtubeChannelUrl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import javax.inject.Inject

// ── UI state ─────────────────────────────────────────────────────────────────

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilter? = null,
    val serviceId: Int = ServiceList.YouTube.serviceId,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val shortsContentFilter: ShortsContentFilter,
        private val shortsQueueHandoff: ShortsQueueHandoff,
    ) : ViewModel() {
        // Signal each distinct submitted query once — typing/filter churn stays silent.
        private var lastSignaledQuery: String? = null
        private val _uiState = MutableStateFlow(SearchUiState())
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        /**
         * Internal trigger: emitting a new value here restarts the pager from page 0.
         * Holds (query, contentFilters) so the PagingSource gets fresh arguments.
         */
        private data class SearchKey(
            val query: String,
            val contentFilters: List<String>,
            val searchFilter: SearchFilter?,
            val serviceId: Int,
        )

        private val _searchKey = MutableStateFlow<SearchKey?>(null)

        /**
         * flatMapLatest restarts the pager whenever [_searchKey] changes (new search
         * or filter change), and cachedIn survives configuration changes.
         */
        val searchResults: Flow<PagingData<SearchResultItem>> =
            _searchKey
                .filterNotNull()
                .filter { it.query.isNotBlank() }
                .combine(shortsContentFilter.enabled) { key, shortsEnabled -> key to shortsEnabled }
                .flatMapLatest { (key, shortsEnabled) ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = 20,
                                prefetchDistance = 6,
                                enablePlaceholders = false,
                                initialLoadSize = 20,
                            ),
                        pagingSourceFactory = {
                            SearchPagingSource(key.query, key.contentFilters, key.searchFilter, shortsEnabled, key.serviceId)
                        },
                    ).flow
                }.cachedIn(viewModelScope)

        // ── public API ────────────────────────────────────────────────────────────

        fun shortsShelfSource(
            shelf: List<Video>,
            tapped: Video,
        ): ShortsQueueSource = shortsQueueHandoff.sourceForShelf(shelf, tapped)

        /**
         * Bilibili's lightweight search-result items carry no uploader URL at all - a gap in the
         * extractor itself (its search-result stream extractor never overrides getUploaderUrl(),
         * unlike the full watch-page one), so [Video.channelId] comes back blank straight out of
         * search. Falls back to fetching the video's own stream info - which does resolve the
         * uploader correctly - only when needed; YouTube hits (which always carry an id already)
         * skip the extra network round-trip entirely.
         */
        suspend fun resolveChannelForVideo(video: Video): Channel? {
            if (video.channelId.isNotBlank()) {
                return Channel(
                    id = video.channelId,
                    name = video.channelName,
                    thumbnailUrl = video.channelThumbnailUrl,
                    subscriberCount = 0,
                    url = youtubeChannelUrl(video.channelId, video.serviceId).orEmpty(),
                    serviceId = video.serviceId,
                )
            }
            if (video.serviceId == ServiceList.YouTube.serviceId) return null
            val resolved =
                runCatching {
                    repository.getVideo(video.id, NewPipe.getService(video.serviceId))
                }.getOrNull() ?: return null
            if (resolved.channelId.isBlank()) return null
            return Channel(
                id = resolved.channelId,
                name = resolved.channelName,
                thumbnailUrl = resolved.channelThumbnailUrl,
                subscriberCount = 0,
                url = youtubeChannelUrl(resolved.channelId, video.serviceId).orEmpty(),
                serviceId = video.serviceId,
            )
        }

        fun search(
            query: String,
            filters: SearchFilter? = null,
        ) {
            if (query.isBlank()) {
                _uiState.value = SearchUiState(serviceId = _uiState.value.serviceId)
                _searchKey.value = null
                return
            }
            val serviceId = _uiState.value.serviceId
            _uiState.value = SearchUiState(query = query, filters = filters, serviceId = serviceId)
            _searchKey.value = SearchKey(query, buildContentFilters(filters), filters, serviceId)

            // A typed search is the most explicit interest statement the user makes.
            val normalized = query.trim().lowercase()
            if (normalized != lastSignaledQuery) {
                lastSignaledQuery = normalized
                viewModelScope.launch {
                    runCatching { FlowNeuroEngine.onSearchQuery(context, query) }
                }
            }
        }

        fun updateFilters(filters: SearchFilter) {
            val currentQuery = _uiState.value.query
            _uiState.value = _uiState.value.copy(filters = filters)
            if (currentQuery.isNotBlank()) {
                _searchKey.value = SearchKey(currentQuery, buildContentFilters(filters), filters, _uiState.value.serviceId)
            }
        }

        /** Switch which streaming service (YouTube / Bilibili) search runs against. */
        fun setService(serviceId: Int) {
            if (serviceId == _uiState.value.serviceId) return
            _uiState.value = _uiState.value.copy(serviceId = serviceId)
            val currentQuery = _uiState.value.query
            if (currentQuery.isNotBlank()) {
                _searchKey.value = SearchKey(currentQuery, buildContentFilters(_uiState.value.filters), _uiState.value.filters, serviceId)
            }
        }

        fun clearSearch() {
            _uiState.value = SearchUiState()
            _searchKey.value = null
        }

        suspend fun getSearchSuggestions(query: String): List<String> {
            if (query.length < 2) return emptyList()
            return try {
                repository.getSearchSuggestions(query)
            } catch (_: Exception) {
                emptyList()
            }
        }

        // ── helpers ───────────────────────────────────────────────────────────────

        private fun buildContentFilters(filters: SearchFilter?): List<String> {
            val list = mutableListOf<String>()
            if (filters == null) return list

            when (filters.contentType) {
                ContentType.VIDEOS -> {
                    list.add("videos")
                }

                ContentType.CHANNELS -> {
                    list.add("channels")
                }

                ContentType.PLAYLISTS -> {
                    list.add("playlists")
                }

                ContentType.LIVE -> {
                    list.add("videos")
                }

                else -> {}
            }

            return list
        }
    }
