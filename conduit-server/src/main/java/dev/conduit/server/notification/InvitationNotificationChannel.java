package dev.conduit.server.notification;

/** Replaceable delivery channel for invitation links. Tokens never enter logs. */
public interface InvitationNotificationChannel {
    String key();
    void send(String address, String inviteUrl);
}
