package com.collabdocs.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SmtpMail implements MailPort {
    private final JavaMailSender sender;
    private final String from;

    public SmtpMail(JavaMailSender sender, @Value("${collabdocs.mail-from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }
}
