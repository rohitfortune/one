package com.rohit.one.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rohit.one.data.Note
import com.rohit.one.data.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val noteRepository: NoteRepository) : ViewModel() {

    val notes: StateFlow<List<Note>> = noteRepository.getAllNotes().map { it.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addNote(note: Note) { viewModelScope.launch { noteRepository.insertNote(note) } }

    fun updateNote(note: Note) { viewModelScope.launch { noteRepository.updateNote(note) } }

    fun deleteNote(note: Note) { viewModelScope.launch { noteRepository.deleteNote(note) } }

    fun restoreNoteFromBackup(title: String, content: String, attachments: List<Note.Attachment> = emptyList(), paths: List<Note.Path> = emptyList()) {
        viewModelScope.launch { noteRepository.upsertNoteFromBackup(title, content, attachments, paths) }
    }

    suspend fun findByTitleAndContent(title: String, content: String): Note? = noteRepository.findByTitleAndContent(title, content)

    companion object {
        fun provideFactory(repository: NoteRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NotesViewModel(repository) as T
            }
        }
    }
}