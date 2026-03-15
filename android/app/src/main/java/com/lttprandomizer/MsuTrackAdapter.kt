package com.lttprandomizer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

interface MsuTrackListener {
    fun onPlayClick(track: MsuTrackSlot, position: Int)
    fun onPlayOriginalClick(track: MsuTrackSlot, position: Int)
}

class MsuTrackAdapter(
    private val tracks: List<MsuTrackSlot>,
    private val listener: MsuTrackListener
) : RecyclerView.Adapter<MsuTrackAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val slotNumber: TextView = view.findViewById(R.id.slotNumber)
        val trackName: TextView = view.findViewById(R.id.trackName)
        val typeLabel: TextView = view.findViewById(R.id.typeLabel)
        val fileIndicator: TextView = view.findViewById(R.id.fileIndicator)
        val playBtn: Button = view.findViewById(R.id.playBtn)
        val playOrigBtn: Button = view.findViewById(R.id.playOrigBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_msu_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]

        holder.slotNumber.text = track.slotDisplay
        holder.trackName.text = track.name

        if (track.hasTypeLabel) {
            holder.typeLabel.text = track.typeLabel
            holder.typeLabel.visibility = View.VISIBLE
        } else {
            holder.typeLabel.visibility = View.GONE
        }

        holder.fileIndicator.text = track.fileName ?: "---"

        if (track.hasFile) {
            holder.playBtn.text = track.playButtonText
            holder.playBtn.visibility = View.VISIBLE
            holder.playBtn.setOnClickListener { listener.onPlayClick(track, position) }
        } else {
            holder.playBtn.visibility = View.GONE
        }

        if (track.hasOriginal) {
            holder.playOrigBtn.text = track.originalPlayButtonText
            holder.playOrigBtn.visibility = View.VISIBLE
            holder.playOrigBtn.setOnClickListener { listener.onPlayOriginalClick(track, position) }
        } else {
            holder.playOrigBtn.visibility = View.GONE
        }
    }

    override fun getItemCount() = tracks.size
}
