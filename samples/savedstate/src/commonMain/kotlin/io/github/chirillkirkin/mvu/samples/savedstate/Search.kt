package io.github.chirillkirkin.mvu.samples.savedstate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chirillkirkin.mvu.CommandExecutor
import io.github.chirillkirkin.mvu.Update
import io.github.chirillkirkin.mvu.andCommand
import io.github.chirillkirkin.mvu.only
import io.github.chirillkirkin.mvu.savedstate.SavedStateMVU
import io.github.chirillkirkin.mvu.savedstate.mvuStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

private const val SEARCH_QUERY_KEY = "search_query"
private val searchDelay = 1.seconds
private val cities = listOf("Amsterdam", "Berlin", "Lisbon", "London", "Madrid", "Paris", "Prague", "Rome")

data class SearchState(
  val query: String = "",
  val results: List<String> = emptyList(),
  val isSearching: Boolean = false,
)

sealed interface SearchMessage {
  data class QueryChanged(val query: String) : SearchMessage

  data object SearchClicked : SearchMessage

  data class ResultsLoaded(val results: List<String>) : SearchMessage
}

sealed interface SearchCommand {
  data class Search(val query: String) : SearchCommand
}

val searchUpdate: Update<SearchMessage, SearchState, SearchCommand> = { message, state ->
  when (message) {
    is SearchMessage.QueryChanged -> state.copy(query = message.query).only()
    SearchMessage.SearchClicked -> state.copy(isSearching = true) andCommand SearchCommand.Search(state.query)
    is SearchMessage.ResultsLoaded -> state.copy(results = message.results, isSearching = false).only()
  }
}

val searchCommandExecutor: CommandExecutor<SearchCommand, SearchMessage> = { command ->
  when (command) {
    is SearchCommand.Search -> flow {
      delay(searchDelay)
      emit(SearchMessage.ResultsLoaded(cities.filter { city -> city.contains(command.query, ignoreCase = true) }))
    }
  }
}

class SearchViewModel(savedStateHandle: SavedStateHandle) :
  ViewModel(),
  SavedStateMVU<SearchMessage, SearchState, SearchCommand> by savedStateHandle.mvuStore(
    initialState = SearchState(),
    update = searchUpdate,
    commandExecutor = searchCommandExecutor,
    saveState = SearchState::query,
    restoreState = { query, initialState -> initialState.copy(query = query, isSearching = query.isNotEmpty()) },
    stateKey = SEARCH_QUERY_KEY,
    initialCommands = { state, _ ->
      if (state.isSearching) listOf(SearchCommand.Search(state.query)) else emptyList()
    },
  ) {
  init {
    launchIn(viewModelScope)
  }
}
