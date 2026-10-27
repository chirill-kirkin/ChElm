package io.github.chirillkirkin.mvu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Pure function that turns a message and the current state into the next state and commands. */
public typealias Update<Message, State, Command> =
  (message: Message, state: State) -> UpdateResult<State, Command>

/** Executes a command and emits the messages produced by that execution. */
public typealias CommandExecutor<Command, Message> = (command: Command) -> Flow<Message>

/** Long-lived source of messages driven by the state. */
public typealias Subscription<State, Message> = (state: StateFlow<State>) -> Flow<Message>

/**
 * A Model-View-Update feature.
 *
 * @param Message an event the feature reacts to.
 * @param State the state of the feature.
 * @param Command a side effect to execute.
 */
public interface MVU<Message, State, Command> {
  /** The current state of the feature. */
  public val state: StateFlow<State>

  /** Commands produced by updates, for the UI to observe. */
  public val commands: Flow<Command>

  /** Queues a message for the update loop. */
  public fun send(message: Message)

  /** Starts the feature in [scope]. */
  public fun launchIn(scope: CoroutineScope)
}
