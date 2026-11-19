package com.livingroom.home

import android.service.notification.NotificationListenerService

/**
 * Lets the home screen see what is playing (title, artist, time, picture) so the phone remote can show it.
 * It reads nothing from notifications; Android only hands out "what is playing" to apps that have this.
 */
class MediaWatch : NotificationListenerService()
