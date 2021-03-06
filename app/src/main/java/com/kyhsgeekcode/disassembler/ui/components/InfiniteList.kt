package com.kyhsgeekcode.disassembler.ui.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

// https://dev.to/luismierez/infinite-lazycolumn-in-jetpack-compose-44a4
@Composable
fun InfiniteList(
    onLoadMore: (Int, Int) -> Unit,
    modifier: Modifier,
    listState: LazyListState = rememberLazyListState(),
    Content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        Content()
    }

    InfiniteListHandler(listState = listState) { first, last ->
        onLoadMore(first, last)
    }
}

/**
 * Handler to make any lazy column (or lazy row) infinite. Will notify the [onLoadMore]
 * callback once needed
 * @param listState state of the list that needs to also be passed to the LazyColumn composable.
 * Get it by calling rememberLazyListState()
 * @param buffer the number of items before the end of the list to call the onLoadMore callback
 * @param onLoadMore will notify when we need to load more
 */
@Composable
fun InfiniteListHandler(listState: LazyListState, buffer: Int = 2, onLoadMore: (Int, Int) -> Unit) {
    val loadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

            lastVisibleItemIndex > (totalItemsNumber - buffer)
        }
    }

    LaunchedEffect(loadMore) {
        snapshotFlow { loadMore.value }
            .distinctUntilChanged()
            .collect {
                val layoutInfo = listState.layoutInfo
                val totalItemsNumber = layoutInfo.totalItemsCount
                val lastVisibleItemIndex =
                    (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
                val firstVisibleItemIndex =
                    (layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0) + 1
                onLoadMore(firstVisibleItemIndex, lastVisibleItemIndex)
            }
    }
}