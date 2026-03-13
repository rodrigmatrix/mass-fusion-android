package com.limelight.ui

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.limelight.R
import com.limelight.utils.SpinnerDialog

object LoadingChecker {

    private var loadingVideoActive = false
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private val videoMap = mapOf(
        "Nintendo Switch" to "switch_loading.mp4",
        "Desktop" to "windows.mp4",
        "Virtual Display" to "windows.mp4",
        "Xbox" to "xbox.mp4",
        "Playnite" to "xbox.mp4",
    )

    @JvmStatic
    fun showLoading(activity: Activity, appName: String?): SpinnerDialog? {
        val video = videoMap[appName]
        if (video != null) {
            val overlay = activity.findViewById<PlayerView>(R.id.loadingVideoOverlay)
            if (overlay != null) {
                playerView = overlay
                overlay.visibility = View.VISIBLE

                val exoPlayer = ExoPlayer.Builder(activity).build()
                overlay.player = exoPlayer

                val mediaItem = MediaItem.fromUri("asset:///$video".toUri())
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                player = exoPlayer

                loadingVideoActive = true
                return null
            }
        }

        // Fallback to spinner dialog
        loadingVideoActive = false
        return SpinnerDialog.displayDialog(
            activity,
            activity.getString(R.string.conn_establishing_title),
            activity.getString(R.string.conn_establishing_msg),
            true
        )
    }

    @JvmStatic
    fun setMessage(
        context: Context,
        resId: Int,
        spinner: SpinnerDialog?,
        stage: String = "",
    ) {
          spinner?.setMessage(context.getString(resId) + " " + stage)
    }

    @JvmStatic
    fun dismissLoading(context: Context, spinner: SpinnerDialog?) {
        if (loadingVideoActive) {
            val currentPlayer = player
            val overlay = playerView

            if (currentPlayer != null && overlay != null) {
                // If video already ended, hide immediately
                if (currentPlayer.playbackState == Player.STATE_ENDED) {
                    hideAndRelease()
                    return
                }

                // Wait for video to finish, then hide
                currentPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            hideAndRelease()
                        }
                    }
                })

                // Safety timeout: hide after 10 seconds no matter what
                overlay.postDelayed({
                    hideAndRelease()
                }, 10_000)
            } else {
                loadingVideoActive = false
            }
        } else {
            spinner?.dismiss()
        }
    }

    private fun hideAndRelease() {
        loadingVideoActive = false
        playerView?.visibility = View.GONE
        player?.release()
        player = null
        playerView = null
    }

    @JvmStatic
    fun release() {
        hideAndRelease()
    }
}
