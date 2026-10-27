package io.github.chirillkirkin.mvu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class SubscriptionTest {
  @Test
  fun `subscription starts and cancels work as state changes`() = runTest {
    var cancellations = 0
    val timer: Subscription<State, Message> = { states ->
      states
        .map { state -> state.running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
          if (running) {
            flow {
              try {
                while (true) {
                  emit(Message.Tick)
                  delay(1_000)
                }
              } finally {
                cancellations += 1
              }
            }
          } else {
            emptyFlow()
          }
        }
    }
    val store = MVUStore<Message, State, Command>(
      initialState = State(),
      update = { message, state ->
        when (message) {
          Message.Start -> state.copy(running = true).only()
          Message.Stop -> state.copy(running = false).only()
          Message.Tick -> state.copy(ticks = state.ticks + 1).only()
        }
      },
      commandExecutor = { emptyFlow() },
      subscriptions = listOf(timer),
    )
    store.launchIn(backgroundScope)

    store.send(Message.Start)
    runCurrent()
    assertTrue(store.state.value.ticks > 0)

    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(3, store.state.value.ticks)

    store.send(Message.Stop)
    runCurrent()
    val ticksWhenStopped = store.state.value.ticks

    assertFalse(store.state.value.running)
    assertEquals(1, cancellations)

    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(ticksWhenStopped, store.state.value.ticks)
  }

  private data class State(val running: Boolean = false, val ticks: Int = 0)

  private sealed interface Message {
    data object Start : Message

    data object Stop : Message

    data object Tick : Message
  }

  private sealed interface Command
}
