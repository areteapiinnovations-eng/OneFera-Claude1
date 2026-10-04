package com.onefera.app.feature.story

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Story IDs viewed during this session, so the stories row can dim rings that were already watched. */
@Singleton
class SeenStories @Inject constructor() {
    private val _seen = MutableStateFlow<Set<String>>(emptySet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    fun markSeen(storyId: String) = _seen.update { it + storyId }
}
