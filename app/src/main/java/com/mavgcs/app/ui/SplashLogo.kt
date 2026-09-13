package com.mavgcs.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.mavgcs.app.R

/**
 * The artwork's own background, sampled from the file rather than assumed to be
 * pure black. The system splash window uses the same value, so the handover
 * between the two does not show a seam.
 */
private val SplashBackground = Color(0xFF080809)

@Composable
fun SplashLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SplashBackground),
        contentAlignment = Alignment.Center,
    ) {
        // Cropped to fill rather than fitted: the artwork carries a faint noise
        // texture, so letterboxing it against a flat fill shows its edge as a
        // rectangle. The logo sits well inside the frame, so the crop misses it.
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = "MavGCS",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
