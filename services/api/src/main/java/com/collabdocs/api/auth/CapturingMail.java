package com.collabdocs.api.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class CapturingMail implements MailPort {
    public final List<Message> messages = new ArrayList<>();

    @Override
    public synchronized void send(String to, String subject, String text) {
        messages.add(new Message(to, subject, text));
    }

    public synchronized void clear() {
        messages.clear();
    }

    public record Message(String to, String subject, String text) {}
}
