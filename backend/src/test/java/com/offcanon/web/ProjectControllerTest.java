package com.offcanon.web;

import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.application.ProjectApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.project.domain.Project;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectControllerTest {
    @Test
    void updateRequiresAnExplicitAcceptancePolicyEvenWhenItIsEmpty() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ApiDtos.UpdateProjectRequest("demo", "C:\\code\\demo", null);

            var violations = factory.getValidator().validate(request);

            assertEquals(1, violations.size());
            assertEquals("verificationCommands",
                    violations.iterator().next().getPropertyPath().toString());
        }
    }

    @Test
    void distinguishesNewRegistrationFromReopeningInTheHttpContract() {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID ownerId = UUID.randomUUID();
        Project project = Project.create(ownerId, "demo", Path.of("demo"), List.of("mvn test"), Instant.now());
        when(identity.ownerId(any())).thenReturn(ownerId);
        ProjectController controller = new ProjectController(projects, experiments, identity);
        var body = new ApiDtos.CreateProjectRequest("demo", project.canonicalPath().toString(), List.of("mvn test"));

        when(projects.registerWithOutcome(ownerId, body.name(), body.canonicalPath(), body.verificationCommands()))
                .thenReturn(new ProjectApplicationService.RegistrationResult(project, false));
        var created = controller.createProject(body, request);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertFalse(created.getBody().reopened());
        assertEquals(project.id(), created.getBody().id());

        when(projects.registerWithOutcome(ownerId, body.name(), body.canonicalPath(), body.verificationCommands()))
                .thenReturn(new ProjectApplicationService.RegistrationResult(project, true));
        var reopened = controller.createProject(body, request);

        assertEquals(HttpStatus.OK, reopened.getStatusCode());
        assertTrue(reopened.getBody().reopened());
        assertEquals(project.id(), reopened.getBody().id());
    }
}
