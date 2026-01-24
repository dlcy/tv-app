package com.example.tvplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.tvplayer.R
import com.example.tvplayer.databinding.ActivityMainBinding
import com.example.tvplayer.model.Channel
import com.example.tvplayer.player.NetworkGuard
import com.example.tvplayer.player.NtpTimeProvider
import com.example.tvplayer.player.PlayerManager
import com.example.tvplayer.player.PlayerServer
import java.io.File

/**
 * 主界面 Activity，是整个应用的入口和核心协调者。
 *
 * 主要职责:
 * 1. **服务协调**: 初始化并管理 PlayerManager, PlayerServer, NtpTimeProvider 等核心服务。
 * 2. **UI管理**: 控制 Toolbar、频道列表、数字输入提示等UI元素的显示、隐藏和交互。
 * 3. **按键处理**: 捕获并处理来自遥控器的所有关键事件（上下切换、数字选台、菜单、返回等）。
 * 4. **数据管理**: 负责频道列表的加载、导入、清空和持久化存储。
 * 5. **播放控制**: 作为中枢，根据用户操作调用 PlayerManager 来执行播放、停止等命令。
 */
class MainActivity : AppCompatActivity() {

    // --- 视图绑定与核心服务实例 ---
    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var playerServer: PlayerServer
    private lateinit var ntpTimeProvider: NtpTimeProvider

    // --- 状态变量 ---
    private val channels = mutableListOf<Channel>() // 内存中的频道数据列表
    private var currentChannelIndex = 0 // 当前正在播放的频道在列表中的索引
    private var inputBuffer = "" // 用于接收用户通过遥控器输入的数字频道号

    // --- 文件与定时器 ---
    private val channelsFile by lazy { File(filesDir, "channels.txt") } // 持久化存储频道列表的文件

    private val uiHandler = Handler(Looper.getMainLooper()) // 用于执行UI相关的延时任务
    private val hideChannelListRunnable = Runnable { binding.channelList.isVisible = false } // 延时隐藏频道列表的任务
    private val hideToolbarRunnable = Runnable { supportActionBar?.hide() } // 延时隐藏顶部Toolbar的任务

    private val channelSwitchHandler = Handler(Looper.getMainLooper()) // 用于处理数字选台的延时任务
    private val channelSwitchRunnable = Runnable { switchByNumber() } // 真正执行数字选台的逻辑

    // Activity Result API: 用于启动文件选择器并处理返回结果，替代了已废弃的 onActivityResult。
    private val importChannelsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { // 如果用户成功选择了一个文件
                try {
                    // 获取对该文件URI的持久化读取权限，这对于非应用私有文件至关重要。
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    // 从选择的文件URI中导入频道数据
                    importChannelsFromFile(it)
                } catch (e: Exception) {
                    Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /**
     * 伴生对象，存放常量。
     */
    companion object {
        private const val PREFS_NAME = "settings" // SharedPreferences 文件名
        private const val PREF_LAST_CHANNEL = "last_channel_index" // 用于保存上次退出时播放的频道索引
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置 Toolbar 并默认隐藏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.hide()

        // --- 核心服务初始化 (播放器和NTP除外) ---
        playerServer = PlayerServer(this)
        ntpTimeProvider = NtpTimeProvider(this, lifecycleScope)

        // --- 网络守卫启动 ---
        // 先执行网络检查，检查通过后，在回调中执行真正的业务逻辑（初始化播放器、加载频道、播放）
        NetworkGuard.attach(this) {
            // --- 核心服务初始化 (播放器) ---
            playerManager = PlayerManager(this, ntpTimeProvider)
            binding.playerView.player = playerManager.player

            // 监听播放状态，实现屏幕常亮功能
            playerManager.player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            })

            // --- UI 初始化 ---
            setupUI()

            // --- 数据加载与播放 ---
            loadChannels()
            ntpTimeProvider.syncNow()
        }
    }

    /**
     * 封装所有与UI相关的初始化代码。
     * 只有在网络检查通过后才被调用。
     */
    private fun setupUI() {
        // 为播放器视图添加点击事件，使其在被点击时能调出频道列表
        binding.playerView.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                resetHideTimers() // 重置所有UI的自动隐藏计时器
                showChannelList()
            }
        }

        // 初始化频道列表的Adapter
        channelAdapter = ChannelAdapter(channels) { index ->
            playChannel(index) // 设置列表项的点击回调：播放对应频道
            binding.channelList.isVisible = false // 点击后立即隐藏列表
        }

        // 将Adapter和LayoutManager设置给RecyclerView
        binding.channelList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
    }

    override fun onResume() {
        super.onResume()
        // 当Activity返回前台时，重置UI隐藏计时器，让UI元素重新显示一段时间
        resetHideTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 在Activity销毁时，释放播放器资源，防止内存泄漏
        if (::playerManager.isInitialized) {
            playerManager.release()
        }
    }

    /* ---------- 菜单 ---------- */

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_channels -> importChannels()       // 导入频道
            R.id.action_clear_channels -> clearChannels()         // 清空频道
            R.id.action_ntp_settings -> ntpTimeProvider.showNtpSettingsDialog() // NTP服务器设置
            R.id.action_server_settings -> playerServer.showServerSettingsDialog() // 播放服务器设置
            R.id.action_hide_menu -> supportActionBar?.hide()     // 隐藏菜单栏
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /* ---------- 频道文件导入 ---------- */

    /**
     * 启动系统文件选择器，让用户选择一个频道列表文件。
     */
    private fun importChannels() {
        // 指定可选择的文件类型
        importChannelsLauncher.launch(arrayOf("text/plain", "audio/x-mpegurl", "application/octet-stream"))
    }

    /**
     * 解析从文件选择器返回的URI，并加载频道数据。
     * 支持两种格式：#EXTINF (m3u格式) 和 `频道名,URL` (简单文本格式)。
     */
    private fun importChannelsFromFile(uri: Uri) {
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val list = mutableListOf<Channel>()
        var num = 1

        if (content.contains("#EXTINF")) { // m3u 格式解析
            var name: String? = null
            content.lines().forEach {
                when {
                    it.startsWith("#EXTINF") -> name = it.substringAfterLast(",").trim()
                    it.startsWith("http") && name != null -> {
                        list.add(Channel(num++, name!!, it.trim()))
                        name = null
                    }
                }
            }
        } else { // 简单文本格式解析
            content.lines().forEach {
                val idx = it.lastIndexOfAny(charArrayOf(',', ' ', '	'))
                if (idx > 0) {
                    val name = it.substring(0, idx).trim()
                    val url = it.substring(idx + 1).trim()
                    if (url.startsWith("http")) {
                        list.add(Channel(num++, name, url))
                    }
                }
            }
        }

        if (list.isEmpty()) return

        // 更新数据源并保存到文件，然后从第一个频道开始播放
        channels.clear()
        channels.addAll(list)
        saveChannels()
        playChannel(0)
    }

    /* ---------- 频道数据管理 ---------- */

    /**
     * 加载频道数据。优先从本地文件加载，如果文件不存在则创建默认频道列表。
     */
    private fun loadChannels() {
        if (!channelsFile.exists()) {
            createDefaultChannels()
        } else {
            channelsFile.forEachLine {
                val p = it.split(",", limit = 3)
                if (p.size == 3) {
                    channels.add(Channel(p[0].toInt(), p[1], p[2]))
                }
            }
        }
        // 加载完成后，恢复到上次播放的频道
        val last = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_LAST_CHANNEL, 0)
        playChannel(last.coerceIn(0, if (channels.isEmpty()) 0 else channels.lastIndex))
    }

    /**
     * 创建一个默认的频道列表，用于首次启动或数据被清空后。
     */
    private fun createDefaultChannels() {
        channels.clear()
        channels.add(Channel(1,"湖南卫视高清","http://{serverip}/000000002000/201500000067/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(2,"湖南经视高清","http://{serverip}/000000002000/201500000068/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(3,"湖南都市高清","http://{serverip}/000000002000/201500000151/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(4,"湖南电视剧高清","http://{serverip}/000000002000/201500000155/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(5,"湖南电影高清","http://{serverip}/000000002000/201500000216/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(6,"湖南爱晚高清","http://{serverip}/000000002000/201500000222/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(7,"湖南娱乐高清","http://{serverip}/000000002000/201500000152/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(8,"金鹰纪实高清","http://{serverip}/000000002000/201500000156/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(9,"金鹰卡通高清","http://{serverip}/000000002000/201500000154/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(10,"湖南国际高清","http://{serverip}/000000002000/201500000153/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(11,"快乐购高清","http://{serverip}/000000002000/201500000223/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(12,"湖南教育高清","http://{serverip}/000000002000/201500000377/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(13,"嘉丽购高清","http://{serverip}/000000002000/201500000140/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(14,"优购物高清","http://{serverip}/000000002000/201500000564/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(15,"茶频道高清","http://{serverip}/000000002000/201500000383/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(16,"CCTV1高清","http://{serverip}/000000002000/201500000063/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(17,"CCTV2高清","http://{serverip}/000000002000/201500000129/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(18,"CCTV3高清","http://{serverip}/000000002000/201500000124/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(19,"CCTV4高清","http://{serverip}/000000002000/201500000204/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(20,"CCTV5高清","http://{serverip}/000000002000/201500000125/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(21,"CCTV6高清","http://{serverip}/000000002000/201500000126/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(22,"CCTV7高清","http://{serverip}/000000002000/201500000130/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(23,"CCTV8高清","http://{serverip}/000000002000/201500000127/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(24,"CCTV9高清","http://{serverip}/000000002000/201500000131/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(25,"CCTV10高清","http://{serverip}/000000002000/201500000132/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(26,"CCTV11高清","http://{serverip}/000000002000/201500000389/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(27,"CCTV12高清","http://{serverip}/000000002000/201500000133/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(28,"CCTV13高清","http://{serverip}/000000002000/201500000460/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(29,"CCTV14高清","http://{serverip}/000000002000/201500000134/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(30,"CCTV15高清","http://{serverip}/000000002000/201500000390/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(31,"CCTV16高清","http://{serverip}/000000002000/201500000501/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(32,"CCTV17高清","http://{serverip}/000000002000/201500000382/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(33,"CCTV5+高清","http://{serverip}/000000002000/201500000062/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(34,"CCTV4欧洲高清","http://{serverip}/000000002000/201500000495/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(35,"CCTV4美洲高清","http://{serverip}/000000002000/201500000496/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(36,"CGTN高清","http://{serverip}/000000002000/201500000019/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(37,"CGTN纪录高清","http://{serverip}/000000002000/201500000011/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(38,"CGTN西班牙语高清","http://{serverip}/000000002000/201500000410/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(39,"CGTN阿拉伯语高清","http://{serverip}/000000002000/201500000497/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(40,"CGTN法语高清","http://{serverip}/000000002000/201500000409/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(41,"CGTN俄语高清","http://{serverip}/000000002000/201500000408/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(42,"江苏卫视高清","http://{serverip}/000000002000/201500000070/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(43,"浙江卫视高清","http://{serverip}/000000002000/201500000064/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(44,"安徽卫视高清","http://{serverip}/000000002000/201500000345/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(45,"北京卫视高清","http://{serverip}/000000002000/201500000065/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(46,"天津卫视高清","http://{serverip}/000000002000/201500000123/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(47,"东方卫视高清","http://{serverip}/000000002000/201500000069/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(48,"江西卫视高清","http://{serverip}/000000002000/201500000356/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(49,"深圳卫视高清","http://{serverip}/000000002000/201500000066/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(50,"辽宁卫视高清","http://{serverip}/000000002000/201500000346/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(51,"东南卫视高清","http://{serverip}/000000002000/201500000336/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(52,"海南卫视高清","http://{serverip}/000000002000/201500000477/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(53,"广东卫视高清","http://{serverip}/000000002000/201500000335/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(54,"湖北卫视高清","http://{serverip}/000000002000/201500000159/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(55,"广西卫视高清","http://{serverip}/000000002000/201500000512/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(56,"山东卫视高清","http://{serverip}/000000002000/201500000071/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(57,"四川卫视高清","http://{serverip}/000000002000/201500000458/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(58,"重庆卫视高清","http://{serverip}/000000002000/201500000459/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(59,"云南卫视高清","http://{serverip}/000000002000/201500000513/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(60,"河南卫视高清","http://{serverip}/000000002000/201500000476/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(61,"黑龙江卫视高清","http://{serverip}/000000002000/201500000072/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(62,"贵州卫视高清","http://{serverip}/000000002000/201500000347/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(63,"河北卫视高清","http://{serverip}/000000002000/201500000348/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(64,"吉林卫视高清","http://{serverip}/000000002000/201500000475/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(65,"陕西卫视高清","http://{serverip}/000000002000/201500000514/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(66,"山西卫视","http://{serverip}/000000002000/201500000111/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(67,"内蒙古卫视","http://{serverip}/000000002000/201500000107/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(68,"宁夏卫视","http://{serverip}/000000002000/201500000108/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(69,"西藏卫视","http://{serverip}/000000002000/201500000117/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(70,"新疆卫视","http://{serverip}/000000002000/201500000116/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(71,"甘肃卫视高清","http://{serverip}/000000002000/201500000461/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(72,"卡酷少儿高清","http://{serverip}/000000002000/201500000515/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(73,"优漫卡通","http://{serverip}/000000002000/201500000059/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(74,"青海卫视高清","http://{serverip}/000000002000/201500000516/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(75,"哈哈炫动","http://{serverip}/000000002000/201500000251/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(76,"嘉佳卡通","http://{serverip}/000000002000/201500000374/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(77,"厦门卫视","http://{serverip}/000000002000/201500000375/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(78,"兵团卫视","http://{serverip}/000000002000/201500000376/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(79,"三沙卫视高清","http://{serverip}/000000002000/201500000396/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(80,"延边卫视","http://{serverip}/000000002000/201500000554/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(81,"CETV-1高清","http://{serverip}/000000002000/201500000342/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(82,"CETV-2","http://{serverip}/000000002000/201500000391/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(83,"山东教育","http://{serverip}/000000002000/201500000341/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(84,"CETV-4高清","http://{serverip}/000000002000/201500000392/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(85,"纪实科教高清","http://{serverip}/000000002000/201500000575/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(86,"四川康巴卫视","http://{serverip}/000000002000/201500000411/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(87,"邵阳新闻综合高清","http://{serverip}/000000002000/201500000452/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(88,"邵阳文旅民生高清","http://{serverip}/000000002000/201500000453/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(89,"天元围棋高清","http://{serverip}/000000002000/201500000113/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(90,"四海钓鱼","http://{serverip}/000000002000/201500000054/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(91,"快乐垂钓高清","http://{serverip}/000000002000/201500000245/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(92,"生态环境","http://{serverip}/000000002000/201500000534/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(93,"国学","http://{serverip}/000000002000/201500000486/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(94,"优优宝贝","http://{serverip}/000000002000/201500000119/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(95,"环球旅游","http://{serverip}/000000002000/201500000484/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(96,"家庭理财","http://{serverip}/000000002000/201500000102/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(97,"车迷","http://{serverip}/000000002000/201500000485/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(98,"财富天下","http://{serverip}/000000002000/201500000091/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(99,"证券服务","http://{serverip}/000000002000/201500000506/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(100,"收藏天下","http://{serverip}/000000002000/201500000507/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(101,"家庭影院高清","http://{serverip}/000000002000/201500000384/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(102,"影迷电影高清","http://{serverip}/000000002000/201500000385/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(103,"动作电影高清","http://{serverip}/000000002000/201500000386/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(104,"新动漫","http://{serverip}/000000002000/201500000387/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(105,"欢笑剧场 4K","http://{serverip}/000000002000/201500000577/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(106,"都市剧场高清","http://{serverip}/000000002000/201500000585/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(107,"动漫秀场高清","http://{serverip}/000000002000/201500000581/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(108,"魅力足球高清","http://{serverip}/000000002000/201500000583/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(109,"法治天地高清","http://{serverip}/000000002000/201500000578/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(110,"金色学堂高清","http://{serverip}/000000002000/201500000586/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(111,"游戏风云高清","http://{serverip}/000000002000/201500000582/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(112,"乐游高清","http://{serverip}/000000002000/201500000579/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(113,"生活时尚高清","http://{serverip}/000000002000/201500000584/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(114,"东方财经高清","http://{serverip}/000000002000/201500000580/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(115,"第一剧场","http://{serverip}/000000002000/201500000517/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(116,"风云剧场","http://{serverip}/000000002000/201500000518/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(117,"风云音乐","http://{serverip}/000000002000/201500000519/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(118,"风云足球","http://{serverip}/000000002000/201500000520/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(119,"怀旧剧场","http://{serverip}/000000002000/201500000521/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(120,"女性时尚","http://{serverip}/000000002000/201500000522/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(121,"央视文化精品","http://{serverip}/000000002000/201500000523/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(122,"世界地理","http://{serverip}/000000002000/201500000524/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(123,"央视台球","http://{serverip}/000000002000/201500000525/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(124,"兵器科技","http://{serverip}/000000002000/201500000526/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(125,"电视指南","http://{serverip}/000000002000/201500000527/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(126,"高尔夫网球","http://{serverip}/000000002000/201500000528/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(127,"先锋乒羽","http://{serverip}/000000002000/201500000115/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(128,"早期教育","http://{serverip}/000000002000/201500000563/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(129,"中学生","http://{serverip}/000000002000/201500000561/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(130,"环球奇观","http://{serverip}/000000002000/201500000560/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(131,"茶频道","http://{serverip}/000000002000/201500000383/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(132,"中国天气高清","http://{serverip}/000000002000/201500000158/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(133,"老故事","http://{serverip}/000000002000/201500000398/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(134,"书画","http://{serverip}/000000002000/201500000399/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(135,"梨园高清","http://{serverip}/000000002000/201500000478/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(136,"文物宝库高清","http://{serverip}/000000002000/201500000479/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(137,"武术世界高清","http://{serverip}/000000002000/201500000480/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(138,"湖南卫视超清","http://{serverip}/000000002000/201500000219/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(139,"湖南卫视","http://{serverip}/000000002000/201500000067/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(140,"湖南经视","http://{serverip}/000000002000/201500000068/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(141,"湖南都市","http://{serverip}/000000002000/201500000151/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(142,"湖南电视剧","http://{serverip}/000000002000/201500000155/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(143,"湖南电影","http://{serverip}/000000002000/201500000216/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(144,"湖南爱晚","http://{serverip}/000000002000/201500000222/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(145,"湖南娱乐","http://{serverip}/000000002000/201500000152/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(146,"金鹰纪实","http://{serverip}/000000002000/201500000156/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(147,"金鹰卡通","http://{serverip}/000000002000/201500000154/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(148,"湖南国际","http://{serverip}/000000002000/201500000153/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(149,"快乐购","http://{serverip}/000000002000/201500000223/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(150,"湖南教育","http://{serverip}/000000002000/201500000377/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(151,"CCTV1","http://{serverip}/000000002000/201500000063/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(152,"CCTV2","http://{serverip}/000000002000/201500000129/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(153,"CCTV3","http://{serverip}/000000002000/201500000124/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(154,"CCTV4","http://{serverip}/000000002000/201500000204/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(155,"CCTV5","http://{serverip}/000000002000/201500000125/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(156,"CCTV6","http://{serverip}/000000002000/201500000126/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(157,"CCTV7","http://{serverip}/000000002000/201500000130/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(158,"CCTV8","http://{serverip}/000000002000/201500000127/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(159,"CCTV9","http://{serverip}/000000002000/201500000131/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(160,"CCTV10","http://{serverip}/000000002000/201500000132/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(161,"CCTV11","http://{serverip}/000000002000/201500000389/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(162,"CCTV12","http://{serverip}/000000002000/201500000133/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(163,"CCTV13","http://{serverip}/000000002000/201500000460/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(164,"CCTV14","http://{serverip}/000000002000/201500000134/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(165,"CCTV15","http://{serverip}/000000002000/201500000390/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(166,"CCTV16","http://{serverip}/000000002000/201500000501/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(167,"CCTV17","http://{serverip}/000000002000/201500000382/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(168,"CCTV5+","http://{serverip}/000000002000/201500000062/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(169,"CCTV4欧洲","http://{serverip}/000000002000/201500000495/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(170,"CCTV4美洲","http://{serverip}/000000002000/201500000496/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(171,"CGTN","http://{serverip}/000000002000/201500000019/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(172,"CGTN纪录","http://{serverip}/000000002000/201500000011/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(173,"CGTN西班牙语","http://{serverip}/000000002000/201500000410/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(174,"CGTN阿拉伯语","http://{serverip}/000000002000/201500000497/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(175,"CGTN法语","http://{serverip}/000000002000/201500000409/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(176,"CGTN俄语","http://{serverip}/000000002000/201500000408/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(177,"江苏卫视","http://{serverip}/000000002000/201500000070/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(178,"浙江卫视","http://{serverip}/000000002000/201500000064/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(179,"安徽卫视","http://{serverip}/000000002000/201500000345/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(180,"北京卫视","http://{serverip}/000000002000/201500000065/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(181,"天津卫视","http://{serverip}/000000002000/201500000123/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(182,"东方卫视","http://{serverip}/000000002000/201500000069/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(183,"江西卫视","http://{serverip}/000000002000/201500000356/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(184,"深圳卫视","http://{serverip}/000000002000/201500000066/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(185,"辽宁卫视","http://{serverip}/000000002000/201500000346/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(186,"东南卫视","http://{serverip}/000000002000/201500000336/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(187,"海南卫视","http://{serverip}/000000002000/201500000477/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(188,"广东卫视","http://{serverip}/000000002000/201500000335/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(189,"湖北卫视","http://{serverip}/000000002000/201500000159/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(190,"广西卫视","http://{serverip}/000000002000/201500000512/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(191,"山东卫视","http://{serverip}/000000002000/201500000071/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(192,"四川卫视","http://{serverip}/000000002000/201500000458/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(193,"重庆卫视","http://{serverip}/000000002000/201500000459/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(194,"云南卫视","http://{serverip}/000000002000/201500000513/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(195,"河南卫视","http://{serverip}/000000002000/201500000476/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(196,"黑龙江卫视","http://{serverip}/000000002000/201500000072/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(197,"贵州卫视","http://{serverip}/000000002000/201500000347/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(198,"河北卫视","http://{serverip}/000000002000/201500000348/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(199,"吉林卫视","http://{serverip}/000000002000/201500000475/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(200,"陕西卫视","http://{serverip}/000000002000/201500000514/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(201,"甘肃卫视","http://{serverip}/000000002000/201500000461/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(202,"卡酷少儿","http://{serverip}/000000002000/201500000515/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(203,"青海卫视","http://{serverip}/000000002000/201500000516/index.m3u8?starttime={timestamp}"))
        channels.add(Channel(204,"CCTV16 4K 超高清", "http://192.168.99.1:7088/udp/239.76.253.230:9000"))
        channels.add(Channel(205,"CCTV16 4K", "http://192.168.99.1:7088/udp/239.76.254.200:9000"))
        channels.add(Channel(206,"CCTV 4K", "http://192.168.99.1:7088/udp/239.76.254.64:9000"))
        channels.add(Channel(207,"CCTV 4K 超高清", "http://192.168.99.1:7088/udp/239.76.254.101:9000"))
        channels.add(Channel(208,"欢笑剧场 4K 超高清", "http://192.168.99.1:7088/udp/239.76.253.43:9000"))
        channels.add(Channel(209,"北京卫视4K", "http://192.168.99.1:7088/udp/239.76.253.150:9000"))
        channels.add(Channel(210,"深圳卫视4K", "http://192.168.99.1:7088/udp/239.76.254.137:9000"))
        channels.add(Channel(211,"广东卫视4K", "http://192.168.99.1:7088/udp/239.76.254.139:9000"))
        saveChannels()
    }

    /**
     * 将内存中的频道列表保存到本地文件中，实现持久化。
     */
    private fun saveChannels() {
        channelsFile.printWriter().use {
            channels.forEach { c -> it.println("${c.number},${c.name},${c.url}") }
        }
    }

    /**
     * 清空所有频道数据和本地文件。
     */
    private fun clearChannels() {
        channels.clear()
        if (channelsFile.exists()) channelsFile.delete()
        playerManager.player.stop()
        channelAdapter.notifyDataSetChanged() // 更新UI
    }

    /**
     * 播放指定索引的频道。
     * 这是核心的播放触发方法。
     */
    private fun playChannel(index: Int) {
        if (index !in channels.indices) return // 安全检查，防止索引越界
        currentChannelIndex = index
        playerManager.play(channels[index].url) // 调用PlayerManager执行播放
        channelAdapter.updatePlayingPosition(index) // 通知Adapter更新UI高亮
        // 保存当前频道索引，以便下次启动时恢复
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(PREF_LAST_CHANNEL, index) }
    }

    /* ---------- 遥控器按键处理 ---------- */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        resetHideTimers() // 任何按键都会重置UI隐藏计时器

        // --- 数字键处理 ---
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (inputBuffer.length < 4) { // 限制最多输入4位频道号
                inputBuffer += (keyCode - KeyEvent.KEYCODE_0).toString()
                showInput()
            }
            return true // 消费事件
        }

        // --- 当频道列表显示时的特殊处理 ---
        if (binding.channelList.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_V || keyCode == KeyEvent.KEYCODE_BACK) {
                binding.channelList.isVisible = false // 左键或返回键隐藏列表
                return true
            }
            return super.onKeyDown(keyCode, event) // 其他按键（如上下）交由系统处理，以滚动列表
        }

        // --- 全局按键处理 ---
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> playChannel((currentChannelIndex - 1 + channels.size) % channels.size) // 上键：切换到上一个频道（循环）
            KeyEvent.KEYCODE_DPAD_DOWN -> playChannel((currentChannelIndex + 1) % channels.size) // 下键：切换到下一个频道（循环）
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_C -> showChannelList() // 中间/确认/右键/C键：显示频道列表
            KeyEvent.KEYCODE_BACK -> {
                if (inputBuffer.isNotEmpty()) { // 如果正在输入数字，返回键用于取消输入
                    inputBuffer = ""
                    binding.channelInputView.isVisible = false
                    channelSwitchHandler.removeCallbacks(channelSwitchRunnable)
                    return true
                }
                if (playerManager.player.isPlaying) { // 如果正在播放，返回键用于停止播放
                    playerManager.stop()
                    return true
                }
                finish() // 如果已停止播放，返回键用于退出应用
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> toggleToolbar() // 菜单键；M键：切换Toolbar的显示和隐藏
            else -> return super.onKeyDown(keyCode, event) // 其他未处理的按键交由系统处理
        }
        return true // 消费事件
    }

    /**
     * 显示用户输入的数字，并启动一个延时任务来执行换台。
     */
    private fun showInput() {
        binding.channelInputView.text = inputBuffer
        binding.channelInputView.isVisible = true
        channelSwitchHandler.removeCallbacks(channelSwitchRunnable) // 取消上一个延时任务
        channelSwitchHandler.postDelayed(channelSwitchRunnable, 3000) // 3秒后执行换台
    }

    /**
     * 根据用户输入的数字切换到对应频道号的频道。
     */
    private fun switchByNumber() {
        val num = inputBuffer.toIntOrNull()
        inputBuffer = ""
        binding.channelInputView.isVisible = false
        val index = channels.indexOfFirst { it.number == num } // 查找频道号匹配的索引
        if (index >= 0) playChannel(index) // 如果找到则播放
    }

    /* ---------- UI 辅助方法 ---------- */

    /**
     * 显示频道列表，并滚动到当前播放的位置。
     */
    private fun showChannelList() {
        if (binding.channelList.isVisible) return
        binding.channelList.isVisible = true
        binding.channelList.scrollToPosition(currentChannelIndex)

        // 在列表滚动和布局完成后，再请求焦点，这是最可靠的方式
        binding.channelList.post {
            val view = binding.channelList.layoutManager?.findViewByPosition(currentChannelIndex)
            view?.requestFocus()
        }
    }

    /**
     * 切换顶部Toolbar的显示和隐藏状态。
     */
    private fun toggleToolbar() {
        supportActionBar?.let { if (it.isShowing) it.hide() else it.show() }
    }

    /**
     * 重置所有UI元素的自动隐藏计时器。
     * 在用户有任何交互时调用，以提供更好的体验。
     */
    private fun resetHideTimers() {
        // 先移除所有待执行的隐藏任务
        uiHandler.removeCallbacks(hideChannelListRunnable)
        uiHandler.removeCallbacks(hideToolbarRunnable)
        // 重新设定延时隐藏任务
        uiHandler.postDelayed(hideChannelListRunnable, 5000) // 频道列表5秒后隐藏
        uiHandler.postDelayed(hideToolbarRunnable, 20000) // Toolbar 20秒后隐藏
    }
}
