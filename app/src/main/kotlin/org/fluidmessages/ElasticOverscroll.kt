package org.fluidmessages

import android.animation.ValueAnimator
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Elastic overscroll for the inbox: pulling past the top or bottom stretches the gaps between
 * conversations a little, rows farther from the edge travel slightly farther, and they snap back on release.
 * A fling that hits an edge gives a small bounce. With motion turned off it falls back to Android's stretch.
 */
internal class ElasticOverscroll(private val maxPull: Float) : OverscrollEffect {
    var pull by mutableFloatStateOf(0f)
        private set

    override val isInProgress: Boolean get() = pull != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        var remaining = delta.y
        var used = 0f
        // Dragging back first relaxes the stretch before the list scrolls again.
        if (pull != 0f && sign(remaining) != sign(pull)) {
            val relaxed = if (abs(remaining) > abs(pull)) -pull else remaining
            pull += relaxed
            if (abs(pull) < .5f) pull = 0f
            remaining -= relaxed
            used += relaxed
        }
        val scrolled = performScroll(Offset(0f, remaining)).y
        used += scrolled
        val left = remaining - scrolled
        if (source == NestedScrollSource.UserInput && left != 0f) {
            stretch(left)
            used += left
        }
        return Offset(delta.x, used)
    }

    /** Adds finger movement past the edge, with resistance that grows as the stretch does. */
    fun stretch(delta: Float) {
        val resistance = .32f * (1f - (abs(pull) / maxPull)).coerceIn(0f, 1f)
        pull = (pull + delta * resistance).coerceIn(-maxPull, maxPull)
    }

    /** Moves the stretch back toward rest by exactly the finger's movement. */
    fun relax(delta: Float) {
        pull += delta
        if (abs(pull) < .5f) pull = 0f
    }

    /** Lets go of the stretch: the rows snap back together. */
    suspend fun release(velocity: Float) { if (pull != 0f) settle(velocity * .15f) }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pull != 0f) {
            release(velocity.y)
            return
        }
        val left = performFling(velocity).y
        if (abs(left) > 50f) {
            // The list hit an edge mid-fling: bounce out and back.
            pull = 0f
            settle(left.coerceIn(-4000f, 4000f) * .06f)
        }
    }

    private suspend fun settle(velocity: Float) {
        // Stiff and lightly damped, so the rows snap back together with a small knock.
        animate(pull, 0f, velocity, spring(dampingRatio = .62f, stiffness = 1100f)) { value, _ ->
            pull = value.coerceIn(-maxPull, maxPull)
        }
        pull = 0f
    }
}

/** Elastic overscroll when motion is on, otherwise Android's standard stretch. */
@Composable
internal fun rememberInboxOverscroll(motion: Int): OverscrollEffect {
    val fallback = rememberOverscrollEffect()
    val maxPull = with(LocalDensity.current) { 90.dp.toPx() }
    val elastic = remember(maxPull) { ElasticOverscroll(maxPull) }
    return if (motion > 0 && ValueAnimator.areAnimatorsEnabled()) elastic else fallback ?: elastic
}

/** Moves a row with the stretch, more the farther it sits from the pulled edge. */
internal fun Modifier.elasticRow(effect: OverscrollEffect, list: LazyListState, index: Int): Modifier {
    if (effect !is ElasticOverscroll) return this
    return graphicsLayer {
        val pull = effect.pull
        if (pull == 0f) return@graphicsLayer
        val visible = list.layoutInfo.visibleItemsInfo
        val distance = if (pull > 0f) index - (visible.firstOrNull()?.index ?: 0)
            else (visible.lastOrNull()?.index ?: index) - index
        translationY = pull * (1f + distance.coerceIn(0, 10) * .045f)
    }
}

/**
 * Compose skips the overscroll effect when a list is too short to scroll, but still offers the drag to
 * nested-scroll parents. This picks it up there so short inboxes stretch the same way.
 */
@Composable
internal fun Modifier.elasticWhenShort(effect: OverscrollEffect, list: LazyListState): Modifier {
    if (effect !is ElasticOverscroll) return this
    val connection = remember(effect, list) {
        object : NestedScrollConnection {
            private val short get() = !list.canScrollForward && !list.canScrollBackward
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val pull = effect.pull
                if (!short || pull == 0f || sign(available.y) == sign(pull)) return Offset.Zero
                val relaxed = if (abs(available.y) > abs(pull)) -pull else available.y
                effect.relax(relaxed)
                return Offset(0f, relaxed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!short || source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                effect.stretch(available.y)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (effect.pull == 0f) return Velocity.Zero
                effect.release(available.y)
                return available
            }
        }
    }
    return nestedScroll(connection)
}
