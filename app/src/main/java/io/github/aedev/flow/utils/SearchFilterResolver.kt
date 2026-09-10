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
    /** "all" is always id 0 for every service that supports content filters at all (verified: it's
     * the first `addFilterItem` call in each service's Filters `init()`), so a plain unfiltered
     * search/listing can ask for it by name instead of hardcoding the identifier. */
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
     * Like [resolveContentFilters], but for search specifically: an empty/unresolvable filter list
     * is NOT a safe "no filter" substitute here - the search-filter framework (e.g. YoutubeFilters)
     * has no graceful default for a truly empty selected-content-filter list and throws. Falls back
     * to the "all" filter so callers always get a usable, non-empty list.
     */
    fun resolveSearchContentFilters(
        service: StreamingService,
        names: List<String>,
    ): List<FilterItem> {
        val resolved = resolveContentFilters(service, names)
        if (resolved.isNotEmpty()) return resolved
        return resolveContentFilters(service, listOf(DEFAULT_CONTENT_FILTER_NAME))
    }
}
