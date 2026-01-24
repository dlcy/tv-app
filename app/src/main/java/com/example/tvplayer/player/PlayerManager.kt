package com.example.tvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import java.text.SimpleDateFormat
import java.util.*

/**
 * 核心播放管理器。
 *
 * 封装了ExoPlayer的创建、配置和控制，并处理播放URL中的动态占位符替换。
 *
 * @param context 安卓上下文，用于初始化ExoPlayer等组件。
 * @param ntpTimeProvider NTP时间提供者，用于获取准确的、不受系统时间影响的时间戳。
 */
class PlayerManager(
    context: Context,
    private val ntpTimeProvider: NtpTimeProvider
) {

    // ExoPlayer 播放器实例，对外暴露以便于UI层（如Activity/Fragment）进行绑定。
    val player: ExoPlayer

    // 模拟一个特定的设备User-Agent，某些视频流服务可能会校验它。
    private val userAgent =
        "Dalvik/1.6.0 (Linux; U; Android 4.4.2; IP906H Build/00100499010290600002)"

    init {
        // --- ExoPlayer 初始化与配置 ---

        // 1. 创建用于加载HTTP/HTTPS媒体数据的数据源工厂。
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent) // 设置自定义User-Agent
            .setAllowCrossProtocolRedirects(true) // 允许跨协议重定向（例如从http重定向到https）

        // 2. 创建一个通用的、能够自动识别流媒体类型的媒体源工厂。
        //    它能自动处理 HLS(.m3u8) 和 Progressive(如 udp-over-http, mp4, mkv) 等多种格式。
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 3. 创建一个渲染器工厂 (RenderersFactory) 实例。
        val renderersFactory = DefaultRenderersFactory(context)
            // 设置优先使用扩展渲染器（解码器），这是播放特殊格式音频的关键。
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // 4. 使用配置好的渲染器工厂和媒体源工厂来构建ExoPlayer实例。
        player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    // 在主线程上执行操作的Handler，用于确保所有对ExoPlayer的调用都在其期望的线程上进行。
    private val mainHandler = Handler(Looper.getMainLooper())
    // 播放服务器地址管理器。
    private val playerServer = PlayerServer(context)

    /**
     * 播放指定的URL。
     * @param url 包含占位符的原始播放地址。
     */
    fun play(url: String) {
        // 1. 替换URL中的动态占位符（如服务器IP和时间戳）。
        val finalUrl = replacePlaceholders(url)
        // 2. 从URL创建MediaItem。
        val mediaItem = MediaItem.fromUri(Uri.parse(finalUrl))

        // 3. 在主线程中将MediaItem设置给播放器并开始播放。
        //    ExoPlayer会使用我们在init中设置好的 `mediaSourceFactory` 自动创建合适的媒体源。
        mainHandler.post {
            player.setMediaItem(mediaItem)
            player.prepare() // 准备播放资源
            player.play()    // 开始播放
        }
    }

    /**
     * 停止播放。
     */
    fun stop() {
        mainHandler.post { player.stop() }
    }

    /**
     * 释放播放器资源。
     * 在退出播放页面或应用时必须调用此方法，以防止内存泄漏。
     */
    fun release() {
        mainHandler.post { player.release() }
    }

    /**
     * 替换URL中的占位符。
     * 这是一个实用方法，用于处理需要动态生成URL的场景。
     *
     * @param url 原始URL模板。
     * @return 替换占位符后的最终URL。
     */
    private fun replacePlaceholders(url: String): String {
        var result = url

        // 替换服务器IP地址占位符
        if (result.contains("{serverip}")) {
            result = result.replace("{serverip}", playerServer.getServerIp())
        }

        // 替换时间戳相关的占位符
        if (result.contains("{timestamp}") || result.contains("{starttime}")) {
            val nowMillis = ntpTimeProvider.getCurrentTimeMillis()
            // 替换ISO UTC格式的时间戳
            if (result.contains("{timestamp}")) {
                result = result.replace("{timestamp}", formatIsoUtc(nowMillis))
            }
            // 替换Unix秒级时间戳
            if (result.contains("{starttime}")) {
                result = result.replace("{starttime}", (nowMillis / 1000).toString())
            }
        }
        return result
    }

    /**
     * 将毫秒级时间戳格式化为 "yyyyMMdd'T'HHmmss.00Z'" 格式的ISO 8601 UTC字符串。
     * @param timeMillis 毫秒时间戳。
     * @return 格式化后的UTC时间字符串。
     */
    private fun formatIsoUtc(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'.00Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMillis))
    }
}
