package tv.slopoff

import android.service.notification.NotificationListenerService

/** Android uses this authorization for media-session events. Notification contents are never read. */
class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() { CaptureSessionService.instance?.connectMedia() }
    override fun onListenerDisconnected() { CaptureSessionService.instance?.disconnectMedia() }
}
