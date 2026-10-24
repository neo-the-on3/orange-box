package io.github.neotheon3.orangebox.driving

import io.github.neotheon3.orangebox.driving.DrivingNotifications.OngoingContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OngoingNotificationUpdaterTest {
    private val connected = OngoingContent(recording = false)
    private fun rec(km: String, t: String) = OngoingContent(true, km, t)

    @Test fun `identical content from every poll is never re-posted`() = runTest {
        val posts = mutableListOf<OngoingContent>()
        val polls = MutableSharedFlow<OngoingContent>()
        val updater = OngoingNotificationUpdater(now = { testScheduler.currentTime }) { posts += it }
        val job = launch { updater.run(polls, initial = connected) }
        runCurrent()
        // ~85 polls a minute with nothing visible changing (the #137 report).
        repeat(85) { polls.emit(connected); advanceTimeBy(700) }
        assertEquals(emptyList<OngoingContent>(), posts)
        job.cancel()
    }

    @Test fun `changes are posted at most once per second, latest wins`() = runTest {
        val posts = mutableListOf<Pair<Long, OngoingContent>>()
        val state = MutableStateFlow(connected)
        val updater = OngoingNotificationUpdater(now = { testScheduler.currentTime }) { posts += testScheduler.currentTime to it }
        val job = launch { updater.run(state, initial = connected) }
        runCurrent()
        advanceTimeBy(5_000)
        state.value = rec("0.0", "0:00:00"); runCurrent()      // first change after a quiet period: immediate
        advanceTimeBy(100); state.value = rec("0.0", "0:00:01")
        advanceTimeBy(100); state.value = rec("0.1", "0:00:01")
        advanceTimeBy(100); state.value = rec("0.1", "0:00:02")
        advanceTimeBy(2_000)
        assertEquals(listOf(rec("0.0", "0:00:00"), rec("0.1", "0:00:02")), posts.map { it.second })
        assertEquals(1_000L, posts[1].first - posts[0].first)
        job.cancel()
    }

    @Test fun `steady one-second updates post once per change`() = runTest {
        val posts = mutableListOf<OngoingContent>()
        val state = MutableStateFlow(connected)
        val updater = OngoingNotificationUpdater(now = { testScheduler.currentTime }) { posts += it }
        val job = launch { updater.run(state, initial = connected) }
        runCurrent()
        for (s in 1..60) {
            advanceTimeBy(1_000)
            // several polls per second, content only changes once
            repeat(3) { state.value = rec("1.0", "0:00:%02d".format(s % 60)) }
            runCurrent()
        }
        advanceTimeBy(2_000)
        assertEquals(60, posts.size)
        job.cancel()
    }
}
