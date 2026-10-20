package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SportsStreamRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("football_live_tv_prefs", Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _matches = MutableStateFlow<List<LiveMatch>>(emptyList())
    val matches: StateFlow<List<LiveMatch>> = _matches.asStateFlow()

    private val _channels = MutableStateFlow<List<SportsChannel>>(emptyList())
    val channels: StateFlow<List<SportsChannel>> = _channels.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryItem>>(emptyList())
    val categories: StateFlow<List<CategoryItem>> = _categories.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val savedConfigJson = prefs.getString("app_config", null)
        if (savedConfigJson != null) {
            try {
                val obj = JSONObject(savedConfigJson)
                _config.value = AppConfig(
                    appTitle = obj.optString("appTitle", "Football Live Tv"),
                    version = obj.optString("version", "7.3"),
                    announcement = obj.optString("announcement", "Welcome to Football Live TV! Multiple high-definition stream servers are active."),
                    telegramLink = obj.optString("telegramLink", "https://t.me/football_live_tv"),
                    adminPin = obj.optString("adminPin", "1234"),
                    forceLowQuality = obj.optBoolean("forceLowQuality", false),
                    isHardwareAccelerationEnabled = obj.optBoolean("isHardwareAccelerationEnabled", true)
                )
            } catch (e: Exception) {
                _config.value = AppConfig()
            }
        }

        val savedMatchesJson = prefs.getString("saved_matches", null)
        if (savedMatchesJson != null) {
            try {
                val list = deserializeMatches(savedMatchesJson)
                if (list.isNotEmpty()) {
                    _matches.value = list
                } else {
                    _matches.value = defaultMatches()
                }
            } catch (e: Exception) {
                _matches.value = defaultMatches()
            }
        } else {
            _matches.value = defaultMatches()
        }

        val savedChannelsJson = prefs.getString("saved_channels", null)
        if (savedChannelsJson != null) {
            try {
                val list = deserializeChannels(savedChannelsJson)
                if (list.isNotEmpty()) {
                    _channels.value = list
                } else {
                    _channels.value = defaultChannels()
                }
            } catch (e: Exception) {
                _channels.value = defaultChannels()
            }
        } else {
            _channels.value = defaultChannels()
        }

        _categories.value = defaultCategories()
    }

    private fun saveData() {
        try {
            val matchesJson = serializeMatches(_matches.value)
            val channelsJson = serializeChannels(_channels.value)
            val configObj = JSONObject().apply {
                put("appTitle", _config.value.appTitle)
                put("version", _config.value.version)
                put("announcement", _config.value.announcement)
                put("telegramLink", _config.value.telegramLink)
                put("adminPin", _config.value.adminPin)
                put("forceLowQuality", _config.value.forceLowQuality)
                put("isHardwareAccelerationEnabled", _config.value.isHardwareAccelerationEnabled)
            }
            prefs.edit()
                .putString("saved_matches", matchesJson)
                .putString("saved_channels", channelsJson)
                .putString("app_config", configObj.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Match Operations
    fun addOrUpdateMatch(match: LiveMatch) {
        val current = _matches.value.toMutableList()
        val index = current.indexOfFirst { it.id == match.id }
        if (index >= 0) {
            current[index] = match
        } else {
            current.add(0, match)
        }
        _matches.value = current
        saveData()
    }

    fun deleteMatch(matchId: String) {
        _matches.value = _matches.value.filter { it.id != matchId }
        saveData()
    }

    fun toggleMatchFavorite(matchId: String) {
        _matches.value = _matches.value.map {
            if (it.id == matchId) it.copy(isFavorite = !it.isFavorite) else it
        }
        saveData()
    }

    // Channel Operations
    fun addOrUpdateChannel(channel: SportsChannel) {
        val current = _channels.value.toMutableList()
        val index = current.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            current[index] = channel
        } else {
            current.add(0, channel)
        }
        _channels.value = current
        saveData()
    }

    fun deleteChannel(channelId: String) {
        _channels.value = _channels.value.filter { it.id != channelId }
        saveData()
    }

    fun toggleChannelFavorite(channelId: String) {
        _channels.value = _channels.value.map {
            if (it.id == channelId) it.copy(isFavorite = !it.isFavorite) else it
        }
        saveData()
    }

    fun updateConfig(newConfig: AppConfig) {
        _config.value = newConfig
        saveData()
    }

    fun resetToDefaults() {
        _matches.value = defaultMatches()
        _channels.value = defaultChannels()
        _categories.value = defaultCategories()
        _config.value = AppConfig()
        saveData()
    }

    // Real M3U8 & Stream Health Checker using OkHttp
    suspend fun checkM3U8Stream(url: String): M3U8CheckResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext M3U8CheckResult(
                url = url,
                isOnline = false,
                message = "URL cannot be empty"
            )
        }
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 FootballLiveTV/7.3")
                .header("Accept", "*/*")
                .build()

            val response = httpClient.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val statusCode = response.code
            val contentType = response.header("Content-Type") ?: "unknown"
            val isSuccess = response.isSuccessful

            // Check if M3U8 manifest
            var isHls = false
            if (contentType.contains("mpegurl", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)) {
                isHls = true
            }

            // Quick peek at the first 512 bytes for #EXTM3U
            val peek = response.peekBody(512).string()
            if (peek.contains("#EXTM3U")) {
                isHls = true
            }
            response.close()

            M3U8CheckResult(
                url = url,
                isOnline = isSuccess,
                httpStatusCode = statusCode,
                latencyMs = latency,
                contentType = contentType,
                isHlsStream = isHls,
                message = if (isSuccess) "Active (${latency}ms) - $contentType" else "HTTP Error $statusCode"
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            M3U8CheckResult(
                url = url,
                isOnline = false,
                httpStatusCode = 0,
                latencyMs = latency,
                message = "Connection failed: ${e.localizedMessage ?: "Timeout"}"
            )
        }
    }

    // Serialization Helpers
    private fun serializeMatches(matches: List<LiveMatch>): String {
        val array = JSONArray()
        for (m in matches) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("tournament", m.tournament)
                put("sport", m.sport.name)
                put("team1Name", m.team1Name)
                put("team1Code", m.team1Code)
                put("team1FlagUrl", m.team1FlagUrl)
                put("team2Name", m.team2Name)
                put("team2Code", m.team2Code)
                put("team2FlagUrl", m.team2FlagUrl)
                put("status", m.status.name)
                put("timeOrScore", m.timeOrScore)
                put("matchTime", m.matchTime)
                put("isFavorite", m.isFavorite)

                val streamArr = JSONArray()
                for (s in m.streams) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("quality", s.quality)
                        put("url", s.url)
                        put("isDefault", s.isDefault)
                    }
                    streamArr.put(sObj)
                }
                put("streams", streamArr)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeMatches(json: String): List<LiveMatch> {
        val list = mutableListOf<LiveMatch>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val streamList = mutableListOf<StreamSource>()
            val streamArr = obj.optJSONArray("streams")
            if (streamArr != null) {
                for (j in 0 until streamArr.length()) {
                    val sObj = streamArr.getJSONObject(j)
                    streamList.add(
                        StreamSource(
                            id = sObj.optString("id"),
                            name = sObj.optString("name", "Stream ${j + 1}"),
                            quality = sObj.optString("quality", "FHD"),
                            url = sObj.optString("url"),
                            isDefault = sObj.optBoolean("isDefault", j == 0)
                        )
                    )
                }
            }
            val sportStr = obj.optString("sport", "FOOTBALL")
            val sportType = try { SportType.valueOf(sportStr) } catch (_: Exception) { SportType.FOOTBALL }
            val statusStr = obj.optString("status", "LIVE")
            val status = try { MatchStatus.valueOf(statusStr) } catch (_: Exception) { MatchStatus.LIVE }

            list.add(
                LiveMatch(
                    id = obj.optString("id"),
                    tournament = obj.optString("tournament"),
                    sport = sportType,
                    team1Name = obj.optString("team1Name"),
                    team1Code = obj.optString("team1Code"),
                    team1FlagUrl = obj.optString("team1FlagUrl"),
                    team2Name = obj.optString("team2Name"),
                    team2Code = obj.optString("team2Code"),
                    team2FlagUrl = obj.optString("team2FlagUrl"),
                    status = status,
                    timeOrScore = obj.optString("timeOrScore"),
                    matchTime = obj.optString("matchTime"),
                    streams = streamList,
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
            )
        }
        return list
    }

    private fun serializeChannels(channels: List<SportsChannel>): String {
        val array = JSONArray()
        for (c in channels) {
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("category", c.category)
                put("logoText", c.logoText)
                put("logoUrl", c.logoUrl)
                put("isFavorite", c.isFavorite)

                val streamArr = JSONArray()
                for (s in c.streams) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("quality", s.quality)
                        put("url", s.url)
                        put("isDefault", s.isDefault)
                    }
                    streamArr.put(sObj)
                }
                put("streams", streamArr)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeChannels(json: String): List<SportsChannel> {
        val list = mutableListOf<SportsChannel>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val streamList = mutableListOf<StreamSource>()
            val streamArr = obj.optJSONArray("streams")
            if (streamArr != null) {
                for (j in 0 until streamArr.length()) {
                    val sObj = streamArr.getJSONObject(j)
                    streamList.add(
                        StreamSource(
                            id = sObj.optString("id"),
                            name = sObj.optString("name", "Stream ${j + 1}"),
                            quality = sObj.optString("quality", "FHD"),
                            url = sObj.optString("url"),
                            isDefault = sObj.optBoolean("isDefault", j == 0)
                        )
                    )
                }
            }
            list.add(
                SportsChannel(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    category = obj.optString("category", "Sports"),
                    logoText = obj.optString("logoText"),
                    logoUrl = obj.optString("logoUrl"),
                    streams = streamList,
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
            )
        }
        return list
    }

    // Default Seed Data matching user screenshots exactly
    private fun defaultMatches(): List<LiveMatch> {
        val akamaiHls1 = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"
        val akamaiHls2 = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val akamaiHls3 = "https://diceyk6a7voy4.cloudfront.net/e78752a1-2e83-43fa-85ae-3d508be29366/hls/de:02b6989d-e714-4227-9c57-a4c1a221a244:playlist.m3u8"
        val bigBuckBunny = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        val sintelHls = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"

        return listOf(
            LiveMatch(
                id = "match-1",
                tournament = "CRICKET | ODI TRI-SERIES IN PAKISTAN",
                sport = SportType.CRICKET,
                team1Name = "Pakistan",
                team1Code = "PAK",
                team1FlagUrl = "🇵🇰",
                team2Name = "England",
                team2Code = "ENG",
                team2FlagUrl = "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
                status = MatchStatus.LIVE,
                timeOrScore = "06:25:22",
                matchTime = "Live Now",
                streams = listOf(
                    StreamSource(name = "WILLOW FHD", quality = "FHD", url = akamaiHls1, isDefault = true),
                    StreamSource(name = "SKY CRICKET FHD", quality = "FHD", url = akamaiHls2),
                    StreamSource(name = "TAPMAD FHD", quality = "FHD", url = akamaiHls3),
                    StreamSource(name = "TAPMAD 2 FHD", quality = "FHD", url = akamaiHls1),
                    StreamSource(name = "WILLOW ALT", quality = "FHD", url = sintelHls),
                    StreamSource(name = "PTV SPORTS HD", quality = "HD", url = akamaiHls2),
                    StreamSource(name = "SD SERVER", quality = "SD", url = bigBuckBunny)
                ),
                isFavorite = true
            ),
            LiveMatch(
                id = "match-2",
                tournament = "CRICKET | ICC WORLD TEST CHAMPIONSHIP",
                sport = SportType.CRICKET,
                team1Name = "South Africa",
                team1Code = "SA",
                team1FlagUrl = "🇿🇦",
                team2Name = "Australia",
                team2Code = "AUS",
                team2FlagUrl = "🇦🇺",
                status = MatchStatus.LIVE,
                timeOrScore = "06:25:22",
                matchTime = "Day 3 - Session 2",
                streams = listOf(
                    StreamSource(name = "SUPERSPORT CRICKET", quality = "FHD", url = akamaiHls2, isDefault = true),
                    StreamSource(name = "FOX CRICKET 501", quality = "FHD", url = akamaiHls1),
                    StreamSource(name = "STAR SPORTS HD 1", quality = "HD", url = akamaiHls3),
                    StreamSource(name = "SD SERVER", quality = "SD", url = bigBuckBunny)
                )
            ),
            LiveMatch(
                id = "match-3",
                tournament = "FOOTBALL | UEFA YOUTH LEAGUE",
                sport = SportType.FOOTBALL,
                team1Name = "PSG U19",
                team1Code = "PSG",
                team1FlagUrl = "🇫🇷",
                team2Name = "Barcelona U19",
                team2Code = "BAR",
                team2FlagUrl = "🇪🇸",
                status = MatchStatus.LIVE,
                timeOrScore = "02:15:22",
                matchTime = "2nd Half 68'",
                streams = listOf(
                    StreamSource(name = "BEIN SPORTS 1 HD", quality = "FHD", url = akamaiHls1, isDefault = true),
                    StreamSource(name = "MOVISTAR LIGA HD", quality = "FHD", url = akamaiHls3),
                    StreamSource(name = "UEFA TV SD", quality = "SD", url = sintelHls)
                ),
                isFavorite = true
            ),
            LiveMatch(
                id = "match-4",
                tournament = "CRICKET | TWENTY20 INTERNATIONAL WOMEN",
                sport = SportType.CRICKET,
                team1Name = "India Women",
                team1Code = "IND-W",
                team1FlagUrl = "🇮🇳",
                team2Name = "Zimbabwe Women",
                team2Code = "ZIM-W",
                team2FlagUrl = "🇿🇼",
                status = MatchStatus.LIVE,
                timeOrScore = "00:45:28",
                matchTime = "Innings Break",
                streams = listOf(
                    StreamSource(name = "FANCODE STREAM 1", quality = "FHD", url = akamaiHls3, isDefault = true),
                    StreamSource(name = "STAR SPORTS 2", quality = "HD", url = akamaiHls2),
                    StreamSource(name = "HOTSTAR SD", quality = "SD", url = bigBuckBunny)
                )
            ),
            LiveMatch(
                id = "match-5",
                tournament = "FOOTBALL | UEFA YOUTH LEAGUE",
                sport = SportType.FOOTBALL,
                team1Name = "Liverpool U19",
                team1Code = "LIV",
                team1FlagUrl = "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
                team2Name = "Villarreal U19",
                team2Code = "VIL",
                team2FlagUrl = "🇪🇸",
                status = MatchStatus.LIVE,
                timeOrScore = "00:15:28",
                matchTime = "1st Half 15'",
                streams = listOf(
                    StreamSource(name = "TNT SPORTS 1", quality = "FHD", url = akamaiHls1, isDefault = true),
                    StreamSource(name = "DAZN 1 FHD", quality = "FHD", url = sintelHls),
                    StreamSource(name = "SD SERVER", quality = "SD", url = akamaiHls2)
                )
            ),
            LiveMatch(
                id = "match-6",
                tournament = "FOOTBALL | UEFA YOUTH LEAGUE",
                sport = SportType.FOOTBALL,
                team1Name = "Man City U19",
                team1Code = "MCI",
                team1FlagUrl = "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
                team2Name = "AEK U19",
                team2Code = "AEK",
                team2FlagUrl = "🇬🇷",
                status = MatchStatus.LIVE,
                timeOrScore = "Live",
                matchTime = "1st Half 30'",
                streams = listOf(
                    StreamSource(name = "CITY+ LIVE", quality = "FHD", url = akamaiHls2, isDefault = true),
                    StreamSource(name = "COSMOTE SPORT", quality = "HD", url = akamaiHls1)
                )
            ),
            LiveMatch(
                id = "match-7",
                tournament = "FOOTBALL | UEFA CHAMPIONS LEAGUE",
                sport = SportType.FOOTBALL,
                team1Name = "PSG",
                team1Code = "PSG",
                team1FlagUrl = "🇫🇷",
                team2Name = "Barcelona",
                team2Code = "FCB",
                team2FlagUrl = "🇪🇸",
                status = MatchStatus.UPCOMING,
                timeOrScore = "Starts in 4 hours",
                matchTime = "01:00 AM",
                streams = listOf(
                    StreamSource(name = "CANAL+ FOOT HD", quality = "FHD", url = akamaiHls1, isDefault = true),
                    StreamSource(name = "TNT SPORTS ULTIMATE", quality = "FHD", url = akamaiHls3),
                    StreamSource(name = "MOVISTAR LIGA DE CAMPEONES", quality = "FHD", url = sintelHls),
                    StreamSource(name = "PARAMOUNT+ STREAM", quality = "HD", url = akamaiHls2)
                )
            ),
            LiveMatch(
                id = "match-8",
                tournament = "HOCKEY | NHL",
                sport = SportType.HOCKEY,
                team1Name = "Canucks",
                team1Code = "VAN",
                team1FlagUrl = "🇨🇦",
                team2Name = "Hurricanes",
                team2Code = "CAR",
                team2FlagUrl = "🇺🇸",
                status = MatchStatus.UPCOMING,
                timeOrScore = "Starts in 11 hours",
                matchTime = "08:00 AM",
                streams = listOf(
                    StreamSource(name = "SPORTSNET PACIFIC", quality = "FHD", url = akamaiHls2, isDefault = true),
                    StreamSource(name = "BALLY SPORTS SOUTH", quality = "HD", url = akamaiHls1)
                )
            ),
            LiveMatch(
                id = "match-9",
                tournament = "FOOTBALL | UEFA YOUTH LEAGUE",
                sport = SportType.FOOTBALL,
                team1Name = "Fenerbahçe U19",
                team1Code = "FEN",
                team1FlagUrl = "🇹🇷",
                team2Name = "Slavia Praha U19",
                team2Code = "SLA",
                team2FlagUrl = "🇨🇿",
                status = MatchStatus.FINISHED,
                timeOrScore = "Full Time (3-1)",
                matchTime = "Finished",
                streams = listOf(
                    StreamSource(name = "MATCH HIGHLIGHTS", quality = "FHD", url = bigBuckBunny, isDefault = true)
                )
            ),
            LiveMatch(
                id = "match-10",
                tournament = "BOXING | WORLD CHAMPIONSHIP",
                sport = SportType.BOXING,
                team1Name = "Fury",
                team1Code = "FUR",
                team1FlagUrl = "🇬🇧",
                team2Name = "Usyk",
                team2Code = "USY",
                team2FlagUrl = "🇺🇦",
                status = MatchStatus.UPCOMING,
                timeOrScore = "Tomorrow 10:00 PM",
                matchTime = "Riyadh Season",
                streams = listOf(
                    StreamSource(name = "DAZN PPV 1", quality = "FHD", url = akamaiHls1, isDefault = true),
                    StreamSource(name = "TNT SPORTS BOX OFFICE", quality = "FHD", url = akamaiHls3)
                )
            )
        )
    }

    private fun defaultChannels(): List<SportsChannel> {
        val streamHls1 = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"
        val streamHls2 = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val streamHls3 = "https://diceyk6a7voy4.cloudfront.net/e78752a1-2e83-43fa-85ae-3d508be29366/hls/de:02b6989d-e714-4227-9c57-a4c1a221a244:playlist.m3u8"

        return listOf(
            SportsChannel(
                id = "ch-1",
                name = "TNT Sports",
                category = "Sports",
                logoText = "TNT",
                streams = listOf(
                    StreamSource(name = "TNT SPORTS 1 HD", quality = "FHD", url = streamHls1, isDefault = true),
                    StreamSource(name = "TNT SPORTS 2 HD", quality = "HD", url = streamHls2),
                    StreamSource(name = "TNT BACKUP SD", quality = "SD", url = streamHls3)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-2",
                name = "TSN",
                category = "Sports",
                logoText = "TSN",
                streams = listOf(
                    StreamSource(name = "TSN 1 FHD", quality = "FHD", url = streamHls2, isDefault = true),
                    StreamSource(name = "TSN 2 HD", quality = "HD", url = streamHls1)
                )
            ),
            SportsChannel(
                id = "ch-3",
                name = "BeIN Sports",
                category = "Sports",
                logoText = "beIN",
                streams = listOf(
                    StreamSource(name = "BeIN SPORTS 1 PREMIUM", quality = "FHD", url = streamHls3, isDefault = true),
                    StreamSource(name = "BeIN SPORTS 2 ENGLISH", quality = "FHD", url = streamHls1),
                    StreamSource(name = "BeIN SERVER 3", quality = "HD", url = streamHls2)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-4",
                name = "FOX Sports",
                category = "Sports",
                logoText = "FOX",
                streams = listOf(
                    StreamSource(name = "FS1 HD", quality = "FHD", url = streamHls1, isDefault = true),
                    StreamSource(name = "FS2 HD", quality = "HD", url = streamHls2)
                )
            ),
            SportsChannel(
                id = "ch-5",
                name = "TUDN",
                category = "Sports",
                logoText = "TUDN",
                streams = listOf(StreamSource(name = "TUDN LIVE", quality = "FHD", url = streamHls2, isDefault = true))
            ),
            SportsChannel(
                id = "ch-6",
                name = "D Sports",
                category = "Sports",
                logoText = "DSports",
                streams = listOf(StreamSource(name = "D SPORTS HD", quality = "HD", url = streamHls3, isDefault = true))
            ),
            SportsChannel(
                id = "ch-7",
                name = "Premier Sports",
                category = "Sports",
                logoText = "PREMIER",
                streams = listOf(StreamSource(name = "PREMIER 1 FHD", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-8",
                name = "Premiere",
                category = "Sports",
                logoText = "PREMIERE",
                streams = listOf(StreamSource(name = "PREMIERE HD", quality = "HD", url = streamHls2, isDefault = true))
            ),
            SportsChannel(
                id = "ch-9",
                name = "Nova Sports",
                category = "Sports",
                logoText = "NOVA",
                streams = listOf(StreamSource(name = "NOVA SPORTS 1", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-10",
                name = "SPOTV",
                category = "Sports",
                logoText = "SPOTV",
                streams = listOf(
                    StreamSource(name = "SPOTV 1 HD", quality = "FHD", url = streamHls2, isDefault = true),
                    StreamSource(name = "SPOTV 2 HD", quality = "HD", url = streamHls3)
                )
            ),
            SportsChannel(
                id = "ch-11",
                name = "SporTV",
                category = "Sports",
                logoText = "sporTV",
                streams = listOf(StreamSource(name = "SporTV 1", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-12",
                name = "CT Sport",
                category = "Sports",
                logoText = "CT",
                streams = listOf(StreamSource(name = "CT SPORT LIVE", quality = "HD", url = streamHls3, isDefault = true))
            ),
            SportsChannel(
                id = "ch-13",
                name = "Fubo Sports",
                category = "Sports",
                logoText = "fubo",
                streams = listOf(StreamSource(name = "FUBO NETWORK", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-14",
                name = "EuroSport",
                category = "Sports",
                logoText = "EURO",
                streams = listOf(
                    StreamSource(name = "EUROSPORT 1", quality = "FHD", url = streamHls2, isDefault = true),
                    StreamSource(name = "EUROSPORT 2", quality = "HD", url = streamHls1)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-15",
                name = "SportDigital",
                category = "Sports",
                logoText = "SD",
                streams = listOf(StreamSource(name = "SPORTDIGITAL HD", quality = "HD", url = streamHls3, isDefault = true))
            ),
            SportsChannel(
                id = "ch-16",
                name = "CBS Golazo",
                category = "Sports",
                logoText = "GOLAZO",
                streams = listOf(StreamSource(name = "CBS GOLAZO 24/7", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-17",
                name = "DAZN",
                category = "Sports",
                logoText = "DAZN",
                streams = listOf(
                    StreamSource(name = "DAZN 1 FHD", quality = "FHD", url = streamHls3, isDefault = true),
                    StreamSource(name = "DAZN 2 HD", quality = "HD", url = streamHls2)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-18",
                name = "Star Sports",
                category = "Sports",
                logoText = "STAR",
                streams = listOf(
                    StreamSource(name = "STAR SPORTS 1 HD", quality = "FHD", url = streamHls1, isDefault = true),
                    StreamSource(name = "STAR SPORTS HINDI", quality = "HD", url = streamHls2)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-19",
                name = "FanCode",
                category = "FanCode IND",
                logoText = "FC",
                streams = listOf(
                    StreamSource(name = "FANCODE LIVE 1", quality = "FHD", url = streamHls3, isDefault = true),
                    StreamSource(name = "FANCODE LIVE 2", quality = "HD", url = streamHls1)
                )
            ),
            SportsChannel(
                id = "ch-20",
                name = "PTV Sports",
                category = "Pakistan",
                logoText = "PTV",
                streams = listOf(
                    StreamSource(name = "PTV SPORTS HD LIVE", quality = "FHD", url = streamHls2, isDefault = true),
                    StreamSource(name = "PTV SPORTS SD", quality = "SD", url = streamHls3)
                ),
                isFavorite = true
            ),
            SportsChannel(
                id = "ch-21",
                name = "A Sports",
                category = "Pakistan",
                logoText = "A",
                streams = listOf(StreamSource(name = "A SPORTS HD", quality = "FHD", url = streamHls1, isDefault = true))
            ),
            SportsChannel(
                id = "ch-22",
                name = "TEN Sports",
                category = "Sports",
                logoText = "TEN",
                streams = listOf(StreamSource(name = "TEN SPORTS HD", quality = "FHD", url = streamHls2, isDefault = true))
            ),
            SportsChannel(
                id = "ch-23",
                name = "Tapmad PK",
                category = "Tapmad PK",
                logoText = "tapmad",
                streams = listOf(
                    StreamSource(name = "TAPMAD LIVE 1", quality = "FHD", url = streamHls3, isDefault = true),
                    StreamSource(name = "TAPMAD LIVE 2", quality = "HD", url = streamHls1)
                )
            ),
            SportsChannel(
                id = "ch-24",
                name = "Willow Cricket",
                category = "Sports",
                logoText = "WILLOW",
                streams = listOf(
                    StreamSource(name = "WILLOW HD 1", quality = "FHD", url = streamHls1, isDefault = true),
                    StreamSource(name = "WILLOW HD 2", quality = "HD", url = streamHls2)
                ),
                isFavorite = true
            )
        )
    }

    private fun defaultCategories(): List<CategoryItem> {
        return listOf(
            CategoryItem(name = "SPORTS", channelCount = 24),
            CategoryItem(name = "LIVE EVENTS HD", channelCount = 18),
            CategoryItem(name = "LIVE EVENTS SD", channelCount = 14),
            CategoryItem(name = "FANCODE IND", channelCount = 10),
            CategoryItem(name = "FANCODE 2 IND", channelCount = 8),
            CategoryItem(name = "FANCODE BD", channelCount = 6),
            CategoryItem(name = "FANCODE NP", channelCount = 4),
            CategoryItem(name = "FANCODE SL", channelCount = 4),
            CategoryItem(name = "SONYLIV LIVE", channelCount = 8),
            CategoryItem(name = "SONYLIV LIVE 2", channelCount = 6),
            CategoryItem(name = "SONYLIV LIVE 3", channelCount = 6),
            CategoryItem(name = "ICC TV", channelCount = 5),
            CategoryItem(name = "CRICHD", channelCount = 12),
            CategoryItem(name = "PLUTO TV", channelCount = 20),
            CategoryItem(name = "TUBI TV", channelCount = 15),
            CategoryItem(name = "ROKU", channelCount = 16),
            CategoryItem(name = "FREE TV", channelCount = 25),
            CategoryItem(name = "FIRE TV", channelCount = 18),
            CategoryItem(name = "PAKISTAN", channelCount = 12),
            CategoryItem(name = "TAPMAD PK", channelCount = 8),
            CategoryItem(name = "NEWS", channelCount = 15),
            CategoryItem(name = "ISLAMIC", channelCount = 10)
        )
    }
}
