package com.example.tvplayer.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tvplayer.databinding.ChannelListItemBinding
import com.example.tvplayer.model.Channel

/**
 * 用于在RecyclerView中显示频道列表的适配器。
 *
 * @param channels 频道数据列表。
 * @param onItemClick 频道项目点击事件的回调函数，返回被点击项的位置。
 */
class ChannelAdapter(
    private val channels: List<Channel>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    // 记录当前正在播放的频道在列表中的位置，默认为0。
    private var playingPosition = 0

    /**
     * 更新当前正在播放的频道位置。
     * 当用户切换频道时，外部会调用此方法来通知Adapter。
     *
     * @param newPosition 新的播放位置。
     */
    fun updatePlayingPosition(newPosition: Int) {
        val oldPosition = playingPosition // 保存旧的播放位置
        playingPosition = newPosition
        // 通知RecyclerView刷新旧的播放项UI（例如，取消高亮）
        notifyItemChanged(oldPosition)
        // 通知RecyclerView刷新新的播放项UI（例如，设置为播放中高亮）
        notifyItemChanged(newPosition)
    }

    /**
     * 当RecyclerView需要新的ViewHolder时调用。
     * 在这里加载列表项的布局 `channel_list_item.xml`。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        // 使用ViewBinding来加载（inflate）布局
        val binding = ChannelListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = ChannelViewHolder(binding)
        // 关键步骤：确保每个列表项都是可聚焦的，这样遥控器才能在它们之间导航。
        holder.itemView.isFocusable = true
        return holder
    }

    /**
     * 将数据绑定到指定的ViewHolder上。
     * RecyclerView在滚动时会为每个可见的列表项调用此方法。
     *
     * @param holder 要绑定数据的ViewHolder。
     * @param position 列表项在数据源中的位置。
     */
    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        // 将频道数据（如频道号和名称）设置到视图上
        holder.bind(channels[position])

        // 初始化时，根据当前状态（是否聚焦、是否在播放）更新高亮
        updateHighlight(holder, position, holder.itemView.isFocused)

        // 设置焦点变化监听器，以便在用户通过遥控器导航时改变外观
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            // 当焦点状态改变时，更新高亮
            updateHighlight(holder, position, hasFocus)
        }

        // 设置点击事件监听器，当用户按下确认键时触发
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            // 确保位置有效，防止在数据变化时点击导致崩溃
            if (pos != RecyclerView.NO_POSITION) {
                // 调用外部传入的回调函数，通知主逻辑用户点击了哪个频道
                onItemClick(pos)
            }
        }
    }

    /**
     * 根据列表项的三个状态（聚焦、正在播放、普通）来更新其UI。
     * 这是实现TV端列表视觉反馈的核心逻辑。
     *
     * @param holder 要更新的ViewHolder。
     * @param position 列表项的位置。
     * @param hasFocus 该列表项当前是否拥有焦点。
     */
    private fun updateHighlight(holder: ChannelViewHolder, position: Int, hasFocus: Boolean) {
        // 检查此项是否为当前正在播放的频道
        val isPlaying = (position == playingPosition)

        if (hasFocus) {
            // 1. 聚焦状态 (最高优先级): 遥控器光标停留在此项上
            //    设置为白底黑字，以提供清晰的视觉反馈。
            holder.itemView.setBackgroundColor(Color.WHITE)
            holder.binding.channelName.setTextColor(Color.BLACK)
        } else {
            // 2. 非聚焦状态: 遥控器光标在别处
            if (isPlaying) {
                // 2.1 正在播放状态: 虽然没有焦点，但这是当前播放的频道
                //     设置为绿底白字，以醒目标记。
                holder.itemView.setBackgroundColor(Color.parseColor("#4CAF50"))
                holder.binding.channelName.setTextColor(Color.WHITE)
            } else {
                // 2.2 普通状态: 既没有焦点，也不是正在播放的频道
                //     设置为透明底白字，作为默认外观。
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                holder.binding.channelName.setTextColor(Color.WHITE)
            }
        }
    }

    /**
     * 返回数据源中的项目总数。
     * RecyclerView需要知道要显示多少个列表项。
     */
    override fun getItemCount() = channels.size

    /**
     * 频道的ViewHolder，作为列表项视图的容器。
     * 它持有了通过ViewBinding找到的视图组件的引用。
     * @param binding 包含了对 `channel_list_item.xml` 布局中所有视图的引用。
     */
    inner class ChannelViewHolder(val binding: ChannelListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        /**
         * 将频道数据绑定到具体的视图组件上。
         * @param channel 包含频道信息的对象。
         */
        fun bind(channel: Channel) {
            // 格式化字符串，将频道号和频道名拼接起来显示。
            binding.channelName.text = "${channel.number} ${channel.name}"
        }
    }
}
