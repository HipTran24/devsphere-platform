package com.project.devsphere.communication.application;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock private ChatChannelRepository chatChannelRepository;
    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private NotificationService notificationService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ChatService chatService;

    private UUID workspaceId;
    private UUID projectId;
    private UUID callerUserId;
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatChannelRepository,
                chatChannelMemberRepository,
                chatMessageRepository,
                accessPolicy,
                notificationService,
                jdbcTemplate
        );

        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        principal = new SessionService.Principal(callerUserId, UUID.randomUUID(), "Alice", "USER");
    }

    @Test
    @DisplayName("createChannel: TASK channel requires workItemId")
    void createChannelTaskRequiresWorkItem() {
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.PM)));

        assertThatThrownBy(() -> chatService.createChannel(principal, workspaceId, projectId, ChatChannel.Type.TASK, "task-channel", null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("TASK channel requires workItemId");
    }

    @Test
    @DisplayName("createChannel: Non-TASK channel rejects workItemId")
    void createChannelNonTaskRejectsWorkItem() {
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.PM)));

        assertThatThrownBy(() -> chatService.createChannel(principal, workspaceId, projectId, ChatChannel.Type.PROJECT, "project-channel", UUID.randomUUID()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("Non-TASK channel must not specify workItemId");
    }

    @Test
    @DisplayName("createChannel: CLIENT-only creator is blocked from internal channels")
    void createChannelClientOnlyBlockedFromInternal() {
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.CLIENT)));

        assertThatThrownBy(() -> chatService.createChannel(principal, workspaceId, projectId, ChatChannel.Type.TEAM, "team-internal", null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("CLIENT-only member cannot enter internal channel");
    }

    @Test
    @DisplayName("createChannel: Success creates channel and automatically adds creator as member")
    void createChannelSuccess() {
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.DEVELOPER)));

        ChatChannel channel = new ChatChannel();
        channel.setId(UUID.randomUUID());
        channel.setType(ChatChannel.Type.PROJECT);
        channel.setName("general");
        when(chatChannelRepository.save(any(ChatChannel.class))).thenReturn(channel);

        ChatChannel created = chatService.createChannel(principal, workspaceId, projectId, ChatChannel.Type.PROJECT, "general", null);

        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo("general");
        verify(chatChannelMemberRepository).save(argThat(m ->
                m.getChannelId().equals(channel.getId()) && m.getUserId().equals(callerUserId)
        ));
    }

    @Test
    @DisplayName("addMember: Non-PM caller who is not a channel member is rejected")
    void addMemberNonPmNonMemberRejected() {
        UUID channelId = UUID.randomUUID();
        UUID targetUser = UUID.randomUUID();

        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.DEVELOPER)));

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        channel.setType(ChatChannel.Type.TEAM);
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(false);

        assertThatThrownBy(() -> chatService.addMember(principal, workspaceId, projectId, channelId, targetUser))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("Caller must be a channel member or project PM");
    }

    @Test
    @DisplayName("addMember: Duplicate member is rejected with 409 DUPLICATE_MEMBER")
    void addMemberDuplicateRejected() {
        UUID channelId = UUID.randomUUID();
        UUID targetUser = UUID.randomUUID();

        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, callerUserId, Set.of(ProjectRole.PM)));

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        channel.setType(ChatChannel.Type.TEAM);
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(jdbcTemplate.queryForObject(contains("workspace_members"), eq(Integer.class), eq(workspaceId), eq(targetUser)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("project_members"), eq(Integer.class), eq(projectId), eq(targetUser)))
                .thenReturn(1);

        when(jdbcTemplate.queryForObject(contains("ds_has_project_role"), eq(Boolean.class), eq(projectId), eq(targetUser)))
                .thenReturn(false); // not client

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(true);
        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, targetUser)).thenReturn(true);

        assertThatThrownBy(() -> chatService.addMember(principal, workspaceId, projectId, channelId, targetUser))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("User is already a member of this channel");
    }

    @Test
    @DisplayName("sendMessage: Sender not in channel is rejected")
    void sendMessageSenderNotInChannelRejected() {
        UUID channelId = UUID.randomUUID();

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(false);

        assertThatThrownBy(() -> chatService.sendMessage(principal, workspaceId, projectId, channelId, "Hello", null, null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("Sender is not an active channel member");
    }

    @Test
    @DisplayName("sendMessage: Mentioning outside user is rejected")
    void sendMessageMentionOutsideUserRejected() {
        UUID channelId = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(true);
        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, outsider)).thenReturn(false);

        assertThatThrownBy(() -> chatService.sendMessage(principal, workspaceId, projectId, channelId, "Hey @outsider", List.of(outsider), null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("Mention must reference channel membership");
    }

    @Test
    @DisplayName("sendMessage: Success creates message and triggers mention notifications")
    void sendMessageSuccess() {
        UUID channelId = UUID.randomUUID();
        UUID mentionedUser = UUID.randomUUID();

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        channel.setName("dev-chat");
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(true);
        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, mentionedUser)).thenReturn(true);

        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setBody("Hello @user");
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(msg);

        ChatMessage result = chatService.sendMessage(principal, workspaceId, projectId, channelId, "Hello @user", List.of(mentionedUser), null);

        assertThat(result).isNotNull();
        verify(notificationService).createNotification(
                eq(workspaceId),
                eq(mentionedUser),
                eq(projectId),
                eq("CHAT_MENTION"),
                anyString(),
                contains("Mentioned in dev-chat"),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("listMessages: Channel member can list messages")
    void listMessagesSuccess() {
        UUID channelId = UUID.randomUUID();

        ChatChannel channel = new ChatChannel();
        channel.setId(channelId);
        when(chatChannelRepository.findByIdAndWorkspaceIdAndProjectId(channelId, workspaceId, projectId))
                .thenReturn(Optional.of(channel));

        when(chatChannelMemberRepository.existsByChannelIdAndUserId(channelId, callerUserId)).thenReturn(true);

        ChatMessage msg = new ChatMessage();
        msg.setId(UUID.randomUUID());
        msg.setBody("Test message");

        Page<ChatMessage> page = new PageImpl<>(List.of(msg));
        when(chatMessageRepository.findByChannelIdOrderBySentAtAsc(eq(channelId), any())).thenReturn(page);

        Page<ChatMessage> result = chatService.listMessages(principal, workspaceId, projectId, channelId, PageRequest.of(0, 50));
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getBody()).isEqualTo("Test message");
    }
}
