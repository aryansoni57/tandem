// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

/** Packet type strings. Identity and pair, then ping, battery, clipboard and share. */
object PacketTypes {
    const val IDENTITY = "kdeconnect.identity"
    const val PAIR = "kdeconnect.pair"
    const val PING = "kdeconnect.ping"
    const val BATTERY = "kdeconnect.battery"
    const val BATTERY_REQUEST = "kdeconnect.battery.request"
    const val CLIPBOARD = "kdeconnect.clipboard"
    const val CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
    const val CLIPBOARD_FILE = "kdeconnect.clipboard.file"
    const val NOTIFICATION = "kdeconnect.notification"
    const val NOTIFICATION_REQUEST = "kdeconnect.notification.request"
    const val NOTIFICATION_REPLY = "kdeconnect.notification.reply"
    const val NOTIFICATION_ACTION = "kdeconnect.notification.action"
    const val MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
    const val MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
    const val MOUSEPAD_ECHO = "kdeconnect.mousepad.echo"
    const val MPRIS = "kdeconnect.mpris"
    const val MPRIS_REQUEST = "kdeconnect.mpris.request"
    const val PRESENTER = "kdeconnect.presenter"
    const val RUNCOMMAND = "kdeconnect.runcommand"
    const val RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
    const val FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
    const val TELEPHONY = "kdeconnect.telephony"
    const val TELEPHONY_REQUEST_MUTE = "kdeconnect.telephony.request_mute"
    const val SMS_MESSAGES = "kdeconnect.sms.messages"
    const val SMS_REQUEST = "kdeconnect.sms.request"
    const val SMS_REQUEST_CONVERSATIONS = "kdeconnect.sms.request_conversations"
    const val SMS_REQUEST_CONVERSATION = "kdeconnect.sms.request_conversation"
    const val SMS_REQUEST_ATTACHMENT = "kdeconnect.sms.request_attachment"
    const val SMS_ATTACHMENT_FILE = "kdeconnect.sms.attachment_file"
    const val SFTP = "kdeconnect.sftp"
    const val SFTP_REQUEST = "kdeconnect.sftp.request"
    const val SHARE_REQUEST = "kdeconnect.share.request"
    const val SHARE_REQUEST_UPDATE = "kdeconnect.share.request.update"

    /** Types that may be sent to a peer before pairing and before capabilities are known. */
    val UNPAIRED_ALLOWED: Set<String> = setOf(IDENTITY, PAIR)
}
