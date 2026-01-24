package com.example.tvplayer.player

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/**
 * NetworkGuard
 *
 * 用于在 App 启动阶段校验网络环境：
 * - ping 指定网关 / IP
 * - 成功则执行回调，放行 App 业务逻辑
 * - 失败提示并强制退出
 */
object NetworkGuard {

    private const val TARGET_HOST = "192.168.99.1"
    private const val MAX_TRY = 10
    private const val INTERVAL_MS = 1_000L

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 在 Activity.onCreate() 中调用。
     * 每次调用都会发起一次独立的网络检查流程。
     *
     * @param activity 用于显示对话框和执行UI操作的Activity实例。
     * @param onGuardPassed 网络检查通过后，在主线程上执行的回调函数。
     */
    fun attach(activity: Activity, onGuardPassed: () -> Unit) {
        executor.execute {
            repeat(MAX_TRY) { index ->
                if (pingOnce(TARGET_HOST)) {
                    // 检查通过，回到主线程执行后续的业务逻辑。
                    activity.runOnUiThread {
                        onGuardPassed()
                    }
                    return@execute
                }

                if (index < MAX_TRY - 1) {
                    Thread.sleep(INTERVAL_MS)
                }
            }

            // 如果所有尝试都失败了，则回到主线程处理UI。
            activity.runOnUiThread {
                showErrorAndExit(activity)
            }
        }
    }

    /**
     * 执行一次 ping
     */
    private fun pingOnce(host: String): Boolean {
        return try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("ping", "-c", "1", "-W", "2", host))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 网络错误提示并退出
     */
    private fun showErrorAndExit(activity: Activity) {
        if (activity.isFinishing) return

        AlertDialog.Builder(activity)
            .setTitle("提示：")
            .setMessage("未检测到授权，请在正确的网络环境中使用!!!")
            .setCancelable(false)
            .setPositiveButton("确定") { _, _ ->
                activity.finish()
                System.exit(0)
            }
            .show()
    }
}
