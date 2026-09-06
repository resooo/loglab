package com.loglab.app.core.bridge

import kotlinx.serialization.Serializable

@Serializable
data class ShellRequest(val command: String)

@Serializable
data class ShellResponse(
    val success: Boolean = false,
    val stdout: String = "",
    val stderr: String = ""
)

@Serializable
data class HealthResponse(
    val status: String = "",
    val shellSupported: Boolean = false
)
