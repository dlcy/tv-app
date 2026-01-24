package com.example.tvplayer.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.tvplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.util.Date

/**
 * NTP (网络时间协议) 时间提供者。
 *
 * 负责从一个或多个NTP服务器获取准确的UTC时间，并计算与设备系统启动时间 (`SystemClock.elapsedRealtime()`) 的偏移量。
 * 这样可以提供一个不受用户手动修改系统时间影响的、稳定可靠的时间源。
 *
 * @param context 安卓上下文，用于访问SharedPreferences等系统服务。
 * @param scope 一个CoroutineScope，用于在此作用域内启动后台的同步任务。
 */
class NtpTimeProvider(
    private val context: Context,
    private val scope: CoroutineScope
) {

    /**
     * 核心属性：存储NTP时间和设备启动时间的偏移量（单位：毫秒）。
     *
     * 计算公式: timeOffset = ntpTime - SystemClock.elapsedRealtime()
     *
     * 一旦这个值被成功计算出来，我们就可以通过 `SystemClock.elapsedRealtime() + timeOffset` 来随时获取当前的NTP时间。
     * 使用 @Volatile 注解确保其在多线程间的可见性。
     */
    @Volatile
    private var timeOffset: Long? = null

    /**
     * 用于持久化存储用户自定义的NTP服务器地址。
     * 使用 lazy 初始化，只有在第一次访问时才会创建实例。
     */
    private val prefs by lazy {
        context.getSharedPreferences("ntp_settings", Context.MODE_PRIVATE)
    }

    /**
     * 伴生对象，用于存放常量。
     */
    companion object {
        private const val TAG = "NtpTimeProvider"
        private const val KEY_NTP_SERVER = "ntp_server" // SharedPreferences的键名
        private const val NTP_TIMEOUT = 5000 // NTP请求的超时时间（毫秒）

        // 内置的备用NTP服务器列表，当用户自定义的服务器不可用时会尝试这些。
        private val NTP_SERVERS = listOf(
            "124.232.139.1",
            "ntp.aliyun.com",
            "time.google.com",
            "ntp.ntsc.ac.cn",
            "1.pool.ntp.org"
        )
    }

    /**
     * 对外提供当前准确时间的统一接口。
     *
     * @return 如果已经成功与NTP服务器同步，则返回基于 `SystemClock.elapsedRealtime()` 和偏移量计算出的准确时间。
     *         如果尚未同步或同步失败，则回退到使用设备的系统时间 `System.currentTimeMillis()`。
     */
    fun getCurrentTimeMillis(): Long {
        val offset = timeOffset
        return if (offset != null) {
            SystemClock.elapsedRealtime() + offset
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * 主动触发一次NTP时间同步。
     * 这是一个异步操作，结果通过可选的回调函数返回。
     *
     * @param onComplete 同步完成后的回调函数，它会在主线程上被调用。
     *                   - (Boolean): 表示同步是否成功。
     *                   - (String): 包含同步结果或错误信息的描述性文本。
     */
    fun syncNow(onComplete: ((Boolean, String) -> Unit)? = null) {
        // 在传入的协程作用域内启动一个新协程，以执行网络操作。
        scope.launch {
            // 从NTP服务器获取时间，这是一个挂起函数。
            val (time, error) = fetchTimeFromNtp()

            if (time != null) {
                // 同步成功，计算并更新时间偏移量。
                timeOffset = time - SystemClock.elapsedRealtime()
                // 如果有回调，则在主线程中执行它，并传递成功信息。
                onComplete?.let {
                    withContext(Dispatchers.Main) {
                        it(true, "时间已同步: ${Date(getCurrentTimeMillis())}")
                    }
                }
            } else {
                // 同步失败，在主线程中执行回调，并传递失败信息。
                onComplete?.let {
                    withContext(Dispatchers.Main) {
                        it(false, "NTP 时间同步失败: ${error?.message ?: "所有服务器无响应"}")
                    }
                }
            }
        }
    }

    /**
     * 核心的私有方法，负责实际执行NTP网络请求。
     * 它会依次尝试所有可用的服务器（用户自定义的优先），直到成功或全部失败。
     *
     * @return 返回一个 Pair，包含获取到的时间戳（Long?）和可能发生的异常（Exception?）。
     */
    private suspend fun fetchTimeFromNtp(): Pair<Long?, Exception?> {
        // 1. 准备要尝试的服务器列表
        val userServer = prefs.getString(KEY_NTP_SERVER, null) // 优先使用用户设置的服务器
        val servers = (listOfNotNull(userServer) + NTP_SERVERS).distinct() // 合并并去重
        var lastError: Exception? = null

        // 2. 遍历服务器列表并尝试同步
        for (server in servers) {
            val client = NTPUDPClient().apply {
                defaultTimeout = NTP_TIMEOUT
            }

            try {
                Log.d(TAG, "正在尝试与NTP服务器同步: $server")
                // 将域名解析为IP地址，这是一个网络操作，需要在IO线程执行。
                val address = withContext(Dispatchers.IO) {
                    InetAddress.getByName(server)
                }
                // 打开NTP客户端连接。
                withContext(Dispatchers.IO) {
                    client.open()
                }
                // 获取时间信息，这是核心的网络请求。
                val timeInfo = withContext(Dispatchers.IO) {
                    client.getTime(address)
                }
                Log.d(TAG, "与 $server 同步成功")
                // 3. 只要有一个服务器成功，就立即返回结果
                return Pair(timeInfo.returnTime, null)
            } catch (e: Exception) {
                // 捕获可能发生的各种异常（如超时、主机找不到等）。
                Log.w(TAG, "与 $server 同步失败: ${e.message}")
                lastError = e
            } finally {
                // 确保无论成功还是失败，都关闭客户端连接。
                if (client.isOpen) {
                    withContext(Dispatchers.IO) {
                        client.close()
                    }
                }
            }
        }
        // 4. 如果所有服务器都尝试失败，则返回null和最后一次的错误信息。
        return Pair(null, lastError)
    }

    /**
     * 显示一个对话框，允许用户查看和修改自定义的NTP服务器地址。
     */
    fun showNtpSettingsDialog() {
        // 获取当前保存的服务器地址，如果没有则使用内置列表的第一个作为默认值。
        val current = prefs.getString(KEY_NTP_SERVER, NTP_SERVERS.first())!!
        val input = EditText(context).apply {
            setText(current)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.ntp_server_settings)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val server = input.text.toString().trim()
                if (server.isNotEmpty()) {
                    // 保存用户输入的新服务器地址。
                    prefs.edit().putString(KEY_NTP_SERVER, server).apply()
                    // 保存后立即触发一次同步，并通过Toast显示结果。
                    syncNow { _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
