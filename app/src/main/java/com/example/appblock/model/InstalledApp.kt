package com.example.appblock.model

import android.graphics.drawable.Drawable

/**
 * Represents an installed launchable application eligible for focus blocking.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isSelected: Boolean = false,
    val isSuspended: Boolean = false,
    val isEligible: Boolean = true
)

