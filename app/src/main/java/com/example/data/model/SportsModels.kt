package com.example.data.model

import java.util.UUID

data class SportsChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // e.g. "TNT Sports", "BeIN Sports", "Sky Cricket"
    val category: String = "Sports",
    val logoText: String = "", // Short badge text e.g. "TNT", "TSN"
    val logoUrl: String = "",
    val streams: List<StreamSource> = emptyList(),
    val isFavorite: Boolean = false
)

data class CategoryItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String, // e.g. "SPORTS", "FANCODE IND", "SONYLIV LIVE"
    val channelCount: Int = 12,
    val iconName: String = "sports"
)

data class AppConfig(
    val appTitle: String = "Football Live Tv",
    val version: String = "7.3",
    val announcement: String = "Welcome to Football Live TV! Multiple high-definition stream servers are active for today's matches.",
    val telegramLink: String = "https://t.me/sports_live_streams",
    val adminPin: String = "1234",
    val forceLowQuality: Boolean = false,
    val isHardwareAccelerationEnabled: Boolean = true
)

data class M3U8CheckResult(
    val url: String,
    val isOnline: Boolean,
    val httpStatusCode: Int = 0,
    val latencyMs: Long = 0,
    val contentType: String = "",
    val isHlsStream: Boolean = false,
    val message: String = ""
)
