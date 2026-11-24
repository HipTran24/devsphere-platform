package com.project.devsphere.communication.application;

import com.project.devsphere.communication.api.dto.ChatMessageResponse;
import com.project.devsphere.communication.domain.ChatChannel;
import com.project.devsphere.communication.domain.ChatChannelMember;
import com.project.devsphere.communication.domain.ChatMessage;
import com.project.devsphere.communication.infrastructure.persistence.ChatChannelMemberRepository;
import com.project.devsphere.communication.infrastructure.persistence.ChatChannelRepository;
import com.project.devsphere.communication.infrastructure.persistence.ChatMessageRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.notification.application.NotificationService;
import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.realtime.application.RealtimeEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class ChatService {

    private final ChatChannelRepository chatChannelRepository;
    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AccessPolicy accessPolicy;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher;

    public ChatService(
            ChatChannelRepository chatChannelRepository,
            ChatChannelMemberRepository chatChannelMemberRepository,
            ChatMessageRepository chatMessageRepository,
            AccessPolicy accessPolicy,
            NotificationService notificationService,
            JdbcTemplate jdbcTemplate) {
        this(chatChannelRepository, chatChannelMemberRepository, chatMessageRepository,
                accessPolicy, notificationService, jdbcTemplate, null);
    }

    @Autowired
    public ChatService(
            ChatChannelRepository chatChannelRepository,
            ChatChannelMemberRepository chatChannelMemberRepository,
            ChatMessageRepository chatMessageRepository,
            AccessPolicy accessPolicy,
            NotificationService notificationService,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher) {
        this.chatChannelRepository = Objects.requireNonNull(chatChannelRepository, "chatChannelRepository must not be null");
        this.chatChannelMemberRepository = Objects.requireNonNull(chatChannelMemberRepository, "chatChannelMemberRepository must not be null");
        this.chatMessageRepository = Objects.requireNonNull(chatMessageRepository, "chatMessageRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    public ChatChannel createChannel(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            ChatChannel.Type type,
            String name,
            UUID workItemId) {

        if (type == null) {
            throw ChatException.validationFailed("Channel type must not be null");
        }

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        // Validation: (type = 'TASK') = (work_item_id IS NOT NULL)
        if (type == ChatChannel.Type.TASK && workItemId == null) {
            throw ChatException.validationFailed("TASK channel requires workItemId");
        }
        if (type != ChatChannel.Type.TASK && workItemId != null) {
            throw ChatException.validationFailed("Non-TASK channel must not specify workItemId");
        }

        // Validation: type NOT IN ('TASK','PROJECT','CLIENT_PM') OR project_id IS NOT NULL
        if ((type == ChatChannel.Type.TASK || type == ChatChannel.Type.PROJECT || type == ChatChannel.Type.CLIENT_PM) && projectId == null) {
            throw ChatException.validationFailed("Project ID required for channel type: " + type);
        }

        // Role constraints for channel creator
        if (type == ChatChannel.Type.TEAM || type == ChatChannel.Type.TASK || type == ChatChannel.Type.PROJECT) {
            if (scope.hasRole(ProjectRole.CLIENT) &&
                    !scope.hasRole(ProjectRole.PM) &&
                    !scope.hasRole(ProjectRole.DEVELOPER) &&
                    !scope.hasRole(ProjectRole.VIEWER)) {
                throw ChatException.clientRestricted("CLIENT-only member cannot enter internal channel");
            }
        } else if (type == ChatChannel.Type.CLIENT_PM) {
            if (!scope.hasRole(ProjectRole.PM) && !scope.hasRole(ProjectRole.CLIENT)) {
                throw ChatException.accessDenied("CLIENT_PM channel requires CLIENT or PM role");
            }
        }

        ChatChannel channel = new ChatChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setProjectId(projectId);
        channel.setWorkItemId(workItemId);
        channel.setType(type);
        channel.setName(name);
        channel.setCreatedBy(principal.userId());
        channel.setVersion(0L);

        ChatChannel savedChannel = chatChannelRepository.save(channel);

        // Automatically add creator to chat_channel_members
        ChatChannelMember member = new ChatChannelMember();
        member.setWorkspaceId(workspaceId);
        member.setProjectId(projectId);
        member.setChannelId(savedChannel.getId());
        member.setUserId(principal.userId());
        member.setJoinedAt(Instant.now());
        member.setMuted(false);
        member.setVersion(0L);
        chatChannelMemberRepository.save(member);

        return savedChannel;
    }

    @Transactional(readOnly = true)
    public List<ChatChannel> listChannels(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        return chatChannelRepository.findUserChannels(workspaceId, projectId, principal.userId());
    }

    public ChatChannelMember addMember(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId,
            UUID userId) {

        if (userId == null) {
            throw ChatException.validationFailed("userId must not be null");
        }

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        ChatChannel channel = chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId)
                .orElseThrow(() -> ChatException.channelNotFound("Chat channel not found: " + channelId));

        // Caller must be project PM or an active member of this channel
        boolean isCallerMember = chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, principal.userId());
        if (!scope.hasRole(ProjectRole.PM) && !isCallerMember) {
            throw ChatException.accessDenied("Caller must be a channel member or project PM to add members");
        }

        // Verify target user is active workspace member
        String wmSql = """
                SELECT COUNT(1)
                FROM workspace_members wm
                JOIN users u ON u.id = wm.user_id
                WHERE wm.workspace_id = ?
                  AND wm.user_id = ?
                  AND wm.status = 'ACTIVE'
                  AND u.status = 'ACTIVE'
                """;
        Integer wmCount = jdbcTemplate.queryForObject(wmSql, Integer.class, workspaceId, userId);
        if (wmCount == null || wmCount == 0) {
            throw ChatException.validationFailed("Channel member requires active Workspace membership");
        }

        // Verify target user is active project member (if project_id != null)
        if (projectId != null) {
            String pmSql = """
                    SELECT COUNT(1)
                    FROM project_members pm
                    WHERE pm.project_id = ?
                      AND pm.user_id = ?
                      AND pm.status = 'ACTIVE'
                    """;
            Integer pmCount = jdbcTemplate.queryForObject(pmSql, Integer.class, projectId, userId);
            if (pmCount == null || pmCount == 0) {
                throw ChatException.validationFailed("Channel member requires Project membership");
            }

            // Role constraints for target user
            Boolean isClient = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'CLIENT')", Boolean.class, projectId, userId);
            Boolean isPm = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'PM')", Boolean.class, projectId, userId);
            Boolean isDev = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'DEVELOPER')", Boolean.class, projectId, userId);
            Boolean isViewer = jdbcTemplate.queryForObject("SELECT ds_has_project_role(?, ?, 'VIEWER')", Boolean.class, projectId, userId);

            if (channel.getType() == ChatChannel.Type.TEAM || channel.getType() == ChatChannel.Type.TASK || channel.getType() == ChatChannel.Type.PROJECT) {
                if (Boolean.TRUE.equals(isClient) &&
                        !Boolean.TRUE.equals(isPm) &&
                        !Boolean.TRUE.equals(isDev) &&
                        !Boolean.TRUE.equals(isViewer)) {
                    throw ChatException.clientRestricted("CLIENT-only member cannot enter internal channel");
                }
            } else if (channel.getType() == ChatChannel.Type.CLIENT_PM) {
                if (!Boolean.TRUE.equals(isPm) && !Boolean.TRUE.equals(isClient)) {
                    throw ChatException.accessDenied("CLIENT_PM channel requires CLIENT or PM");
                }
            }
        }

        // Check if user is already a member
        if (chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ChatException.duplicateMember("User is already a member of this channel: " + userId);
        }

        ChatChannelMember member = new ChatChannelMember();
        member.setWorkspaceId(workspaceId);
        member.setProjectId(projectId);
        member.setChannelId(channelId);
        member.setUserId(userId);
        member.setJoinedAt(Instant.now());
        member.setMuted(false);
        member.setVersion(0L);

        return chatChannelMemberRepository.save(member);
    }

    public ChatMessage sendMessage(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId,
            String body,
            List<UUID> mentions,
            UUID replyToId) {

        if (body == null || body.trim().isEmpty()) {
            throw ChatException.validationFailed("Message body must not be blank");
        }

        ChatChannel channel = chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId)
                .orElseThrow(() -> ChatException.channelNotFound("Chat channel not found: " + channelId));

        // Sender must be an active channel member
        if (!chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, principal.userId())) {
            throw ChatException.notChannelMember("Sender is not an active channel member");
        }

        // Check mentions
        UUID[] mentionArray = new UUID[0];
        if (mentions != null && !mentions.isEmpty()) {
            for (UUID mentionId : mentions) {
                if (!chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, mentionId)) {
                    throw ChatException.mentionNotMember("Mention must reference channel membership: " + mentionId);
                }
            }
            mentionArray = mentions.toArray(new UUID[0]);
        }

        // Check reply
        if (replyToId != null) {
            ChatMessage reply = chatMessageRepository.findById(replyToId)
                    .orElseThrow(() -> ChatException.replyMismatch("Reply message not found: " + replyToId));
            if (!channelId.equals(reply.getChannelId())) {
                throw ChatException.replyMismatch("Reply message must belong to the same channel");
            }
        }

        ChatMessage message = new ChatMessage();
        message.setWorkspaceId(workspaceId);
        message.setProjectId(projectId);
        message.setChannelId(channelId);
        message.setSenderId(principal.userId());
        message.setSenderKind(ChatMessage.SenderKind.USER);
        message.setBody(body.trim());
        message.setMentions(mentionArray);
        message.setReplyToId(replyToId);
        message.setSentAt(Instant.now());
        message.setVersion(0L);

        ChatMessage saved = chatMessageRepository.save(message);

        // Notify mentions via NotificationService
        if (mentions != null) {
            for (UUID mentionId : mentions) {
                if (!mentionId.equals(principal.userId())) {
                    String channelName = channel.getName() != null && !channel.getName().isBlank()
                            ? channel.getName()
                            : "chat channel";
                    String eventKey = "chat:mention:" + channelId + ":" + saved.getId() + ":" + mentionId;
                    String preview = body.length() > 60 ? body.substring(0, 57) + "..." : body;
                    notificationService.createNotification(
                            workspaceId,
                            mentionId,
                            projectId,
                            "CHAT_MENTION",
                            eventKey,
                            "Mentioned in " + channelName,
                            "You were mentioned in " + channelName + ": \"" + preview + "\"",
                            "/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId
                    );
                }
            }
        }

        // Publish real-time chat message event after transaction commit
        RealtimeEventPublisher publisher = realtimeEventPublisher == null ? null : realtimeEventPublisher.getIfAvailable();
        if (publisher != null && saved != null && workspaceId != null && projectId != null && channelId != null) {
            publisher.publishChatMessage(workspaceId, projectId, channelId, ChatMessageResponse.from(saved));
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<ChatMessage> listMessages(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId,
            Pageable pageable) {

        ChatChannel channel = chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId)
                .orElseThrow(() -> ChatException.channelNotFound("Chat channel not found: " + channelId));

        if (!chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, principal.userId())) {
            throw ChatException.notChannelMember("Caller is not an active channel member");
        }

        return chatMessageRepository.findByChannelIdOrderBySentAtAsc(channelId, pageable);
    }
}
