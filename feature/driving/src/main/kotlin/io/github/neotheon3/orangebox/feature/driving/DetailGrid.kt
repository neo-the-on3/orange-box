package io.github.neotheon3.orangebox.feature.driving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.ConnectChips
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.dashboardui.DetailTileState
import io.github.neotheon3.orangebox.core.dashboardui.PageDots
import io.github.neotheon3.orangebox.core.dashboardui.ValueTile
import io.github.neotheon3.orangebox.core.dashboardui.vector

/** Virtual pages per real page, so swiping wraps (last ↔ first) like the legacy adapter. */
private const val WRAP_FACTOR = 1_000

/** Detailed Info grid (§4.6): MIL/OBD chips, a wrapping pager of 3×3 tile pages and page dots. */
@Composable
fun DetailGrid(state: DashboardUiState, landscape: Boolean, modifier: Modifier = Modifier) {
    val pages = state.detailPages
    val pagerState = rememberPagerState(initialPage = pages.size * WRAP_FACTOR / 2) { pages.size * WRAP_FACTOR }
    Column(modifier.testTag("detail_grid"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (landscape) ConnectChips(state, Modifier.width(150.dp).padding(bottom = 6.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("detail_pager"),
            pageSpacing = 16.dp,
        ) { page ->
            val index = page % pages.size
            val title = stringResource(R.string.detail_page, index + 1, pages.size)
            DetailPage(pages[index], landscape, Modifier.fillMaxSize().semantics { paneTitle = title })
        }
        PageDots(pages.size, pagerState.currentPage % pages.size, Modifier.padding(vertical = 6.dp).testTag("detail_dots"))
        if (!landscape) ConnectChips(state, Modifier.width(180.dp).padding(bottom = 4.dp))
    }
}

/** 3×3: rows 1–2 small tiles (weight 46), row 3 large tiles with icon + glow (weight 70), 8 dp gutters. */
@Composable
private fun DetailPage(tiles: List<DetailTileState>, landscape: Boolean, modifier: Modifier) {
    val rows = tiles.chunked(3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { r, row ->
            val weight = if (r == rows.lastIndex) 70f else 46f
            Row(Modifier.fillMaxWidth().weight(weight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Portrait tiles are ~110 dp wide: let titles wrap to two lines instead of ellipsizing.
                row.forEach { Tile(it, if (landscape) 1 else 2, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun Tile(t: DetailTileState, titleLines: Int, modifier: Modifier) {
    ValueTile(
        title = stringResource(t.title),
        value = t.value,
        unit = t.unit,
        status = t.status,
        modifier = modifier,
        icon = t.icon?.vector,
        large = t.large,
        titleLines = titleLines,
    )
}
