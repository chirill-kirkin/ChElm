package io.github.chirillkirkin.mvu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
internal class MVUStoreTest {
  @Test
  fun `messages are processed sequentially`() = runTest {
    val store = createStore()
    store.launchIn(backgroundScope)

    store.send(Message.Record(1))
    store.send(Message.Record(2))
    store.send(Message.Record(3))
    runCurrent()

    assertEquals(listOf(1, 2, 3), store.state.value.recordedValues)
  }

  @Test
  fun `commands are published and executor messages return to update`() = runTest {
    val observedCommands = mutableListOf<Command>()
    val store = createStore(
      commandExecutor = { command -> flowOf(Message.Completed(command.id)) },
    )

    backgroundScope.launch {
      store.commands.collect(observedCommands::add)
    }
    store.launchIn(backgroundScope)

    store.send(Message.StartWork(listOf(1, 2)))
    runCurrent()

    val expectedCommands: List<Command> = listOf(Command.Work(1), Command.Work(2))
    assertEquals(expectedCommands, observedCommands)
    assertEquals(2, store.state.value.workCompletedCount)
  }

  @Test
  fun `initial commands are published and executed`() = runTest {
    val observedCommands = mutableListOf<Command>()
    val store = createStore(
      initialCommands = listOf(Command.Work(7)),
      commandExecutor = { command -> flowOf(Message.Completed(command.id)) },
    )

    backgroundScope.launch {
      store.commands.collect(observedCommands::add)
    }
    store.launchIn(backgroundScope)
    runCurrent()

    val expectedCommands: List<Command> = listOf(Command.Work(7))
    assertEquals(expectedCommands, observedCommands)
    assertEquals(1, store.state.value.workCompletedCount)
  }

  @Test
  fun `executor failure does not stop subsequent commands`() = runTest {
    val store = createStore(
      commandExecutor = commandExecutorWithFailure(ExpectedCommandException()),
    )

    store.launchIn(backgroundScope)
    store.send(Message.StartWork(listOf(FAILING_COMMAND_ID)))
    runCurrent()

    store.send(Message.StartWork(listOf(SUCCESSFUL_COMMAND_ID)))
    runCurrent()

    assertEquals(EXPECTED_COMPLETED_WORK_COUNT, store.state.value.workCompletedCount)
  }

  @Test
  fun `executor failure is passed to exception callback`() = runTest {
    val expectedException = ExpectedCommandException()
    var failedCommand: Command? = null
    var commandException: Throwable? = null
    val store = createStore(
      commandExecutor = commandExecutorWithFailure(expectedException),
      onCommandException = { command, throwable ->
        failedCommand = command
        commandException = throwable
      },
    )

    store.launchIn(backgroundScope)
    store.send(Message.StartWork(listOf(FAILING_COMMAND_ID)))
    runCurrent()

    assertEquals(Command.Work(FAILING_COMMAND_ID), failedCommand)
    assertSame(expectedException, commandException)
  }

  @Test
  fun `cancelling owner scope stops message processing`() = runTest {
    val ownerJob = SupervisorJob()
    val ownerScope = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
    val store = createStore()
    store.launchIn(ownerScope)

    store.send(Message.Record(1))
    runCurrent()
    assertEquals(listOf(1), store.state.value.recordedValues)

    ownerScope.cancel()
    store.send(Message.Record(2))
    runCurrent()

    assertEquals(listOf(1), store.state.value.recordedValues)
  }

  private fun createStore(
    initialCommands: List<Command> = emptyList(),
    commandExecutor: CommandExecutor<Command, Message> = { emptyFlow() },
    onCommandException: (command: Command, throwable: Throwable) -> Unit = { _, _ -> },
  ): MVUStore<Message, State, Command> = MVUStore(
    initialState = State(),
    update = { message, state ->
      when (message) {
        is Message.Record ->
          state.copy(recordedValues = state.recordedValues + message.value).only()

        is Message.StartWork -> state.andCommands(message.ids.map(Command::Work))

        is Message.Completed -> state.copy(workCompletedCount = state.workCompletedCount + 1).only()
      }
    },
    commandExecutor = commandExecutor,
    initialCommands = initialCommands,
    onCommandException = onCommandException,
  )

  private fun commandExecutorWithFailure(
    expectedException: ExpectedCommandException,
  ): CommandExecutor<Command, Message> = { command ->
    flow {
      if (command.id == FAILING_COMMAND_ID) {
        throw expectedException
      }

      emit(Message.Completed(command.id))
    }
  }

  private data class State(val recordedValues: List<Int> = emptyList(), val workCompletedCount: Int = 0)

  private sealed interface Message {
    data class Record(val value: Int) : Message

    data class StartWork(val ids: List<Int>) : Message

    data class Completed(val id: Int) : Message
  }

  private sealed interface Command {
    val id: Int

    data class Work(override val id: Int) : Command
  }

  private class ExpectedCommandException : RuntimeException()

  private companion object {
    const val FAILING_COMMAND_ID = 1
    const val SUCCESSFUL_COMMAND_ID = 2
    const val EXPECTED_COMPLETED_WORK_COUNT = 1
  }
}
