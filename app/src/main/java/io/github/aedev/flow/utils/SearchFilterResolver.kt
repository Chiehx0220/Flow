package io.github.aedev.flow.utils

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.search.filter.FilterItem

/**
 * PipePipeExtractor's `getSearchExtractor`/`getChannelExtractor` overloads take
 * `List<FilterItem>` instead of a plain `List<String>`/sort string, and a [FilterItem] carries a
 * service-assigned numeric identifier with no public constant - the only way to get one (e.g.
 * "channels only") is to look it up by name among the factory's own available filters. Shared so
 * every call site resolves filters the same way instead of re-deriving this.
 */
object SearchFilterResolver {
    /** "all" is id 0 for YouTube and some other services (it's the first `addFilterItem` call in
     * their Filters `init()`), so a plain unfiltered search/listing can ask for it by name first.
     * NOT universal though - Bilibili's `BilibiliFilters` has no "all" content filter at all (only
     * videos/lives/channels/animes/movies_and_tv), so this name lookup alone silently resolves to
     * an empty list for it. See [defaultContentFilter] for the actual generic fallback. */
    const val DEFAULT_CONTENT_FILTER_NAME = "all"

    fun resolveContentFilters(
        factory: ListLinkHandlerFactory,
        names: List<String>,
    ): List<FilterItem> {
        if (names.isEmpty()) return emptyList()
        val allItems =
            factory.availableContentFilter
                ?.filterGroups
                ?.flatMap { it.filterItems.asList() }
                ?: return emptyList()
        return names.mapNotNull { name -> allItems.firstOrNull { it.name == name } }
    }

    fun resolveContentFilters(
        service: StreamingService,
        names: List<String>,
    ): List<FilterItem> = resolveContentFilters(service.searchQHFactory, names)

    /**
     * The service's own default content filter, used when nothing more specific resolved. Every
     * service we've checked (YouTube, Bilibili) registers its intended default as the very first
     * filter item of the very first filter group - "all" for YouTube, "videos" for Bilibili (which
     * has no "all") - so reading that first item generically finds the right default without
     * hardcoding per-service names. Falls back to [DEFAULT_CONTENT_FILTER_NAME] by name if the
     * factory exposes no groups at all (defensive; shouldn't happen for a real service).
     */
    private fun defaultContentFilter(factory: ListLinkHandlerFactory): List<FilterItem> {
        val firstItem =
            factory.availableContentFilter
                ?.filterGroups
                ?.firstOrNull()
                ?.filterItems
                ?.firstOrNull()
        if (firstItem != null) return listOf(firstItem)
        return resolveContentFilters(factory, listOf(DEFAULT_CONTENT_FILTER_NAME))
    }

    /**
     * Like [resolveContentFilters], but for search specifically: an empty/unresolvable filter list
     * is NOT a safe "no filter" substitute here - the search-filter framework (e.g. YoutubeFilters)
     * has no graceful default for a truly empty selected-content-filter list and throws. Falls back
     * to the service's own default filter (see [defaultContentFilter]) so callers always get a
     * usable, non-empty list.
     */
    fun resolveSearchContentFilters(
        service: StreamingService,
        names: List<String>,
    ): List<FilterItem> {
        val resolved = resolveContentFilters(service, names)
        if (resolved.isNotEmpty()) return resolved
        return defaultContentFilter(service.searchQHFactory)
    }
}
