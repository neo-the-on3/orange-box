package io.github.neotheon3.orangebox.feature.trips.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.api.BoundingBox
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.addPath
import ovh.plrapps.mapcompose.api.snapScrollTo
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.ui.state.MapState

/** A marker on the route, e.g. a harsh-brake event. */
data class RouteMarker(val id: String, val position: GeoPoint, val label: String, val color: Color, val description: String)

/**
 * The recorded route on a keyless map. The polyline and markers are always drawn by the app; tiles from
 * [tiles] are only a backdrop (null = tiles off, or offline). Attribution is always visible with tiles on.
 */
@Composable
fun RouteMap(
    route: List<GeoPoint>,
    markers: List<RouteMarker>,
    tiles: TileStreamProvider?,
    routeDescription: String,
    modifier: Modifier = Modifier,
) {
    val routeColor = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val state = remember(route, markers, tiles) {
        MapState(
            levelCount = MapTiles.MAX_ZOOM + 1,
            fullWidth = WORLD_PX,
            fullHeight = WORLD_PX,
            tileSize = MapTiles.TILE_SIZE,
            workerCount = TILE_WORKERS,
        ).apply {
            tiles?.let { addLayer(it, initialOpacity = if (dark) 0.75f else 1f) }
            val projected = route.map(WebMercator::project)
            addPath("route", width = 4.dp, color = routeColor) { addPoints(projected.map { it.x to it.y }) }
            val start = projected.first()
            val end = projected.last()
            addMarker("start", start.x, start.y, relativeOffset = Offset(-0.5f, -0.5f), clickable = false) {
                Pin("", START_COLOR, "Start")
            }
            addMarker("end", end.x, end.y, relativeOffset = Offset(-0.5f, -0.5f), clickable = false) {
                Pin("", END_COLOR, "End", square = true)
            }
            markers.forEach { m ->
                val p = WebMercator.project(m.position)
                addMarker(m.id, p.x, p.y, relativeOffset = Offset(-0.5f, -0.5f), clickable = false) {
                    Pin(m.label, m.color, m.description, small = true)
                }
            }
        }
    }
    LaunchedEffect(state) {
        val b = MapBounds.of(route.map(WebMercator::project))!!.withMinimumSize(MIN_SPAN)
        state.snapScrollTo(BoundingBox(b.left, b.top, b.right, b.bottom), padding = Offset(0.2f, 0.2f))
    }
    DisposableEffect(state) { onDispose { state.shutdown() } }
    Box(
        modifier
            .background(if (dark) Color(0xFF1E2224) else Color(0xFFE8ECEB))
            .semantics { contentDescription = routeDescription }
            .testTag("trip_map"),
    ) {
        MapUI(Modifier.matchParentSize(), state = state)
        if (tiles != null) Attribution(Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun Attribution(modifier: Modifier) {
    val uri = LocalUriHandler.current
    Text(
        MapTiles.ATTRIBUTION,
        fontSize = 11.sp,
        color = Color(0xFF202124),
        modifier = modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(topStart = 4.dp))
            .clickable { runCatching { uri.openUri(MapTiles.COPYRIGHT_URL) } }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("trip_map_attribution"),
    )
}

@Composable
private fun Pin(label: String, color: Color, description: String, small: Boolean = false, square: Boolean = false) {
    val shape = if (square) RoundedCornerShape(3.dp) else CircleShape
    Box(
        Modifier
            .size(if (small) 18.dp else 20.dp)
            .background(color, shape)
            .border(2.dp, Color.White, shape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (small) 9.sp else 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** World size in pixels at the deepest level: 256 · 2^18. */
private const val WORLD_PX = MapTiles.TILE_SIZE shl MapTiles.MAX_ZOOM
/** A few tiles at z16, so a very short trip doesn't zoom past the deepest tiles. */
private const val MIN_SPAN = 4.0 / (1 shl 16)
private const val TILE_WORKERS = 4
val START_COLOR = Color(0xFF2E7D32)
val END_COLOR = Color(0xFF212121)
