package com.voicedialer.app.data.model

import android.graphics.drawable.Drawable

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val photo: Drawable? = null
)
