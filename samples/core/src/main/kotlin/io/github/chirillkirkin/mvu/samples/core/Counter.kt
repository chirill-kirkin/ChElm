package io.github.chirillkirkin.mvu.samples.core

import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.Subscription
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.andCommand
import io.github.chirillkirkin.mvu.only
import io.github.chirillkirkin.mvu.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Duration.Companion.milliseconds

private const val STORED_COUNT = 10
private val loadDelay = 300.milliseconds
private val tickInterval = 200.milliseconds

data class CounterState(val count: Int = 0, val isLoading: Boolean = false, val isTimerRunning: Boolean = false)

sealed interface CounterMessage {
  data object Increment : CounterMessage

  data object Reload : CounterMessage

  data class CountLoaded(val count: Int) : CounterMessage

  data object StartTimer : CounterMessage

  data object StopTimer : CounterMessage

  data object Tick : CounterMessage
}

sealed interface CounterCommand {
  data object LoadCount : CounterCommand
}

val counterUpdate: Update<CounterMessage, CounterState, CounterCommand> = { message, state ->
  when (message) {
    CounterMessage.Increment -> state.copy(count = state.count + 1).only()
    CounterMessage.Reload -> state.copy(isLoading = true) andCommand CounterCommand.LoadCount
    is CounterMessage.CountLoaded -> state.copy(count = message.count, isLoading = false).only()
    CounterMessage.StartTimer -> state.copy(isTimerRunning = true).only()
    CounterMessage.StopTimer -> state.copy(isTimerRunning = false).only()
    CounterMessage.Tick -> state.copy(count = state.count + 1).only()
  }
}

val counterDslUpdate: Update<CounterMessage, CounterState, CounterCommand> = update { message, _ ->
  when (message) {
    CounterMessage.Increment -> state { copy(count = count + 1) }

    CounterMessage.Reload -> {
      state { copy(isLoading = true) }
      command(CounterCommand.LoadCount)
    }

    is CounterMessage.CountLoaded -> state { copy(count = message.count, isLoading = false) }

    CounterMessage.StartTimer -> state { copy(isTimerRunning = true) }

    CounterMessage.StopTimer -> state { copy(isTimerRunning = false) }

    CounterMessage.Tick -> state { copy(count = count + 1) }
  }
}

val counterCommandExecutor: CommandExecutor<CounterCommand, CounterMessage> = { command ->
  when (command) {
    CounterCommand.LoadCount -> flowOf(CounterMessage.CountLoaded(STORED_COUNT)).onStart { delay(loadDelay) }
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
val timerSubscription: Subscription<CounterState, CounterMessage> = { states ->
  states
    .map { state -> state.isTimerRunning }
    .distinctUntilChanged()
    .flatMapLatest { isTimerRunning ->
      if (isTimerRunning) {
        flow {
          while (true) {
            delay(tickInterval)
            emit(CounterMessage.Tick)
          }
        }
      } else {
        emptyFlow()
      }
    }
}
