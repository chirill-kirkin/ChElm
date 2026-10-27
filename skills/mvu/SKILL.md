---
name: mvu
description: |
  Model-View-Update architecture for Kotlin using the mvu-core library API. Use when creating or reviewing an MVU feature, State, Message, Update, Command, CommandExecutor, Subscription, MVUStore integration, SavedStateHandle restoration with mvu-savedstate, or MVU tests. Trigger on MVU, Model-View-Update, Elm Architecture, MVUStore, SavedStateMVU, MVU CommandExecutor, state-driven Subscription, mvu-core, or mvu-savedstate requests.
---

# Kotlin MVU

## Vocabulary and contracts

Use the contracts from `io.github.chirillkirkin.mvu` (artifact `io.github.chirill-kirkin:mvu-core`). Do not introduce parallel abstractions named `Model`, `Action`, `Intent`, `Effect`, or `Event` when the existing types cover the requirement.

| Responsibility | Type |
|---|---|
| Complete feature state | Feature `State` |
| Every input to the update loop | Feature `Message` |
| Pure state transition | `Update<Message, State, Command>` |
| Transition result | `UpdateResult<State, Command>` |
| Description of external work | Feature `Command` |
| External work implementation | `CommandExecutor<Command, Message>` |
| State-dependent continuous input | `Subscription<State, Message>` |
| Running feature contract | `MVU<Message, State, Command>` |
| Default runtime | `MVUStore<Message, State, Command>` |
| Saved-state runtime | `SavedStateMVUStore<Message, State, Command>` |

The public function types are:

```kotlin
typealias Update<Message, State, Command> =
  (message: Message, state: State) -> UpdateResult<State, Command>

typealias CommandExecutor<Command, Message> =
  (command: Command) -> Flow<Message>

typealias Subscription<State, Message> =
  (state: StateFlow<State>) -> Flow<Message>
```

`MVU` exposes the current `state`, the `commands` flow, `send(message)`, and `launchIn(scope)`. `MVUStore` additionally accepts `initialState`, `update`, `commandExecutor`, optional `initialCommands`, optional `subscriptions`, command `concurrency`, and `onCommandException`.

## Keep Update pure

`Update` receives one `Message` and the current immutable `State`, then returns the next state and zero or more commands. Keep it deterministic and free of I/O, coroutine launches, mutable dependencies, and platform APIs. Only `Update` produces feature state transitions.

Use either a direct function or the `update { }` DSL. Prefer the style already used by the surrounding feature. The helpers `only()`, `andCommand`, and `andCommands` create concise `UpdateResult` values.

```kotlin
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.andCommand
import io.github.chirillkirkin.mvu.only

data class CounterState(
  val count: Int = 0,
  val isLoading: Boolean = false,
)

sealed interface CounterMessage {
  data object Increment : CounterMessage
  data object Refresh : CounterMessage
  data class CountLoaded(val count: Int) : CounterMessage
  data class LoadingFailed(val message: String) : CounterMessage
}

sealed interface CounterCommand {
  data object LoadCount : CounterCommand
}

val counterUpdate: Update<CounterMessage, CounterState, CounterCommand> =
  { message, state ->
    when (message) {
      CounterMessage.Increment -> state.copy(count = state.count + 1).only()
      CounterMessage.Refresh ->
        state.copy(isLoading = true) andCommand CounterCommand.LoadCount
      is CounterMessage.CountLoaded ->
        state.copy(count = message.count, isLoading = false).only()
      is CounterMessage.LoadingFailed -> state.copy(isLoading = false).only()
    }
  }
```

Represent user input, command results, subscription emissions, and internal transitions as `Message`. Never mutate state from a command or subscription.

## Execute external work through Command

`Command` is data describing finite external work. Keep repositories, I/O, and platform operations in `CommandExecutor`. It returns a cold `Flow<Message>` and may emit zero, one, or multiple messages.

Expected failures are domain outcomes: convert them to failure messages inside the command flow. Reserve `onCommandException` for unexpected failures. Its default callback is empty; it does not log, retry, or create a message.

Put fallible work inside the returned flow. The store isolates non-cancellation exceptions raised while collecting that flow. A synchronous exception thrown before `CommandExecutor` returns its flow is outside this boundary. Always preserve `CancellationException`.

```kotlin
import io.github.chirillkirkin.mvu.CommandExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

interface CounterRepository {
  suspend fun loadCount(): Int
}

fun counterCommandExecutor(
  repository: CounterRepository,
): CommandExecutor<CounterCommand, CounterMessage> = { command ->
  when (command) {
    CounterCommand.LoadCount -> flow {
      emit(CounterMessage.CountLoaded(repository.loadCount()))
    }.catch { throwable ->
      if (throwable is CancellationException) {
        throw throwable
      }
      emit(CounterMessage.LoadingFailed(throwable.message.orEmpty()))
    }
  }
}
```

## Use Subscription for state-driven ongoing work

Use a command for finite work requested once. Use a `Subscription` when an external source should remain active only while selected state says it is needed, such as a timer, connectivity observation, or a stream for the currently selected entity.

A subscription receives `StateFlow<State>` and returns `Flow<Message>`. Derive its activation flag or key from state, apply `distinctUntilChanged()`, then normally switch the underlying source with `flatMapLatest`. This cancels obsolete work when state changes.

```kotlin
import io.github.chirillkirkin.mvu.Subscription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class TimerState(
  val running: Boolean = false,
  val ticks: Int = 0,
)

sealed interface TimerMessage {
  data object Tick : TimerMessage
}

val timerSubscription: Subscription<TimerState, TimerMessage> = { states ->
  states
    .map { state -> state.running }
    .distinctUntilChanged()
    .flatMapLatest { running ->
      if (running) {
        flow {
          while (true) {
            delay(1_000)
            emit(TimerMessage.Tick)
          }
        }
      } else {
        emptyFlow()
      }
    }
}
```

Do not launch another coroutine from a subscription. Return the complete flow so `MVUStore` owns and cancels it.

## Runtime semantics

The data flow is:

```text
send(Message)
    -> sequential Update(Message, State)
    -> publish next State
    -> publish each Command to commands
    -> execute each Command concurrently, bounded by concurrency
    -> enqueue every emitted Message back into the sequential update loop
```

Commands are published to `commands` before they are submitted to `CommandExecutor`. Enqueue order is preserved, but completion order is not guaranteed when commands run concurrently.

Each command flow has its own failure boundary before flows are merged. A non-cancellation failure during collection stops only that command, invokes `onCommandException`, and leaves later commands executable. Do not throw from the callback.

`commands` is backed by `Channel.receiveAsFlow()`. It is single-consumer, not broadcast: multiple collectors divide commands instead of each receiving every value. Use exactly one logical consumer.

Create one store per feature instance and call `launchIn(ownerScope)` exactly once. Calling it repeatedly duplicates pipelines and initial commands. Cancelling the owner scope stops message processing, commands, and subscriptions; no separate close API is required.

## Saved state

Use `MVUStore` when state does not need restoration. Add `mvu-savedstate` (artifact `io.github.chirill-kirkin:mvu-savedstate`, package `io.github.chirillkirkin.mvu.savedstate`) only when state must survive process recreation. It creates a `SavedStateMVU` from a `SavedStateHandle`, typically inside a `ViewModel`, and works in common KMP code.

`mvu-savedstate` provides two `SavedStateHandle.mvuStore` overloads: full `@Serializable` State and a State projection. `stateKey` is mandatory; keep it stable and unique within the owner.

### Full Serializable State

Use the serialization overload when State is small and all fields are meaningful after recreation. Pass the generated serializer. The state is encoded with `androidx.savedstate.serialization` only when the `SavedStateHandle` is saved, not on every change. An optional `configuration: SavedStateConfiguration` supplies a custom `SerializersModule`.

```kotlin
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chirillkirkin.mvu.savedstate.SavedStateMVU
import io.github.chirillkirkin.mvu.savedstate.mvuStore
import kotlinx.serialization.Serializable

@Serializable
data class EditorState(
  val title: String = "",
  val body: String = "",
  val isDraftLoaded: Boolean = false,
)

private const val EDITOR_STATE_KEY = "editor_state"

class EditorViewModel(
  savedStateHandle: SavedStateHandle,
  repository: EditorRepository,
) : ViewModel(),
  SavedStateMVU<EditorMessage, EditorState, EditorCommand> by savedStateHandle.mvuStore(
    initialState = EditorState(),
    update = editorUpdate,
    commandExecutor = editorCommandExecutor(repository),
    serializer = EditorState.serializer(),
    stateKey = EDITOR_STATE_KEY,
    initialCommands = { state, _ ->
      if (state.isDraftLoaded) emptyList() else listOf(EditorCommand.LoadDraft)
    },
  ) {
  init {
    launchIn(viewModelScope)
  }
}
```

The adapter restores State while constructing the store. `state.value` is therefore restored before `launchIn`. The state is saved only after `launchIn`.

### State projection

Use the projection overload when only part of State should survive or State contains derived collections, large payloads, unsupported types, or transient execution flags. The projection is written to `SavedStateHandle` on every State change.

```kotlin
data class SearchState(
  val query: String = "",
  val results: List<String> = emptyList(),
  val isSearching: Boolean = false,
)

private const val SEARCH_QUERY_KEY = "search_query"

class SearchViewModel(
  savedStateHandle: SavedStateHandle,
  repository: SearchRepository,
) : ViewModel(),
  SavedStateMVU<SearchMessage, SearchState, SearchCommand> by savedStateHandle.mvuStore(
    initialState = SearchState(),
    update = searchUpdate,
    commandExecutor = searchCommandExecutor(repository),
    saveState = SearchState::query,
    restoreState = { query, initialState ->
      initialState.copy(query = query, isSearching = query.isNotEmpty())
    },
    stateKey = SEARCH_QUERY_KEY,
    initialCommands = { state, _ ->
      if (state.isSearching) listOf(SearchCommand.Search(state.query)) else emptyList()
    },
  ) {
  init {
    launchIn(viewModelScope)
  }
}
```

`restoreState` receives the saved projection and a fresh initial State. Merge into the fresh value so omitted transient fields retain current defaults. The saved projection must be a type supported by `SavedStateHandle`, such as a primitive, `String`, or a list of them; when the whole State should survive, use the serialization overload instead.

Do not persist repositories, coroutine objects, streams, or large response payloads. A saved `isLoading` or `isSaving` does not mean its old command survived. Reset transient flags or restart necessary work. `initialCommands` receives the State the store starts with, restored or initial, and `isStateRestored`. Decide from that State whether initialization is still needed: State saved before loading finished is restored without its result and still needs the load command.

## Testing

Use `kotlin.test` for tests and assertions. Use `kotlinx-coroutines-test` only for runtime, command, or subscription behavior. Pure update tests do not need `runTest`.

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class CounterUpdateTest {
  @Test
  fun `increment changes state without commands`() {
    val result = counterUpdate(
      CounterMessage.Increment,
      CounterState(count = 2),
    )

    assertEquals(CounterState(count = 3), result.state)
    assertEquals(emptyList(), result.commands)
  }
}
```

For store tests, use `runTest`, launch the store in `backgroundScope`, send messages, then use `runCurrent()` or virtual-time advancement before assertions. Do not use real sleeps.

Cover sequential message processing, command publication and feedback, initial commands, concurrency limits, failure isolation, callback arguments, cancellation propagation, subscription switching, and owner-scope cancellation. Keep test exceptions inside `flow { }` when testing the store's per-command failure boundary.

For saved-state stores, instantiate `SavedStateHandle` directly. Verify fresh and restored State, `isStateRestored`, State saving after launch, projection merging, and the State and restoration flag passed to `initialCommands`.

## Checklist

- The feature has one immutable State and exhaustive sealed Message and Command types.
- Update is pure and is the only state-transition authority.
- Commands describe work rather than storing jobs or mutable services.
- Expected failures return failure messages and cancellation is preserved.
- Ongoing state-dependent work is a Subscription.
- The store is launched once in its owner scope.
- Only one consumer collects commands.
- Core code has no Android SDK dependency.
- Saved-state support is optional and uses a mandatory constant key.
- Tests use deterministic virtual time when timing matters.
