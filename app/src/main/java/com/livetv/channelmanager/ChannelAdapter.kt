package com.livetv.channelmanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.livetv.channelmanager.databinding.ItemChannelBinding

class ChannelAdapter(
    private val items: MutableList<Channel>,
    private val onEdit: (Channel, Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    inner class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        with(holder.b) {
            tvChannelNumber.text = "CH ${ch.channelNumber}"
            tvChannelName.text = ch.name
            tvStreamUrl.text = ch.streamUrl
            btnEdit.setOnClickListener { onEdit(ch, position) }
            btnDelete.setOnClickListener { onDelete(position) }
        }
    }

    override fun getItemCount() = items.size
}
