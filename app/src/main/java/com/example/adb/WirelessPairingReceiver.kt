package com.example.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.R
import com.example.input.ShizukuInputController

/**
 * Handles inline notification replies (RemoteInput) so users can enter the 6-digit
 * Wireless Debugging pairing code directly from the notification shade while Android's
 * "Pair device with pairing code" dialog remains open on screen.
 */
class WirelessPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT_CODE -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim().orEmpty()
                if (code.length == 6 && code.all { it.isDigit() }) {
                    val pendingResult = goAsync()
                    ShizukuInputController.init(context)
                    ShizukuInputController.pairFromNotification(context.applicationContext, code) {
                        pendingResult.finish()
                    }
                } else {
                    showPairingNotification(
                        context = context,
                        endpoint = ShizukuInputController.discoveredPairingEndpoint.value,
                        statusOverride = context.getString(R.string.notif_pair_invalid_code)
                    )
                }
            }

            ACTION_CANCEL_PAIRING -> {
                ShizukuInputController.cancelNotificationPairing(context)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "padforge_wireless_pairing"
        const val NOTIFICATION_ID = 2002
        const val ACTION_SUBMIT_CODE = "com.example.action.SUBMIT_PAIRING_CODE"
        const val ACTION_CANCEL_PAIRING = "com.example.action.CANCEL_PAIRING"
        const val KEY_PAIRING_CODE = "key_pairing_code"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_pair_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_pair_channel_desc)
                }
                nm.createNotificationChannel(channel)
            }
        }

        fun showPairingNotification(
            context: Context,
            endpoint: AdbEndpoint?,
            statusOverride: String? = null
        ) {
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val replyIntent = Intent(context, WirelessPairingReceiver::class.java).apply {
                action = ACTION_SUBMIT_CODE
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                10,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val cancelIntent = Intent(context, WirelessPairingReceiver::class.java).apply {
                action = ACTION_CANCEL_PAIRING
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                11,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel(context.getString(R.string.field_pairing_code))
                .build()

            val replyAction = NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.notif_pair_reply_action),
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()

            val title = if (endpoint != null) {
                context.getString(R.string.notif_pair_found_title, endpoint.port)
            } else {
                context.getString(R.string.notif_pair_searching_title)
            }

            val body = statusOverride ?: if (endpoint != null) {
                context.getString(R.string.notif_pair_found_body)
            } else {
                context.getString(R.string.notif_pair_searching_body)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(replyAction)
                .addAction(0, context.getString(R.string.action_cancel), cancelPendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        }

        fun showPairingResultNotification(
            context: Context,
            success: Boolean,
            message: String
        ) {
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val openAppIntent = PendingIntent.getActivity(
                context,
                12,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = if (success) {
                context.getString(R.string.notif_pair_success_title)
            } else {
                context.getString(R.string.notif_pair_failed_title)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        }

        fun dismiss(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
