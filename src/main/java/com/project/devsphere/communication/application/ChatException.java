package com.project.devsphere.communication.application;

import org.springframework.http.HttpStatus;

public class ChatException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ChatException channelNotFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND", message);
    }

    public static ChatException messageNotFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", message);
    }

    public static ChatException validationFailed(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static ChatException accessDenied(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static ChatException clientRestricted(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "CLIENT_RESTRICTED", message);
    }

    public static ChatException notChannelMember(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "NOT_CHANNEL_MEMBER", message);
    }

    public static ChatException mentionNotMember(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "MENTION_NOT_MEMBER", message);
    }

    public static ChatException replyMismatch(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "REPLY_MISMATCH", message);
    }

    public static ChatException duplicateMember(String message) {
        return new ChatException(HttpStatus.CONFLICT, "DUPLICATE_MEMBER", message);
    }
}
