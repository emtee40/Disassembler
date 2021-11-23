package com.kyhsgeekcode.disassembler.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.kyhsgeekcode.disassembler.ui.tabs.BinaryTab
import com.kyhsgeekcode.disassembler.ui.tabs.ImageTab
import com.kyhsgeekcode.disassembler.ui.tabs.TextTab
import com.kyhsgeekcode.disassembler.viewmodel.MainViewModel


@ExperimentalFoundationApi
@Composable
fun OpenedTabs(viewModel: MainViewModel) {
    var state by remember { mutableStateOf(0) }
//    val titles = listOf("TAB 1", "TAB 2", "TAB 3")
    val tabs = viewModel.openedTabs.collectAsState()
    val titles = tabs.value.map {
        it.title
    }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = state,
        ) {
            titles.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = state == index,
                    onClick = { state = index }
                )
            }
        }
        TabContent(state, viewModel)
    }
}

@ExperimentalFoundationApi
@Composable
fun TabContent(state: Int, viewModel: MainViewModel) {
    val theTab = viewModel.openedTabs.value[state]
    when (val tabKind = theTab.tabKind) {
        is TabKind.AnalysisResult -> TODO()
        is TabKind.Apk -> TODO()
        is TabKind.Archive -> TODO()
        is TabKind.Binary -> BinaryTab(data = theTab, viewModel = viewModel)
        is TabKind.Dex -> TODO()
        is TabKind.DotNet -> TODO()
        is TabKind.Image -> ImageTab(theTab, viewModel)
        is TabKind.Text -> TextTab(theTab, viewModel)
        is TabKind.ProjectOverview -> ProjectOverview(viewModel)
        is TabKind.FoundString -> TODO()
        is TabKind.Hex -> TODO()
        is TabKind.Log -> TODO()
    }
}
