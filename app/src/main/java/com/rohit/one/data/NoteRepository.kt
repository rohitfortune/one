package com.rohit.one.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(
    private val noteDao: NoteDao
) {

    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()

    suspend fun insertNote(note: Note): Long = noteDao.insertNote(note)

    suspend fun updateNote(note: Note) { noteDao.updateNote(note) }

    suspend fun deleteNote(note: Note) { noteDao.deleteNote(note) }

    suspend fun upsertNoteFromBackup(title: String, content: String, attachments: List<Note.Attachment> = emptyList(), paths: List<Note.Path> = emptyList()) {
        val existing = noteDao.findByTitleAndContent(title, content)
        if (existing == null) {
            noteDao.insertNote(Note(title = title, content = content, attachments = attachments, paths = paths))
        } else if (attachments.isNotEmpty() || paths.isNotEmpty()) {
            val newAttachments = if (attachments.isNotEmpty()) attachments else existing.attachments
            val newPaths = if (paths.isNotEmpty()) paths else existing.paths
            noteDao.updateNote(existing.copy(attachments = newAttachments, paths = newPaths))
        }
    }

    suspend fun findByTitleAndContent(title: String, content: String): Note? = noteDao.findByTitleAndContent(title, content)
}