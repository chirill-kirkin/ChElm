package io.github.chirillkirkin.mvu.samples.savedstate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.andCommand
import io.github.chirillkirkin.mvu.only
import io.github.chirillkirkin.mvu.savedstate.SavedStateMVU
import io.github.chirillkirkin.mvu.savedstate.mvuStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

private const val COUNTER_STATE_KEY = "counter_state"
private const val STORED_COUNT = 10
private val loadDelay = 2.seconds

@Serializable
data class CounterState(val count: Int? = null, val note: String = "")

sealed interface CounterMessage {
  data class CountLoaded(val count: Int) : CounterMessage

  data object Increment : CounterMessage

  data object Decrement : CounterMessage

  data object Reset : CounterMessage

  data class NoteChanged(val note: String) : CounterMessage
}

sealed interface CounterCommand {
  data object LoadCount : CounterCommand

  data object ShowResetMessage : CounterCommand
}

val counterUpdate: Update<CounterMessage, CounterState, CounterCommand> = { message, state ->
  when (message) {
    is CounterMessage.CountLoaded -> state.copy(count = message.count).only()
    CounterMessage.Increment -> state.copy(count = state.count?.inc()).only()
    CounterMessage.Decrement -> state.copy(count = state.count?.dec()).only()
    CounterMessage.Reset -> state.copy(count = 0) andCommand CounterCommand.ShowResetMessage
    is CounterMessage.NoteChanged -> state.copy(note = message.note).only()
  }
}

val counterCommandExecutor: CommandExecutor<CounterCommand, CounterMessage> = { command ->
  when (command) {
    CounterCommand.LoadCount -> flowOf(CounterMessage.CountLoaded(STORED_COUNT)).onStart { delay(loadDelay) }
    CounterCommand.ShowResetMessage -> emptyFlow()
  }
}

class CounterViewModel(savedStateHandle: SavedStateHandle) :
  ViewModel(),
  SavedStateMVU<CounterMessage, CounterState, CounterCommand> by savedStateHandle.mvuStore(
    initialState = CounterState(),
    update = counterUpdate,
    commandExecutor = counterCommandExecutor,
    serializer = CounterState.serializer(),
    stateKey = COUNTER_STATE_KEY,
    initialCommands = { state, _ ->
      if (state.count == null) listOf(CounterCommand.LoadCount) else emptyList()
    },
  ) {
  init {
    launchIn(viewModelScope)
  }
}
