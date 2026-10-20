package com.collabdocs.api.auth;

public interface MailPort {
    void send(String to, String subject, String text);
}
