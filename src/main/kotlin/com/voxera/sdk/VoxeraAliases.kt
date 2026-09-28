@file:Suppress("unused")

package com.voxera.sdk

/**
 * Voxera public API facade.
 *
 * The implementation remains in `com.rocs.sdk` for one compatibility cycle so
 * existing applications can upgrade the artifact without changing all imports
 * at once. New applications should import from `com.voxera.sdk`.
 */
typealias VoxeraClient = com.rocs.sdk.RocsClient
typealias VoxeraConfig = com.rocs.sdk.RocsConfig
typealias VoxeraListener = com.rocs.sdk.RocsListener
typealias VoxeraException = com.rocs.sdk.RocsException
typealias VoxeraErrorCode = com.rocs.sdk.RocsErrorCode

typealias ChatConfig = com.rocs.sdk.ChatConfig
typealias VoiceConfig = com.rocs.sdk.VoiceConfig
typealias VideoConfig = com.rocs.sdk.VideoConfig
typealias ConnectionOptions = com.rocs.sdk.ConnectionOptions
typealias ConnectionStatus = com.rocs.sdk.ConnectionStatus
typealias ConversationStatus = com.rocs.sdk.ConversationStatus
typealias SpeakingStatus = com.rocs.sdk.SpeakingStatus
typealias ToolCallFunction = com.rocs.sdk.ToolCallFunction
typealias ToolCall = com.rocs.sdk.ToolCall
typealias ConversationMessage = com.rocs.sdk.ConversationMessage
typealias WebRTCStats = com.rocs.sdk.WebRTCStats
typealias RoomMode = com.rocs.sdk.RoomMode
typealias RoomParticipant = com.rocs.sdk.RoomParticipant
typealias TranscriptionEntry = com.rocs.sdk.TranscriptionEntry
typealias WaitingRoomEntry = com.rocs.sdk.WaitingRoomEntry
typealias MeetingBookmark = com.rocs.sdk.MeetingBookmark
typealias MeetingSummary = com.rocs.sdk.MeetingSummary
typealias MeetingMinutesActionItem = com.rocs.sdk.MeetingMinutesActionItem
typealias MeetingMinutesSection = com.rocs.sdk.MeetingMinutesSection
typealias MeetingMinutes = com.rocs.sdk.MeetingMinutes
typealias MeetingCallbacks = com.rocs.sdk.MeetingCallbacks
