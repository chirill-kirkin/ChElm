@file:OptIn(FlowPreview::class)

package io.github.chirillkirkin.mvu.savedstate

import androidx.lifecycle.SavedStateHandle
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.MVU
import io.github.chirillkirkin.mvu.MVUStore
import io.github.chirillkirkin.mvu.Subscription
import io.github.chirillkirkin.mvu.Update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer

/** An [MVU] feature whose state is saved to a [SavedStateHandle]. */
public interface SavedStateMVU<Message, State, Command> : MVU<Message, State, Command> {
  /** Whether the initial state was restored from the [SavedStateHandle]. */
  public val isStateRestored: Boolean
}

/** A [SavedStateMVU] decorator that keeps the state of its delegate in a [SavedStateHandle]. */
public class SavedStateMVUStore<Message, State, Command> internal constructor(
  private val delegate: MVU<Message, State, Command>,
  override val isStateRestored: Boolean,
  private val startSaving: (states: StateFlow<State>, scope: CoroutineScope) -> Unit,
) : SavedStateMVU<Message, State, Command> {
  override val state: StateFlow<State> = delegate.state
  override val commands: Flow<Command> = delegate.commands

  override fun send(message: Message) {
    delegate.send(message)
  }

  override fun launchIn(scope: CoroutineScope) {
    startSaving(delegate.state, scope)
    delegate.launchIn(scope)
  }
}

/**
 * Creates an MVU store that persists its entire state encoded with [serializer].
 *
 * The state is encoded only when the [SavedStateHandle] is saved, not on every change.
 * [initialCommands] receives the state the store starts with, restored or initial, and whether it
 * was restored, so initialization work can be skipped when the state already contains its result.
 * Keep the state small and use the projection overload for state containing data that does not
 * need to survive process death.
 */
public fun <Message, State : Any, Command> SavedStateHandle.mvuStore(
  initialState: State,
  update: Update<Message, State, Command>,
  commandExecutor: CommandExecutor<Command, Message>,
  serializer: KSerializer<State>,
  stateKey: String,
  configuration: SavedStateConfiguration = SavedStateConfiguration.DEFAULT,
  initialCommands: (state: State, isStateRestored: Boolean) -> List<Command> = { _, _ -> emptyList() },
  subscriptions: List<Subscription<State, Message>> = emptyList(),
  concurrency: Int = DEFAULT_CONCURRENCY,
  onCommandException: (command: Command, throwable: Throwable) -> Unit = { _, _ -> },
): SavedStateMVUStore<Message, State, Command> = savedStateMVUStore(
  initialState = initialState,
  update = update,
  commandExecutor = commandExecutor,
  restoreState = { savedState: SavedState, _ -> decodeFromSavedState(serializer, savedState, configuration) },
  startSaving = { states, _ ->
    setSavedStateProvider(stateKey) { encodeToSavedState(serializer, states.value, configuration) }
  },
  stateKey = stateKey,
  initialCommands = initialCommands,
  subscriptions = subscriptions,
  concurrency = concurrency,
  onCommandException = onCommandException,
)

/**
 * Creates an MVU store that persists a projection of its state.
 *
 * [SavedState] must be a type supported by [SavedStateHandle]. [restoreState] builds the state from
 * the saved projection and [initialState]. [initialCommands] receives the state the store starts
 * with, restored or initial, and whether it was restored.
 */
public fun <Message, State : Any, SavedState : Any, Command> SavedStateHandle.mvuStore(
  initialState: State,
  update: Update<Message, State, Command>,
  commandExecutor: CommandExecutor<Command, Message>,
  saveState: (State) -> SavedState,
  restoreState: (savedState: SavedState, initialState: State) -> State,
  stateKey: String,
  initialCommands: (state: State, isStateRestored: Boolean) -> List<Command> = { _, _ -> emptyList() },
  subscriptions: List<Subscription<State, Message>> = emptyList(),
  concurrency: Int = DEFAULT_CONCURRENCY,
  onCommandException: (command: Command, throwable: Throwable) -> Unit = { _, _ -> },
): SavedStateMVUStore<Message, State, Command> = savedStateMVUStore(
  initialState = initialState,
  update = update,
  commandExecutor = commandExecutor,
  restoreState = restoreState,
  startSaving = { states, scope ->
    states
      .onEach { state -> this[stateKey] = saveState(state) }
      .launchIn(scope)
  },
  stateKey = stateKey,
  initialCommands = initialCommands,
  subscriptions = subscriptions,
  concurrency = concurrency,
  onCommandException = onCommandException,
)

@Suppress("LongParameterList")
private fun <Message, State : Any, SavedState : Any, Command> SavedStateHandle.savedStateMVUStore(
  initialState: State,
  update: Update<Message, State, Command>,
  commandExecutor: CommandExecutor<Command, Message>,
  restoreState: (savedState: SavedState, initialState: State) -> State,
  startSaving: (states: StateFlow<State>, scope: CoroutineScope) -> Unit,
  stateKey: String,
  initialCommands: (state: State, isStateRestored: Boolean) -> List<Command>,
  subscriptions: List<Subscription<State, Message>>,
  concurrency: Int,
  onCommandException: (command: Command, throwable: Throwable) -> Unit,
): SavedStateMVUStore<Message, State, Command> {
  val savedState = get<SavedState>(stateKey)
  val isStateRestored = savedState != null
  val state = savedState?.let { restoreState(it, initialState) } ?: initialState
  val store = MVUStore(
    initialState = state,
    update = update,
    commandExecutor = commandExecutor,
    initialCommands = initialCommands(state, isStateRestored),
    subscriptions = subscriptions,
    concurrency = concurrency,
    onCommandException = onCommandException,
  )

  return SavedStateMVUStore(
    delegate = store,
    isStateRestored = isStateRestored,
    startSaving = startSaving,
  )
}
