package com.rocs.demo

data class ServerOption(
    val id: String,
    val label: String,
    val url: String,
)

object Config {
    val servers = listOf(
        ServerOption("local", "Local (localhost:8004)", "http://localhost:8004"),
        ServerOption("custom", "Custom server", ""),
    )

    const val DEFAULT_SERVER_ID = "local"

    const val APP_KEY = "replace-with-a-test-app-key"

    const val USER_ID = "demo-user"

    const val DEFAULT_SYSTEM_PROMPT =
        "You are a helpful AI assistant. Keep responses concise and conversational."
}
