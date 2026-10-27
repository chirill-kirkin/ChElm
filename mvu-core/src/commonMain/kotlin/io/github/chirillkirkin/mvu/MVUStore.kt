package io.github.chirillkirkin.mvu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Default [MVU] runtime.
 *
 * Messages are processed sequentially. [initialCommands] are executed on launch. Commands are
 * exposed to the UI before they are submitted to [commandExecutor], and command executions may run
 * concurrently up to [concurrency]. An unhandled command exception only stops that command and is
 * passed to [onCommandException]. By default, the exception is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
public open class MVUStore<Message, State, Command> public constructor(
  initialState: State,
  private val update: Update<Message, State, Command>,
  private val commandExecutor: CommandExecutor<Command, Message>,
  private val initialCommands: List<Command> = emptyList(),
  private val subscriptions: List<Subscription<State, Message>> = emptyList(),
  private val concurrency: Int = DEFAULT_CONCURRENCY,
  private val onCommandException: (command: Command, throwable: Throwable) -> Unit = { _, _ -> },
) : MVU<Message, State, Command> {
  private val mutableState: MutableStateFlow<State> = MutableStateFlow(initialState)
  override val state: StateFlow<State> = mutableState.asStateFlow()

  private val commandsToExecute: Channel<Command> = Channel(Channel.UNLIMITED)
  private val commandsForUi: Channel<Command> = Channel(Channel.UNLIMITED)
  override val commands: Flow<Command> = commandsForUi.receiveAsFlow()

  private val messages: Channel<Message> = Channel(Channel.UNLIMITED)

  override fun launchIn(scope: CoroutineScope) {
    messages
      .receiveAsFlow()
      .onEach(::processMessage)
      .launchIn(scope)

    commandsToExecute
      .receiveAsFlow()
      .flatMapMerge(concurrency = concurrency) { command ->
        commandExecutor(command)
          .catch { throwable ->
            if (throwable is CancellationException) {
              throw throwable
            }

            onCommandException(command, throwable)
          }
      }.onEach(::send)
      .launchIn(scope)

    subscriptions.forEach { subscription ->
      subscription(state)
        .onEach(::send)
        .launchIn(scope)
    }

    initialCommands.forEach(::sendCommand)
  }

  override fun send(message: Message) {
    messages.trySend(message)
  }

  private fun processMessage(message: Message) {
    val result = update(message, state.value)

    mutableState.value = result.state
    result.commands.forEach(::sendCommand)
  }

  private fun sendCommand(command: Command) {
    commandsForUi.trySend(command)
    commandsToExecute.trySend(command)
  }
}
