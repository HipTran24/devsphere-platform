package com.project.devsphere.communication.api;

import com.project.devsphere.communication.api.dto.*;
import com.project.devsphere.communication.application.ChatException;
import com.project.devsphere.communication.application.ChatService;
import com.project.devsphere.communication.domain.ChatChannel;
import com.project.devsphere.communication.domain.ChatChannelMember;
import com.project.devsphere.communication.domain.ChatMessage;
import com.project.devsphere.identity.application.SessionService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/chat/channels")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
    }

    @PostMapping
    public ResponseEntity<ChatChannelResponse> createChannel(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody CreateChannelRequest request) {

        ChatChannel channel = chatService.createChannel(
                principal,
                workspaceId,
                projectId,
                request.type(),
                request.name(),
                request.workItemId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ChatChannelResponse.from(channel));
    }

    @GetMapping
    public ResponseEntity<List<ChatChannelResponse>> listChannels(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId) {

        List<ChatChannel> channels = chatService.listChannels(principal, workspaceId, projectId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(channels.stream().map(ChatChannelResponse::from).toList());
    }

    @PostMapping("/{channelId}/members")
    public ResponseEntity<ChatChannelMemberResponse> addMember(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("channelId") UUID channelId,
            @Valid @RequestBody AddMemberRequest request) {

        ChatChannelMember member = chatService.addMember(
                principal,
                workspaceId,
                projectId,
                channelId,
                request.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ChatChannelMemberResponse.from(member));
    }

    @PostMapping("/{channelId}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("channelId") UUID channelId,
            @Valid @RequestBody SendMessageRequest request) {

        ChatMessage message = chatService.sendMessage(
                principal,
                workspaceId,
                projectId,
                channelId,
                request.body(),
                request.mentions(),
                request.replyToId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ChatMessageResponse.from(message));
    }

    @GetMapping("/{channelId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> listMessages(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("channelId") UUID channelId,
            @PageableDefault(size = 50, sort = "sentAt", direction = Sort.Direction.ASC) Pageable pageable) {

        Page<ChatMessage> messages = chatService.listMessages(
                principal,
                workspaceId,
                projectId,
                channelId,
                pageable
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(messages.getContent().stream().map(ChatMessageResponse::from).toList());
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ProblemDetail> handleChatException(ChatException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("ACCESS_DENIED");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleValidationExceptions(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed: " + ex.getMessage());
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity conflict: " + ex.getMessage());
        problem.setTitle("CONFLICT");
        problem.setProperty("code", "CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
