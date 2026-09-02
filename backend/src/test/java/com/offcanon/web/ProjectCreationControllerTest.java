package com.offcanon.web;

import com.offcanon.application.ProjectCreationApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.shared.web.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectCreationControllerTest {
    @Test
    void refusesNonLoopbackRequestsBeforeCreatingAProject() {
        ProjectCreationApplicationService creator = mock(ProjectCreationApplicationService.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(identity.ownerId(request)).thenReturn(UUID.randomUUID());
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        var body = new ApiDtos.CreateProjectRequest(
                "demo", "C:/projects/demo", List.of("mvn test"));

        ProjectCreationController controller = new ProjectCreationController(creator, identity);

        assertThrows(ForbiddenException.class, () -> controller.create(body, request));
        verifyNoInteractions(creator);
    }
}
