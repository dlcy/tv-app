package com.example.tvplayer.model

/**
 * 数据模型类，用于封装一个电视频道的所有信息。
 *
 * 这是一个不可变的数据类 (data class)，用于在应用的各个层之间（如数据加载、UI显示、播放逻辑）安全地传递频道数据。
 *
 * @property number 频道的唯一编号，用于数字选台和排序。
 * @property name 频道的显示名称，例如 "CCTV-1 综合"。
 * @property url 频道的播放流地址 (URL)，可以是 m3u8, flv 等格式。
 */
data class Channel(
    val number: Int,
    val name: String,
    val url: String
)
