package com.livetv.channelmanager

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Channel(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val channelNumber: Int,
    val streamUrl: String,
    val logoUrl: String = "",
    val epgId: String = ""
) : Parcelable
