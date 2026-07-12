package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TransferConflictStrategy

internal fun RootComponent.setLayoutMode(mode: PaneLayoutMode) = dispatch(RootIntent.SetLayoutMode(mode))

internal fun RootComponent.setPaneSplitFraction(fraction: Float) = dispatch(RootIntent.SetPaneSplitFraction(fraction))

internal fun RootComponent.openSettings() = dispatch(RootIntent.OpenSettings)

internal fun RootComponent.updateSettingsDraft(draft: OnyxSettings) = dispatch(RootIntent.UpdateSettingsDraft(draft))

internal fun RootComponent.openRemoteConnections() = dispatch(RootIntent.OpenRemoteConnections)

internal fun RootComponent.updateRemoteConnectionDraft(draft: RemoteConnectionDraft) =
    dispatch(RootIntent.UpdateRemoteConnectionDraft(draft))

internal fun RootComponent.editRemoteConnection(profile: RemoteConnectionProfile) =
    dispatch(RootIntent.EditRemoteConnection(profile))

internal fun RootComponent.newRemoteConnection() = dispatch(RootIntent.NewRemoteConnection)

internal fun RootComponent.saveRemoteConnectionDraft() = dispatch(RootIntent.SaveRemoteConnectionDraft)

internal fun RootComponent.testRemoteConnectionDraft() = dispatch(RootIntent.TestRemoteConnectionDraft)

internal fun RootComponent.deleteRemoteConnection(id: String) = dispatch(RootIntent.DeleteRemoteConnection(id))

internal fun RootComponent.openRemoteConnection(location: String) = dispatch(RootIntent.OpenRemoteConnection(location))

internal fun RootComponent.activatePane(paneId: PaneId) = dispatch(RootIntent.ActivatePane(paneId))

internal fun RootComponent.updateSettings(settings: OnyxSettings) = dispatch(RootIntent.UpdateSettings(settings))

internal fun RootComponent.openLocationInActivePane(location: String) =
    dispatch(RootIntent.OpenLocationInActivePane(location))

internal fun RootComponent.toggleFavoriteLocation(location: String) =
    dispatch(RootIntent.ToggleFavoriteLocation(location))

internal fun RootComponent.toggleSidebarTreeNode(location: String) =
    dispatch(RootIntent.ToggleSidebarTreeNode(location))

internal fun RootComponent.retrySidebarTreeNode(location: String) = dispatch(RootIntent.RetrySidebarTreeNode(location))

internal fun RootComponent.beginCreateDirectoriesInPane(paneId: PaneId) =
    dispatch(RootIntent.BeginCreateDirectoriesInPane(paneId))

internal fun RootComponent.updateCreateDirectoriesDraft(draft: String) =
    dispatch(RootIntent.UpdateCreateDirectoriesDraft(draft))

internal fun RootComponent.confirmDialog() = dispatch(RootIntent.ConfirmDialog)

internal fun RootComponent.dismissDialog() = dispatch(RootIntent.DismissDialog)

internal fun RootComponent.resolveConflict(strategy: TransferConflictStrategy, applyToAll: Boolean) =
    dispatch(RootIntent.ResolveConflict(strategy, applyToAll))

internal fun RootComponent.moveTab(
    sourcePaneId: PaneId,
    tabId: String,
    targetPaneId: PaneId,
    targetIndex: Int,
) = dispatch(RootIntent.MoveTab(sourcePaneId, tabId, targetPaneId, targetIndex))

internal fun RootComponent.refreshActivePane() = dispatch(RootIntent.RefreshActivePane)

internal fun RootComponent.togglePreviewPane() = dispatch(RootIntent.TogglePreviewPane)

internal fun RootComponent.showSearchPanel() = dispatch(RootIntent.ShowSearchPanel)

internal fun RootComponent.closeSearchPanel() = dispatch(RootIntent.CloseSearchPanel)

internal fun RootComponent.updateSearchQuery(query: String) = dispatch(RootIntent.UpdateSearchQuery(query))

internal fun RootComponent.executeSearch() = dispatch(RootIntent.ExecuteSearch)

internal fun RootComponent.cancelSearch() = dispatch(RootIntent.CancelSearch)

internal fun RootComponent.openSearchResult(entry: VFile) = dispatch(RootIntent.OpenSearchResult(entry))

internal fun RootComponent.openSearchResultsAsCollection() = dispatch(RootIntent.OpenSearchResultsAsCollection)
