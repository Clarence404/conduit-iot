package dev.conduit.server.notification;

/** 可替换的邀请链接投递通道；令牌绝不写入日志。 */
public interface InvitationNotificationChannel {
    String key();
    void send(String address, String inviteUrl);
}
