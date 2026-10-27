package dev.conduit.server.notification;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmtpInvitationChannelTest {
    @Test
    void sendsLinkThroughInjectedMailProvider() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var channel = new SmtpInvitationChannel(sender, "demo@conduit.local");
        channel.send("person@example.test", "https://example.test/invitations/accept?token=sample");
        var captured = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captured.capture());
        assertThat(captured.getValue().getTo()).containsExactly("person@example.test");
        assertThat(captured.getValue().getText()).contains("https://example.test/invitations/accept?token=sample");
    }
}
