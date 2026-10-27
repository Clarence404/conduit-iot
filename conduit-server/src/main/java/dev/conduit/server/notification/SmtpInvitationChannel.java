package dev.conduit.server.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** First notification plugin: SMTP. Other transports can implement the same interface. */
@Component
@ConditionalOnProperty(name = "spring.mail.host")
public class SmtpInvitationChannel implements InvitationNotificationChannel {
    private final JavaMailSender mail;
    private final String from;

    public SmtpInvitationChannel(JavaMailSender mail,
                                 @Value("${conduit.notification.email.from:}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override public String key() { return "email"; }

    @Override public void send(String address, String inviteUrl) {
        if (from.isBlank()) throw new IllegalStateException("Invitation sender address is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject("Conduit IoT 账号邀请");
        message.setText("你收到了 Conduit IoT 的账号邀请。请在 48 小时内打开以下链接设置密码：\n\n"
                + inviteUrl + "\n\n如果你没有预期收到邀请，请忽略此邮件。");
        mail.send(message);
    }
}
