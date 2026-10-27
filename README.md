# ChElm

> 🚧 Work in progress

ChElm (Ch[irkin]Elm) is a Kotlin Multiplatform UDF-architecture based on The Elm architecture (aka MVU, Model-View-Update).
All state changes happen in one pure function, and side effects are described as data and executed separately.

## Artifacts

- [`mvu-core`](mvu-core): the MVU runtime.
- [`mvu-savedstate`](mvu-savedstate): stores whose state survives process death via `SavedStateHandle`.

## Usage

<details>
<summary>Counter with a command</summary>

```kotlin
data class CounterState(val count: Int = 0, val isLoading: Boolean = false)

sealed interface CounterMessage {
  data object Increment : CounterMessage

  data object Reload : CounterMessage

  data class CountLoaded(val count: Int) : CounterMessage
}

sealed interface CounterCommand {
  data object LoadCount : CounterCommand
}

val counterUpdate: Update<CounterMessage, CounterState, CounterCommand> = { message, state ->
  when (message) {
    CounterMessage.Increment -> state.copy(count = state.count + 1).only()
    CounterMessage.Reload -> state.copy(isLoading = true) andCommand CounterCommand.LoadCount
    is CounterMessage.CountLoaded -> state.copy(count = message.count, isLoading = false).only()
  }
}

fun counterCommandExecutor(repository: CounterRepository): CommandExecutor<CounterCommand, CounterMessage> =
  { command ->
    when (command) {
      CounterCommand.LoadCount -> flow { emit(CounterMessage.CountLoaded(repository.loadCount())) }
    }
  }

val store = MVUStore(
  initialState = CounterState(),
  update = counterUpdate,
  commandExecutor = counterCommandExecutor(repository),
)

store.launchIn(scope)
store.send(CounterMessage.Reload)
```

</details>

The UI renders `store.state` and observes `store.commands`, for example for navigation.

### Saved state

<details>
<summary>ViewModel with a serializable state</summary>

```kotlin
@Serializable
data class EditorState(val title: String = "", val body: String = "")

class EditorViewModel(savedStateHandle: SavedStateHandle) :
  ViewModel(),
  SavedStateMVU<EditorMessage, EditorState, EditorCommand> by savedStateHandle.mvuStore(
    initialState = EditorState(),
    update = editorUpdate,
    commandExecutor = editorCommandExecutor,
    serializer = EditorState.serializer(),
    stateKey = "editor_state",
  ) {
  init {
    launchIn(viewModelScope)
  }
}
```

</details>

To persist only part of the state, use the overload with `saveState` and `restoreState`.

## Samples

- [`samples/core`](samples/core): update as a function and as a DSL, commands, a subscription.
- [`samples/savedstate`](samples/savedstate): full serializable state and a state projection.

## Agent skills

[`skills`](skills) contains instructions for coding agents: [`mvu`](skills/mvu/SKILL.md) for the core architecture and [`android-mvu`](skills/android-mvu/SKILL.md) for Android features.
