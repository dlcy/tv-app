package com.example.tvplayer.player

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.tvplayer.R

/**
 * 负责管理播放服务器的地址，通过 SharedPreferences 进行持久化存储，并提供设置对话框让用户修改。
 *
 * @param context 安卓上下文，用于访问SharedPreferences和创建对话框。
 */
class PlayerServer(private val context: Context) {

    /**
     * 伴生对象，用于存放常量。
     */
    companion object {
        // SharedPreferences 文件的名称
        private const val PREFS_NAME = "player_settings"
        // 用于存储服务器IP地址的键名
        private const val KEY_SERVER_IP = "server_ip"
        // 如果用户没有设置，则使用此默认IP
        private const val DEFAULT_SERVER_IP = "2.2.2.2:6610"
    }

    /**
     * SharedPreferences 实例，用于读写数据。
     * 使用 lazy 委托进行延迟初始化，只有在第一次访问时才会创建。
     */
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取当前配置的服务器 IP 地址。
     *
     * @return 如果 SharedPreferences 中已保存地址，则返回该地址；否则返回默认地址。
     */
    fun getServerIp(): String {
        return prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    }

    /**
     * 保存用户设置的服务器 IP 地址到 SharedPreferences。
     * @param ip 用户输入的服务器地址字符串。
     */
    private fun setServerIp(ip: String) {
        prefs.edit().putString(KEY_SERVER_IP, ip.trim()).apply()
    }

    /**
     * 显示一个对话框，允许用户查看和修改播放服务器的地址。
     */
    fun showServerSettingsDialog() {
        // 获取当前保存的服务器地址，用于在输入框中显示
        val currentServer = getServerIp()

        // 创建一个EditText作为对话框的输入视图
        val input = EditText(context).apply {
            setText(currentServer) // 将当前服务器地址填充到输入框
            hint = "例如：1.1.1.1:6610" // 提供输入格式提示
        }

        // 使用AlertDialog.Builder构建对话框
        AlertDialog.Builder(context)
            .setTitle(R.string.action_server_settings) // 设置对话框标题
            .setView(input) // 将输入框设置为对话框的内容视图
            .setPositiveButton(R.string.save) { _, _ ->
                // 设置“保存”按钮的点击事件
                setServerIp(input.text.toString()) // 保存输入框中的新地址
                Toast.makeText(context, "播放服务器已保存", Toast.LENGTH_SHORT).show() // 弹出提示
            }
            .setNegativeButton(R.string.cancel, null) // 设置“取消”按钮，点击后直接关闭对话框
            .show() // 显示对话框
    }
}
