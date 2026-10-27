package io.github.chirillkirkin.mvu

import kotlin.test.Test
import kotlin.test.assertEquals

internal class UpdateTest {
  @Test
  fun `update DSL transforms state and preserves command order`() {
    val update: Update<Message, State, Command> = update { message, currentState ->
      when (message) {
        Message.Increment -> {
          state { copy(value = value + 1) }
          commands(Command.First, Command.Second)
        }

        Message.Replace -> state(currentState.copy(value = 42))
      }
    }

    val result = update(Message.Increment, State(value = 1))
    val replacement = update(Message.Replace, State(value = 1))

    assertEquals(State(value = 2), result.state)
    assertEquals(listOf(Command.First, Command.Second), result.commands)
    assertEquals(State(value = 42), replacement.state)
    assertEquals(emptyList(), replacement.commands)
  }

  private data class State(val value: Int)

  private sealed interface Message {
    data object Increment : Message

    data object Replace : Message
  }

  private sealed interface Command {
    data object First : Command

    data object Second : Command
  }
}
