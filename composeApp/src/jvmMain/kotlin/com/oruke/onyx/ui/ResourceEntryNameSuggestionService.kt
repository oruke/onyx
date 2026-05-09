package com.oruke.onyx.ui

import com.oruke.onyx.app.component.EntryNameSuggestionService
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import org.jetbrains.compose.resources.getString

class ResourceEntryNameSuggestionService : EntryNameSuggestionService {
    override suspend fun newFileName(): String = getString(Res.string.action_new_file)

    override suspend fun newDirectoryName(): String = getString(Res.string.action_new_directory)
}
