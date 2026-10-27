---
name: android-mvu
description: |
  Android presentation architecture using the mvu-core library and the optional mvu-savedstate runtime. Use when creating or reviewing an Android MVU feature, ViewModel, Compose screen, UI command handling, Subscription, SavedStateHandle restoration, or Android-facing MVU tests. Trigger on requests that combine Android, ViewModel, Compose, or process-death restoration with MVU, Model-View-Update, Elm Architecture, MVUStore, or SavedStateMVUStore. Do not use for ViewModels that do not use this MVU runtime.
---

# Android MVU

Follow the core and Android rules below. Saved-state support is described in the `mvu` skill.

## Vocabulary and API

Use `io.github.chirillkirkin.mvu` (artifact `io.github.chirill-kirkin:mvu-core`) and the optional `io.github.chirillkirkin.mvu.savedstate` adapter (artifact `io.github.chirill-kirkin:mvu-savedstate`). Do not add parallel `Model`, `Action`, `Intent`, `Effect`, or `Event` types.

| Responsibility | Type |
|---|---|
| Complete UI state | Feature `State` |
| User input and every runtime result | Feature `Message` |
| Pure transition | `Update<Message, State, Command>` |
| Transition output | `UpdateResult<State, Command>` |
| Background work and one-time UI instruction | Feature `Command` |
| Command implementation | `CommandExecutor<Command, Message>` |
| State-dependent ongoing input | `Subscription<State, Message>` |
| Running feature contract | `MVU<Message, State, Command>` |
| Default runtime | `MVUStore<Message, State, Command>` |
| Saved-state runtime | `SavedStateMVUStore<Message, State, Command>` |

The core function types are:

```kotlin
typealias Update<Message, State, Command> =
  (message: Message, state: State) -> UpdateResult<State, Command>

typealias CommandExecutor<Command, Message> =
  (command: Command) -> Flow<Message>

typealias Subscription<State, Message> =
  (state: StateFlow<State>) -> Flow<Message>
```

`MVU` exposes `state`, `commands`, `send(message)`, and `launchIn(scope)`. `MVUStore` accepts `initialState`, `update`, `commandExecutor`, optional `initialCommands`, optional `subscriptions`, command `concurrency`, and `onCommandException`.

## Core feature rules

Keep `Update` deterministic and free of I/O, coroutine launches, Android APIs, and mutable dependencies. It receives one `Message` and immutable `State`, then returns the next state and zero or more commands. Only `Update` produces feature state transitions.

Use either a direct `Update` or the `update { }` DSL. Prefer the style already used by the feature. Use `only()`, `andCommand`, and `andCommands` for concise `UpdateResult` values.

Represent taps, text changes, command results, subscription emissions, and internal transitions as `Message`. A text field sends a message for every user edit.

Define `Command` as data. Use it both for finite external work and one-time UI instructions such as navigation or a transient snackbar. UI and `CommandExecutor` receive the same command and each ignores irrelevant variants.

Keep repositories, I/O, and platform operations inside `CommandExecutor`. It returns a cold `Flow<Message>` with zero, one, or multiple results. Expected failures become failure messages. Reserve `onCommandException` for unexpected failures; its default callback is empty and it does not log, retry, or create a message.

Put fallible work inside the returned flow. The runtime isolates non-cancellation exceptions raised while collecting each command flow. Synchronous exceptions thrown before the executor returns a flow are outside that boundary. Always rethrow `CancellationException`.

```kotlin
import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.andCommand
import io.github.chirillkirkin.mvu.only
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

data class ProfileState(
  val name: String = "",
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
)

sealed interface ProfileMessage {
  data object Refresh : ProfileMessage
  data class NameChanged(val name: String) : ProfileMessage
  data class ProfileLoaded(val name: String) : ProfileMessage
  data class LoadingFailed(val message: String) : ProfileMessage
  data object BackClicked : ProfileMessage
  data object ErrorAcknowledged : ProfileMessage
}

sealed interface ProfileCommand {
  data object LoadProfile : ProfileCommand
  data object NavigateBack : ProfileCommand
  data class ShowError(val message: String) : ProfileCommand
}

data class Profile(
  val name: String,
)

interface ProfileRepository {
  suspend fun loadProfile(): Profile
  fun observeProfile(name: String): Flow<Profile>
}

val profileUpdate: Update<ProfileMessage, ProfileState, ProfileCommand> =
  { message, state ->
    when (message) {
      ProfileMessage.Refresh ->
        state.copy(isLoading = true, errorMessage = null) andCommand
          ProfileCommand.LoadProfile
      is ProfileMessage.NameChanged -> state.copy(name = message.name).only()
      is ProfileMessage.ProfileLoaded ->
        state.copy(name = message.name, isLoading = false).only()
      is ProfileMessage.LoadingFailed ->
        state.copy(isLoading = false, errorMessage = message.message) andCommand
          ProfileCommand.ShowError(message.message)
      ProfileMessage.BackClicked -> state andCommand ProfileCommand.NavigateBack
      ProfileMessage.ErrorAcknowledged -> state.copy(errorMessage = null).only()
    }
  }

fun profileCommandExecutor(
  repository: ProfileRepository,
): CommandExecutor<ProfileCommand, ProfileMessage> = { command ->
  when (command) {
    ProfileCommand.LoadProfile -> flow {
      emit(ProfileMessage.ProfileLoaded(repository.loadProfile().name))
    }.catch { throwable ->
      if (throwable is CancellationException) {
        throw throwable
      }
      emit(ProfileMessage.LoadingFailed(throwable.message.orEmpty()))
    }
    ProfileCommand.NavigateBack,
    is ProfileCommand.ShowError,
    -> emptyFlow()
  }
}
```

The UI observes all three command variants and ignores `LoadProfile`. The executor observes the same variants and returns `emptyFlow()` for `NavigateBack` and `ShowError`.

Keep durable presentation in State. Here `errorMessage` makes the current error renderable; `ShowError` only requests a one-time snackbar. The application supplies `ProfileRepository` and its error-to-text mapping.

## Subscription

Use a finite command for work requested once. Use `Subscription` for an external stream whose activation or key follows State, such as a timer, connectivity state, or updates for the selected entity.

Derive the activation flag or key with `map` and `distinctUntilChanged()`, then switch the underlying source with `flatMapLatest`. Return the complete flow; do not launch an unmanaged ViewModel coroutine.

```kotlin
import io.github.chirillkirkin.mvu.Subscription
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

fun profileUpdates(
  repository: ProfileRepository,
): Subscription<ProfileState, ProfileMessage> =
  { states ->
    states
      .map { state -> state.name.takeIf(String::isNotBlank) }
      .distinctUntilChanged()
      .flatMapLatest { profileName ->
        if (profileName == null) {
          emptyFlow()
        } else {
          repository.observeProfile(profileName).map { profile ->
            ProfileMessage.ProfileLoaded(profile.name)
          }
        }
      }
  }
```

When the derived key changes, `flatMapLatest` cancels the obsolete stream before observing the new key.

## Runtime semantics

Messages are processed sequentially. Every update publishes its next State, then each Command is published to `commands` before being submitted for execution. Commands execute concurrently up to `concurrency`; enqueue order is preserved, completion order is not.

Every message emitted by a command or subscription returns to the same sequential update loop. Each returned command flow has its own failure boundary before flows are merged, so one collected command failure does not stop later commands. Do not throw from `onCommandException`.

`commands` is backed by `Channel.receiveAsFlow()`. It is single-consumer, not broadcast: multiple collectors divide commands. Collect it in exactly one UI location.

Constructor-inject one feature-specific Store per ViewModel instance and delegate the ViewModel's MVU contract to it. Call `launchIn(viewModelScope)` exactly once. Repeated launch duplicates runtime pipelines and initial commands. Clearing the ViewModel cancels the scope and therefore messages, commands, and subscriptions; no separate close API is required.

## ViewModel ownership

Dependency injection owns runtime construction and its lifetime. Define a feature-specific Store with an injectable constructor, scope it to one ViewModel instance, and inherit from `MVUStore`. The Store configures the feature runtime. The ViewModel delegates `MVU` to the injected Store, eliminating forwarding properties and methods. Do not mirror state into another `MutableStateFlow`.

With Hilt, annotate the feature Store with `@ViewModelScoped` and use constructor injection. A separate Hilt module is unnecessary when the concrete Store can be constructed directly:

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chirillkirkin.mvu.MVU
import io.github.chirillkirkin.mvu.MVUStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject

@ViewModelScoped
class ProfileStore @Inject constructor(
  repository: ProfileRepository,
) : MVUStore<ProfileMessage, ProfileState, ProfileCommand>(
    initialState = ProfileState(),
    update = profileUpdate,
    commandExecutor = profileCommandExecutor(repository),
    initialCommands = listOf(ProfileCommand.LoadProfile),
  )

@HiltViewModel
class ProfileViewModel @Inject constructor(
  store: ProfileStore,
) : ViewModel(), MVU<ProfileMessage, ProfileState, ProfileCommand> by store {
  init {
    launchIn(viewModelScope)
  }
}
```

Use the equivalent per-ViewModel scope when the project uses another DI framework. Do not start the Store lazily from a composable. The ViewModel owns the running coroutine lifetime, not the current composition, while DI owns construction.

Keep a small feature's State, Message, Command, Update, Store, and ViewModel together by default. Split them only when file size or distinct responsibilities make a separate file clearer.

## Compose Root and Screen

Keep Root and Screen in the same feature UI file unless it becomes genuinely unwieldy.

- `<Feature>Root` obtains the ViewModel, collects state and commands, and invokes navigation or platform callbacks.
- `<Feature>Screen` accepts only State and `onMessage`, renders state, and sends messages. It has no ViewModel reference and is independently previewable and testable.

Use `collectAsStateWithLifecycle()` for state. Collect commands once with `repeatOnLifecycle(STARTED)` from `LaunchedEffect`. Use `rememberUpdatedState` for callbacks that may change without restarting collection.

```kotlin
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun ProfileRoot(
  onNavigateBack: () -> Unit,
  showSnackbar: suspend (String) -> Unit,
  viewModel: ProfileViewModel,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentNavigateBack by rememberUpdatedState(onNavigateBack)
  val currentShowSnackbar by rememberUpdatedState(showSnackbar)

  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.commands.collect { command ->
        when (command) {
          ProfileCommand.NavigateBack -> currentNavigateBack()
          is ProfileCommand.ShowError -> {
            currentShowSnackbar(command.message)
            viewModel.send(ProfileMessage.ErrorAcknowledged)
          }
          ProfileCommand.LoadProfile -> Unit
        }
      }
    }
  }

  ProfileScreen(
    state = state,
    onMessage = viewModel::send,
  )
}

@Composable
fun ProfileScreen(
  state: ProfileState,
  onMessage: (ProfileMessage) -> Unit,
) {
  TextField(
    value = state.name,
    onValueChange = { name -> onMessage(ProfileMessage.NameChanged(name)) },
  )
  state.errorMessage?.let { message -> Text(text = message) }
}
```

Do not collect commands in both Root and a child. Commands pending in the same ViewModel lifetime can be consumed after lifecycle collection restarts, but commands do not survive process death and are not persisted state.

Keep business decisions and data transformations outside composables. Compose-owned objects such as `LazyListState` may use `remember`; application state belongs in MVU State.

## Saved state

Saved-state rules and the `SavedStateHandle.mvuStore` overloads are described in the `mvu` skill. On Android, inject `SavedStateHandle` into the ViewModel-scoped Store; Hilt provides it in the ViewModel component:

```kotlin
@ViewModelScoped
class EditorStore @Inject constructor(
  savedStateHandle: SavedStateHandle,
  repository: EditorRepository,
) : SavedStateMVU<EditorMessage, EditorState, EditorCommand> by savedStateHandle.mvuStore(
    initialState = EditorState(),
    update = editorUpdate,
    commandExecutor = editorCommandExecutor(repository),
    serializer = EditorState.serializer(),
    stateKey = EDITOR_STATE_KEY,
  )

@HiltViewModel
class EditorViewModel @Inject constructor(
  store: EditorStore,
) : ViewModel(), SavedStateMVU<EditorMessage, EditorState, EditorCommand> by store {
  init {
    launchIn(viewModelScope)
  }
}
```

Do not persist bitmaps or other Android objects that only make sense in the current process.

## Testing

Use `kotlin.test` and `kotlinx-coroutines-test`; Turbine, AssertK, and mocks are optional rather than architectural requirements.

- Invoke Update directly for pure transition tests; do not use `runTest` unnecessarily.
- Test message ordering, command feedback, concurrency, failure isolation, cancellation, initial commands, and subscriptions against `MVUStore` with `runTest`, `backgroundScope`, `runCurrent()`, and virtual time.
- Test a ViewModel only for Android ownership or wiring not already covered by store tests. Replace `Dispatchers.Main` with one test dispatcher when `viewModelScope` is involved and reset it afterwards.
- Test Screen as a pure composable by passing State and capturing Message values. Do not create its ViewModel in the Screen test.

Use constants for repeated state keys, identifiers, strings, and timing values in tests. Keep expected command failures inside the returned `flow { }` when testing the runtime's per-flow exception boundary.

## Checklist

- The feature uses one immutable State and exhaustive Message and Command types.
- Update is pure and is the only state-transition authority.
- Expected failures return messages; cancellation is preserved.
- Ongoing state-dependent work is a Subscription.
- DI constructor-injects one feature-specific Store per ViewModel instance; the ViewModel delegates its MVU contract to `store` and launches it once in `viewModelScope`.
- State is collected with lifecycle awareness and exactly one UI collector consumes commands.
- Root handles commands; Screen only renders State and sends Messages.
- Durable/restorable information is State, not Command.
- Saved-state support follows the `mvu` skill; the Store receives `SavedStateHandle` through DI.
- Tests use deterministic coroutine time where timing matters.
