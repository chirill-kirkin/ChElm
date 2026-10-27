package io.github.chirillkirkin.mvu

/** The next state and commands produced by an [Update]. */
public data class UpdateResult<State, Command> public constructor(
  public val state: State,
  public val commands: List<Command>,
)

/**
 * Creates an [Update] using a small imperative-style DSL while keeping the resulting update pure.
 */
public fun <Message, State, Command> update(
  block: UpdateDsl<State, Command>.(message: Message, state: State) -> Unit,
): Update<Message, State, Command> = { message, state ->
  UpdateDsl<State, Command>()
    .apply { block(message, state) }
    .getResult(state)
}

/** Receiver used by [update] to describe a state change and commands. */
public class UpdateDsl<State, Command> {
  private var stateUpdate: State.() -> State = { this }
  private val commands: MutableList<Command> = mutableListOf()

  /** Sets the transformation of the current state. A later call replaces the previous one. */
  public fun state(update: State.() -> State) {
    stateUpdate = update
  }

  /** Sets the next state. */
  public fun state(newState: State) {
    state { newState }
  }

  /** Adds a command. */
  public fun command(command: Command) {
    commands.add(command)
  }

  /** Adds commands in order. */
  public fun commands(vararg commands: Command) {
    commands(commands.toList())
  }

  /** Adds commands in order. */
  public fun commands(commands: List<Command>) {
    this.commands.addAll(commands)
  }

  internal fun getResult(initialState: State): UpdateResult<State, Command> = UpdateResult(
    state = stateUpdate(initialState),
    commands = commands.toList(),
  )
}

/** Returns an update result containing this state and no commands. */
public fun <State, Command> State.only(): UpdateResult<State, Command> =
  UpdateResult(state = this, commands = emptyList())

/** Returns an update result containing this state and the supplied commands. */
public fun <State, Command> State.andCommands(vararg commands: Command): UpdateResult<State, Command> =
  UpdateResult(state = this, commands = commands.toList())

/** Returns an update result containing this state and one command. */
public infix fun <State, Command> State.andCommand(command: Command): UpdateResult<State, Command> =
  UpdateResult(state = this, commands = listOf(command))

/** Returns an update result containing this state and the supplied commands. */
public infix fun <State, Command> State.andCommands(commands: List<Command>): UpdateResult<State, Command> =
  UpdateResult(state = this, commands = commands)
