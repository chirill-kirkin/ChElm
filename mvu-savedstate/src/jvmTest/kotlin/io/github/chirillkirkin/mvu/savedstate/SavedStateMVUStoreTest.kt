package io.github.chirillkirkin.mvu.savedstate

import androidx.lifecycle.SavedStateHandle
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.only
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class SavedStateMVUStoreTest {
  @Test
  fun `full state is restored and reported`() {
    val restoredState = SerializableState(count = RESTORED_COUNT)
    val savedStateHandle = SavedStateHandle(
      mapOf(STATE_KEY to encodeToSavedState(SerializableState.serializer(), restoredState)),
    )
    var initialCommandsReceivedState: SerializableState? = null
    var initialCommandsReceivedRestoredState: Boolean? = null

    val store = savedStateHandle.mvuStore(
      initialState = SerializableState(count = FRESH_COUNT),
      update = serializableUpdate,
      commandExecutor = emptyCommandExecutor,
      serializer = SerializableState.serializer(),
      stateKey = STATE_KEY,
      initialCommands = { state, isStateRestored ->
        initialCommandsReceivedState = state
        initialCommandsReceivedRestoredState = isStateRestored
        emptyList()
      },
    )

    assertEquals(restoredState, store.state.value)
    assertTrue(store.isStateRestored)
    assertEquals(restoredState, initialCommandsReceivedState)
    assertEquals(true, initialCommandsReceivedRestoredState)
  }

  @Test
  fun `initial state is used when saved state is absent`() {
    val initialState = SerializableState(count = INITIAL_COUNT)
    var initialCommandsReceivedState: SerializableState? = null
    var initialCommandsReceivedRestoredState: Boolean? = null

    val store = SavedStateHandle().mvuStore(
      initialState = initialState,
      update = serializableUpdate,
      commandExecutor = emptyCommandExecutor,
      serializer = SerializableState.serializer(),
      stateKey = STATE_KEY,
      initialCommands = { state, isStateRestored ->
        initialCommandsReceivedState = state
        initialCommandsReceivedRestoredState = isStateRestored
        emptyList()
      },
    )

    assertEquals(initialState, store.state.value)
    assertFalse(store.isStateRestored)
    assertEquals(initialState, initialCommandsReceivedState)
    assertEquals(false, initialCommandsReceivedRestoredState)
  }

  @Test
  fun `initial and updated full state are saved`() = runTest {
    val savedStateHandle = SavedStateHandle()
    val store = savedStateHandle.mvuStore(
      initialState = SerializableState(count = INITIAL_COUNT),
      update = serializableUpdate,
      commandExecutor = emptyCommandExecutor,
      serializer = SerializableState.serializer(),
      stateKey = STATE_KEY,
    )

    store.launchIn(backgroundScope)
    runCurrent()
    assertEquals(SerializableState(count = INITIAL_COUNT), savedStateHandle.recreated().decodeState())

    store.send(Message.Increment)
    runCurrent()

    assertEquals(SerializableState(count = UPDATED_COUNT), store.state.value)
    assertEquals(SerializableState(count = UPDATED_COUNT), savedStateHandle.recreated().decodeState())
  }

  @Test
  fun `state projection is restored and saved`() = runTest {
    val savedStateHandle = SavedStateHandle(mapOf(STATE_KEY to RESTORED_COUNT))
    val update: Update<Message, ProjectedState, Command> = { message, state ->
      when (message) {
        Message.Increment ->
          state
            .copy(
              count = state.count + INCREMENT,
              transientValue = UPDATED_TRANSIENT_VALUE,
            ).only()
      }
    }
    var initialCommandsReceivedState: ProjectedState? = null
    val store = savedStateHandle.mvuStore(
      initialState = ProjectedState(
        count = FRESH_COUNT,
        transientValue = FRESH_TRANSIENT_VALUE,
      ),
      update = update,
      commandExecutor = emptyCommandExecutor,
      saveState = ProjectedState::count,
      restoreState = { savedCount, initialState -> initialState.copy(count = savedCount) },
      stateKey = STATE_KEY,
      initialCommands = { state, _ ->
        initialCommandsReceivedState = state
        emptyList()
      },
    )
    val restoredState = ProjectedState(count = RESTORED_COUNT, transientValue = FRESH_TRANSIENT_VALUE)

    assertEquals(restoredState, store.state.value)
    assertTrue(store.isStateRestored)
    assertEquals(restoredState, initialCommandsReceivedState)

    store.launchIn(backgroundScope)
    store.send(Message.Increment)
    runCurrent()

    assertEquals(
      ProjectedState(count = RESTORED_COUNT + INCREMENT, transientValue = UPDATED_TRANSIENT_VALUE),
      store.state.value,
    )
    assertEquals(RESTORED_COUNT + INCREMENT, savedStateHandle[STATE_KEY])
  }

  private fun SavedStateHandle.recreated(): SavedStateHandle =
    SavedStateHandle.createHandle(restoredState = savedStateProvider().saveState(), defaultState = null)

  private fun SavedStateHandle.decodeState(): SerializableState {
    val savedState = assertNotNull(get<SavedState>(STATE_KEY))
    return decodeFromSavedState(SerializableState.serializer(), savedState)
  }

  @Serializable
  private data class SerializableState(val count: Int)

  private data class ProjectedState(val count: Int, val transientValue: String)

  private sealed interface Message {
    data object Increment : Message
  }

  private sealed interface Command

  private companion object {
    const val STATE_KEY = "test_state"
    const val FRESH_COUNT = 0
    const val INITIAL_COUNT = 2
    const val RESTORED_COUNT = 7
    const val INCREMENT = 1
    const val UPDATED_COUNT = INITIAL_COUNT + INCREMENT
    const val FRESH_TRANSIENT_VALUE = "fresh"
    const val UPDATED_TRANSIENT_VALUE = "changed"

    val serializableUpdate: Update<Message, SerializableState, Command> = { message, state ->
      when (message) {
        Message.Increment -> state.copy(count = state.count + INCREMENT).only()
      }
    }

    val emptyCommandExecutor: CommandExecutor<Command, Message> = { emptyFlow() }
  }
}
