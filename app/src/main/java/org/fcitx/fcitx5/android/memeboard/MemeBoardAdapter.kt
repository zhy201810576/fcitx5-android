/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.crossfade
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class MemeBoardAdapter(
    private val client: MtPhotosClient,
    private val theme: Theme,
    private val imageLoader: ImageLoader,
    private val onSend: (MtFile) -> Unit,
    private val onLongClick: (MtFile) -> Unit,
    private val onImageError: () -> Unit,
) : RecyclerView.Adapter<MemeBoardAdapter.Holder>() {

    @Volatile var authCode: String = ""

    private val items = mutableListOf<MtFile>()

    fun submit(list: List<MtFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<MtFile>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val iv = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.popupBackgroundColor)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                parent.context.dp(80)
            )
        }
        return Holder(iv)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = items[position]
        val code = authCode
        if (code.isNotEmpty() && file.md5.isNotEmpty()) {
            holder.image.load(client.thumbUrl(file.md5, code), imageLoader) {
                crossfade(150)
                listener(onError = { _, _ -> onImageError() })
            }
        }
        holder.image.contentDescription = file.fileName.ifBlank { file.id.toString() }
        holder.image.setOnClickListener { onSend(file) }
        holder.image.setOnLongClickListener { onLongClick(file); true }
    }

    override fun getItemCount() = items.size
}
